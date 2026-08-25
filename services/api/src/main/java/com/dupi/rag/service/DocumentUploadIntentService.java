package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persists an upload's in-flight ownership before object storage is touched. The short transactions
 * deliberately end before MinIO I/O; deletion observes the pending ingest job through its activity probe.
 */
@Service
@RequiredArgsConstructor
class DocumentUploadIntentService {
    private final KnowledgeBaseRepository knowledgeBases;
    private final DocumentRepository documents;
    private final IngestJobRepository jobs;
    private final UploadQuotaService quota;
    private final IngestOutboxService outbox;
    private final DocumentTombstoneService tombstones;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void prepare(String tenantId, Document document, IngestJob job) {
        KnowledgeBase locked = knowledgeBases
                .findByIdAndTenantIdForUpdateAnyStatus(document.getKbId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Knowledge base not found: " + document.getKbId()));
        KnowledgeBaseLifecyclePolicy.requireReady(locked, document.getKbId());
        document.setStatus(DocumentStatus.UPLOADING);
        document.setErrorMessage(null);
        job.setStatus(IngestJobStatus.UPLOAD_INTENT);
        job.setStage(IngestStage.UPLOAD_PENDING);
        job.setErrorMessage(null);
        documents.save(document);
        long revision = locked.getIndexRevision() == null ? 0L : locked.getIndexRevision();
        locked.setIndexRevision(revision + 1);
        knowledgeBases.save(locked);
        jobs.saveAndFlush(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    DocumentUploadPublication publish(String tenantId, Document intentDocument, IngestJob intentJob,
                                      UploadQuotaReservation reservation) {
        KnowledgeBase locked = knowledgeBases
                .findByIdAndTenantIdForUpdateAnyStatus(intentDocument.getKbId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Knowledge base not found: " + intentDocument.getKbId()));
        KnowledgeBaseLifecyclePolicy.requireReady(locked, intentDocument.getKbId());
        IngestJob job = jobs.findByIdForUpdate(intentJob.getId())
                .filter(candidate -> intentDocument.getKbId().equals(candidate.getKbId())
                        && intentDocument.getId().equals(candidate.getDocId()))
                .orElseThrow(() -> new IllegalStateException("Upload intent ingest job is missing"));
        Document document = documents.findById(intentDocument.getId())
                .filter(candidate -> intentDocument.getKbId().equals(candidate.getKbId()))
                .orElseThrow(() -> new IllegalStateException("Upload intent document is missing"));
        if (job.getStatus() != IngestJobStatus.UPLOAD_INTENT
                || job.getStage() != IngestStage.UPLOAD_PENDING
                || document.getStatus() != DocumentStatus.UPLOADING) {
            throw new IllegalStateException("Upload intent is no longer publishable");
        }

        quota.commitInCurrentTransaction(reservation, document);
        tombstones.disarmUploadCleanup(document);
        document.setStatus(DocumentStatus.PENDING);
        document.setErrorMessage(null);
        job.setStatus(IngestJobStatus.PENDING);
        job.setStage(IngestStage.QUEUED);
        job.setErrorMessage(null);
        documents.save(document);
        jobs.save(job);
        outbox.record(job, locked, document.getObjectKey(), document.getFileName(), document.getMimeType());
        jobs.flush();
        return new DocumentUploadPublication(document, job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    DocumentUploadPublicationResolution reconcilePublication(
            String tenantId, Document intentDocument, IngestJob intentJob) {
        knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(intentDocument.getKbId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Knowledge base not found: " + intentDocument.getKbId()));
        IngestJob job = jobs.findByIdForUpdate(intentJob.getId())
                .filter(candidate -> intentDocument.getKbId().equals(candidate.getKbId())
                        && intentDocument.getId().equals(candidate.getDocId()))
                .orElseThrow(() -> new IllegalStateException("Upload intent ingest job is missing"));
        Document document = documents.findById(intentDocument.getId())
                .filter(candidate -> intentDocument.getKbId().equals(candidate.getKbId()))
                .orElseThrow(() -> new IllegalStateException("Upload intent document is missing"));

        boolean exactIntent = job.getStatus() == IngestJobStatus.UPLOAD_INTENT
                && job.getStage() == IngestStage.UPLOAD_PENDING
                && document.getStatus() == DocumentStatus.UPLOADING;
        if (exactIntent) {
            return DocumentUploadPublicationResolution.retained();
        }
        if (job.getStatus() != IngestJobStatus.UPLOAD_INTENT
                && document.getStatus() != DocumentStatus.UPLOADING
                && outbox.hasDurableRecord(job.getId())) {
            return DocumentUploadPublicationResolution.published(document, job);
        }
        return DocumentUploadPublicationResolution.retained();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(Document document, IngestJob job, String diagnostic) {
        Instant now = Instant.now();
        String error = limit(diagnostic);
        document.setStatus(DocumentStatus.FAILED);
        document.setErrorMessage(error);
        document.setQuotaReservationId(null);
        job.setStatus(IngestJobStatus.FAILED);
        job.setStage(IngestStage.FAILED);
        job.setErrorMessage(error);
        job.setCompletedAt(now);
        outbox.cancelPendingForJob(job.getId(), "Upload publication failed");
        documents.save(document);
        jobs.saveAndFlush(job);
    }

    private String limit(String diagnostic) {
        String text = diagnostic == null || diagnostic.isBlank() ? "Upload failed" : diagnostic;
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }
}
