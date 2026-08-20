package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void prepare(String tenantId, Document document, IngestJob job) {
        KnowledgeBase locked = knowledgeBases
                .findByIdAndTenantIdForUpdateAnyStatus(document.getKbId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Knowledge base not found: " + document.getKbId()));
        KnowledgeBaseLifecyclePolicy.requireReady(locked, document.getKbId());
        documents.save(document);
        long revision = locked.getIndexRevision() == null ? 0L : locked.getIndexRevision();
        locked.setIndexRevision(revision + 1);
        knowledgeBases.save(locked);
        jobs.saveAndFlush(job);
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
        documents.save(document);
        jobs.saveAndFlush(job);
    }

    private String limit(String diagnostic) {
        String text = diagnostic == null || diagnostic.isBlank() ? "Upload failed" : diagnostic;
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }
}
