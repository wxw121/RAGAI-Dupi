package com.dupi.rag.service;

import com.dupi.rag.config.UploadQuotaProperties;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.IngestOutboxEvent;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import com.dupi.rag.repository.UploadQuotaReservationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Short fenced database boundaries for stale upload reconciliation. */
@Service
class UploadQuotaReconciliationPersistenceService {
    private static final String CLEANUP_OWNER_PREFIX = "upload-reconciliation:";

    private final UploadQuotaReservationRepository reservations;
    private final DocumentRepository documents;
    private final IngestJobRepository jobs;
    private final IngestOutboxEventRepository outbox;
    private final long leaseSeconds;

    @Autowired
    UploadQuotaReconciliationPersistenceService(
            UploadQuotaReservationRepository reservations,
            DocumentRepository documents,
            IngestJobRepository jobs,
            IngestOutboxEventRepository outbox,
            UploadQuotaProperties properties) {
        this(reservations, documents, jobs, outbox, properties.getAttemptLeaseSeconds());
    }

    UploadQuotaReconciliationPersistenceService(
            UploadQuotaReservationRepository reservations,
            DocumentRepository documents,
            IngestJobRepository jobs,
            IngestOutboxEventRepository outbox,
            long leaseSeconds) {
        this.reservations = reservations;
        this.documents = documents;
        this.jobs = jobs;
        this.outbox = outbox;
        this.leaseSeconds = Math.max(1L, leaseSeconds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean releaseMissing(UploadQuotaAttemptCandidate candidate, String reason) {
        UploadQuotaReservation current = current(candidate);
        if (current == null || documents.findById(candidate.attemptId()).isPresent()) {
            return false;
        }
        release(current, reason);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean commitPublished(UploadQuotaAttemptCandidate candidate, UUID documentId, UUID jobId) {
        IngestJob job = jobs.findByIdForUpdate(jobId).orElse(null);
        Document document = documents.findByIdForUpdate(documentId).orElse(null);
        if (job == null || document == null
                || !documentId.equals(candidate.attemptId())
                || !documentId.equals(job.getDocId())
                || job.getStatus() != IngestJobStatus.PENDING
                || job.getStage() != IngestStage.QUEUED
                || document.getStatus() != DocumentStatus.PENDING
                || !hasPublishedOutbox(jobId)) {
            return false;
        }
        UploadQuotaReservation current = current(candidate);
        if (current == null) {
            return false;
        }
        current.setDocId(documentId);
        current.setAttemptId(null);
        current.setAttemptExpiresAt(null);
        current.setStatus(UploadQuotaReservationStatus.COMMITTED);
        current.setReleaseReason(null);
        reservations.save(current);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<UploadLegacyCleanupClaim> claimLegacyCleanup(
            UploadQuotaAttemptCandidate candidate, UUID documentId, UUID jobId, Instant now) {
        IngestJob job = jobId == null ? null : jobs.findByIdForUpdate(jobId).orElse(null);
        if (jobId != null && (job == null || !documentId.equals(job.getDocId()))) {
            return Optional.empty();
        }
        Document document = documents.findByIdForUpdate(documentId).orElse(null);
        if (document == null || document.getImportJobId() != null
                || document.getStatus() == DocumentStatus.IMPORTING
                || document.getStatus() == DocumentStatus.DELETING) {
            return Optional.empty();
        }
        UploadQuotaReservation current = current(candidate);
        if (current == null || current.getAttemptExpiresAt() == null
                || current.getAttemptExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        String owner = CLEANUP_OWNER_PREFIX + UUID.randomUUID();
        current.setReleaseReason(owner);
        current.setAttemptExpiresAt(now.plusSeconds(leaseSeconds));
        reservations.save(current);
        return Optional.of(new UploadLegacyCleanupClaim(
                current.getId(), candidate.attemptId(), documentId, jobId,
                document.getObjectKey(), owner));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean completeLegacyCleanup(UploadLegacyCleanupClaim claim, String reason) {
        IngestJob job = claim.jobId() == null ? null : jobs.findByIdForUpdate(claim.jobId()).orElse(null);
        if (claim.jobId() != null && (job == null || !claim.documentId().equals(job.getDocId()))) {
            return false;
        }
        Document document = documents.findByIdForUpdate(claim.documentId()).orElse(null);
        if (document != null && document.getStatus() == DocumentStatus.DELETING) {
            return false;
        }
        UploadQuotaReservation current = reservations.findById(claim.reservationId()).orElse(null);
        if (document == null || current == null
                || current.getStatus() != UploadQuotaReservationStatus.PENDING
                || !claim.attemptId().equals(current.getAttemptId())
                || !claim.ownerToken().equals(current.getReleaseReason())) {
            return false;
        }
        if (job != null) {
            outbox.deleteByJobId(job.getId());
            jobs.delete(job);
        }
        document.setStatus(DocumentStatus.FAILED);
        document.setErrorMessage("Upload attempt expired before ingest dispatch became durable");
        document.setQuotaReservationId(null);
        documents.save(document);
        release(current, reason);
        return true;
    }

    private UploadQuotaReservation current(UploadQuotaAttemptCandidate candidate) {
        if (candidate == null || candidate.reservationId() == null || candidate.attemptId() == null) {
            return null;
        }
        return reservations.findById(candidate.reservationId())
                .filter(current -> current.getStatus() == UploadQuotaReservationStatus.PENDING)
                .filter(current -> candidate.attemptId().equals(current.getAttemptId()))
                .filter(current -> Objects.equals(candidate.ownerToken(), current.getReleaseReason()))
                .orElse(null);
    }

    private boolean hasPublishedOutbox(UUID jobId) {
        return outbox.findByJobId(jobId).stream().map(IngestOutboxEvent::getStatus)
                .anyMatch(status -> status == IngestOutboxStatus.PENDING
                        || status == IngestOutboxStatus.FAILED);
    }

    private void release(UploadQuotaReservation reservation, String reason) {
        reservation.setDocId(null);
        reservation.setAttemptId(null);
        reservation.setAttemptExpiresAt(null);
        reservation.setStatus(UploadQuotaReservationStatus.RELEASED);
        reservation.setReleaseReason(reason);
        reservations.save(reservation);
    }
}
