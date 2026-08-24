package com.dupi.rag.service;

import com.dupi.rag.config.UploadQuotaProperties;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
class UploadIntentCleanupService {
    private static final String OWNER_PREFIX = "upload-cleanup:";

    private final DocumentRepository documents;
    private final IngestJobRepository jobs;
    private final UploadQuotaService quota;
    private final IngestOutboxService outbox;
    private final long leaseSeconds;

    @Autowired
    UploadIntentCleanupService(
            DocumentRepository documents,
            IngestJobRepository jobs,
            UploadQuotaService quota,
            IngestOutboxService outbox,
            UploadQuotaProperties properties) {
        this(documents, jobs, quota, outbox, properties.getAttemptLeaseSeconds());
    }

    UploadIntentCleanupService(
            DocumentRepository documents,
            IngestJobRepository jobs,
            UploadQuotaService quota,
            IngestOutboxService outbox,
            long leaseSeconds) {
        this.documents = documents;
        this.jobs = jobs;
        this.quota = quota;
        this.outbox = outbox;
        this.leaseSeconds = Math.max(1L, leaseSeconds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    UploadIntentCleanupDecision claim(UploadQuotaReservation candidate, Instant now) {
        if (candidate == null || candidate.getAttemptId() == null) {
            return UploadIntentCleanupDecision.skipped();
        }
        Document observed = documents.findById(candidate.getAttemptId()).orElse(null);
        if (observed == null || observed.getImportJobId() != null || observed.getStatus() == DocumentStatus.IMPORTING) {
            return UploadIntentCleanupDecision.skipped();
        }
        IngestJob observedJob = jobs.findTopByDocIdOrderByCreatedAtDesc(observed.getId()).orElse(null);
        if (observedJob == null) {
            return UploadIntentCleanupDecision.skipped();
        }
        IngestJob job = jobs.findByIdForUpdate(observedJob.getId()).orElse(null);
        Document document = documents.findById(observed.getId()).orElse(null);
        if (job == null || document == null) {
            return UploadIntentCleanupDecision.skipped();
        }
        boolean published = job.getStatus() != IngestJobStatus.UPLOAD_INTENT
                && document.getStatus() != DocumentStatus.UPLOADING
                && outbox.hasDurableRecord(job.getId());
        if (published) {
            return UploadIntentCleanupDecision.published();
        }
        if (job.getStatus() == IngestJobStatus.UPLOAD_INTENT
                && job.getStage() == IngestStage.UPLOAD_CLEANUP
                && job.getLeaseExpiresAt() != null
                && job.getLeaseExpiresAt().isAfter(now)) {
            return UploadIntentCleanupDecision.skipped();
        }

        String owner = OWNER_PREFIX + UUID.randomUUID();
        Instant expiresAt = now.plusSeconds(leaseSeconds);
        job.setStatus(IngestJobStatus.UPLOAD_INTENT);
        job.setStage(IngestStage.UPLOAD_CLEANUP);
        job.setClaimedBy(owner);
        job.setLeaseExpiresAt(expiresAt);
        job.setErrorMessage("Cleaning up expired upload intent");
        quota.claimCleanupInCurrentTransaction(candidate, document.getId(), owner, expiresAt);
        jobs.saveAndFlush(job);
        return UploadIntentCleanupDecision.claimed(new UploadIntentCleanupClaim(
                candidate.getId(), document.getId(), job.getId(), document.getObjectKey(), owner));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void complete(UploadIntentCleanupClaim claim, String diagnostic) {
        IngestJob job = jobs.findByIdForUpdate(claim.jobId())
                .orElseThrow(() -> new IllegalStateException("Upload cleanup job is missing"));
        if (job.getStatus() != IngestJobStatus.UPLOAD_INTENT
                || job.getStage() != IngestStage.UPLOAD_CLEANUP
                || !claim.owner().equals(job.getClaimedBy())) {
            throw new IllegalStateException("Upload cleanup claim is stale");
        }
        Document document = documents.findById(claim.documentId())
                .orElseThrow(() -> new IllegalStateException("Upload cleanup document is missing"));
        String error = limit(diagnostic);
        outbox.cancelPendingForJob(job.getId(), "Upload attempt expired");
        quota.releaseCleanupInCurrentTransaction(
                UploadQuotaReservation.builder().id(claim.reservationId()).build(),
                document.getId(), claim.owner(), error);
        document.setStatus(DocumentStatus.FAILED);
        document.setErrorMessage(error);
        document.setQuotaReservationId(null);
        job.setStatus(IngestJobStatus.FAILED);
        job.setStage(IngestStage.FAILED);
        job.setErrorMessage(error);
        job.setClaimedBy(null);
        job.setLeaseExpiresAt(null);
        job.setCompletedAt(Instant.now());
        documents.save(document);
        jobs.saveAndFlush(job);
    }

    private String limit(String diagnostic) {
        String text = diagnostic == null || diagnostic.isBlank() ? "Upload attempt expired" : diagnostic;
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }
}
