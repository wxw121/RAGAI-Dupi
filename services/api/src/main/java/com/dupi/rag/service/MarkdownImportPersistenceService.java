package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.DocumentAsset;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.IngestOutboxEvent;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.repository.DocumentAssetRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Fenced metadata/quota transactions for Markdown prepare, atomic publish, and compensation. */
@Service
@RequiredArgsConstructor
class MarkdownImportPersistenceService {
    private final OperationDomainGuard guard;
    private final OperationJobRepository jobs;
    private final OperationStepRepository steps;
    private final DocumentRepository documents;
    private final DocumentAssetRepository assets;
    private final IngestJobRepository ingestJobs;
    private final IngestOutboxEventRepository outbox;
    private final UploadQuotaService quotas;
    private final KnowledgeBaseRepository knowledgeBases;

    @Transactional
    void assertActive(OperationExecutionContext context) { guard.assertActive(context); }

    @Transactional
    void prepare(OperationExecutionContext context, MarkdownImportPlan plan, String stepKey) {
        OperationJob operation = guard.assertActive(context);
        var knowledgeBase = knowledgeBases.findByIdForUpdate(plan.knowledgeBaseId())
                .orElseThrow(() -> new MarkdownImportInvariantException(
                        "Markdown knowledge base is not available for import"));
        if (!operation.getTenantId().equals(knowledgeBase.getTenantId())
                || knowledgeBase.getLifecycleStatus() != KnowledgeBaseLifecycleStatus.READY) {
            throw new MarkdownImportInvariantException(
                    "Markdown knowledge base is not available for import");
        }
        List<Document> existing = documents.findByImportJobIdOrderByCreatedAtAsc(context.jobId());
        if (!existing.isEmpty()) {
            verifyPrepared(plan, existing, operation);
            completeStep(context.jobId(), stepKey);
            return;
        }
        Instant now = Instant.now();
        for (MarkdownImportPlan.MarkdownDocument item : plan.documents()) {
            UUID reservationId = MarkdownImportPlan.deterministicId(context.jobId(), "quota", item.path());
            var reservation = quotas.reserveForImport(reservationId, operation.getTenantId(), operation.getCreatedBy(),
                    plan.knowledgeBaseId(), item.documentId(),
                    "markdown:" + context.jobId() + ":" + reservationId, item.byteSize(), "sha256:" + item.sha256());
            Document document = Document.builder().id(item.documentId()).kbId(plan.knowledgeBaseId())
                    .fileName(item.fileName()).objectKey(item.objectKey()).mimeType("text/markdown")
                    .fileSize(item.byteSize()).quotaReservationId(reservation.getId())
                    .importJobId(context.jobId()).status(DocumentStatus.IMPORTING).createdAt(now).updatedAt(now).build();
            documents.save(document);
            for (MarkdownImportPlan.Asset itemAsset : item.assets()) {
                assets.save(DocumentAsset.builder().id(itemAsset.assetId()).kbId(plan.knowledgeBaseId())
                        .docId(item.documentId()).relativePath(itemAsset.reference())
                        .objectKey(itemAsset.objectKey()).mimeType(itemAsset.mimeType())
                        .fileName(itemAsset.fileName()).fileSize(itemAsset.byteSize()).createdAt(now).build());
            }
        }
        documents.flush(); assets.flush();
        completeStep(context.jobId(), stepKey);
    }

    @Transactional
    void publish(OperationExecutionContext context, MarkdownImportPlan plan, String stepKey) {
        OperationJob operation = guard.assertActive(context);
        List<Document> owned = documents.findByImportJobIdOrderByCreatedAtAsc(context.jobId());
        verifyPrepared(plan, owned, operation);
        Instant now = Instant.now();
        for (MarkdownImportPlan.MarkdownDocument item : plan.documents()) {
            Document document = owned.stream().filter(candidate -> candidate.getId().equals(item.documentId()))
                    .findFirst().orElseThrow();
            if (document.getStatus() != DocumentStatus.IMPORTING) {
                throw new MarkdownImportInvariantException("Markdown document became visible before atomic publish");
            }
            UUID ingestId = MarkdownImportPlan.deterministicId(context.jobId(), "ingest", item.path());
            IngestJob ingest = ingestJobs.findById(ingestId).orElseGet(() -> ingestJobs.save(IngestJob.builder()
                    .id(ingestId).executionId(MarkdownImportPlan.deterministicId(context.jobId(), "execution", item.path()))
                    .kbId(plan.knowledgeBaseId()).docId(item.documentId()).status(IngestJobStatus.PENDING)
                    .stage(IngestStage.QUEUED).retryCount(0).callbackSequence(0L).createdAt(now).updatedAt(now).build()));
            UUID outboxId = MarkdownImportPlan.deterministicId(context.jobId(), "outbox", item.path());
            if (!outbox.existsById(outboxId)) {
                outbox.save(IngestOutboxEvent.builder().id(outboxId).jobId(ingest.getId())
                        .kbId(plan.knowledgeBaseId()).docId(item.documentId()).objectKey(item.objectKey())
                        .fileName(item.fileName()).mimeType("text/markdown").status(IngestOutboxStatus.PENDING)
                        .attemptCount(0).nextAttemptAt(now).createdAt(now).updatedAt(now).build());
            }
            quotas.commitForImport(document.getQuotaReservationId(), operation.getTenantId(), operation.getCreatedBy(),
                    document.getId());
            document.setStatus(DocumentStatus.PENDING); document.setErrorMessage(null);
            documents.save(document);
        }
        var knowledgeBase = knowledgeBases.findByIdForUpdate(plan.knowledgeBaseId())
                .orElseThrow(() -> new MarkdownImportInvariantException("Markdown knowledge base is missing during publish"));
        knowledgeBase.setIndexRevision((knowledgeBase.getIndexRevision() == null ? 0L : knowledgeBase.getIndexRevision()) + 1);
        knowledgeBases.save(knowledgeBase);
        completeStep(context.jobId(), stepKey);
        operation.setStatus(OperationStatus.COMPLETED); operation.setRunnable(false);
        operation.setCompletedAt(now); operation.setLastError(null); operation.setNextAttemptAt(null);
        operation.setClaimToken(null); operation.setLeaseExpiresAt(null);
        jobs.saveAndFlush(operation);
    }

    @Transactional
    void compensateMetadata(OperationExecutionContext context, MarkdownImportPlan plan, String stepKey) {
        OperationJob operation = guard.assertActive(context);
        List<Document> owned = documents.findByImportJobIdOrderByCreatedAtAsc(context.jobId());
        for (Document document : owned) {
            ingestJobs.findTopByDocIdOrderByCreatedAtDesc(document.getId()).ifPresent(ingest -> {
                outbox.deleteAll(outbox.findByJobId(ingest.getId()));
                ingestJobs.delete(ingest);
            });
            assets.deleteAll(assets.findByDocIdOrderByCreatedAtAsc(document.getId()));
            quotas.releaseForImport(document.getQuotaReservationId(), operation.getTenantId(), operation.getCreatedBy(),
                    document.getId(), "Markdown import compensated");
        }
        documents.deleteAll(owned);
        documents.flush();
        completeStep(context.jobId(), stepKey);
    }

    private void verifyPrepared(MarkdownImportPlan plan, List<Document> existing, OperationJob operation) {
        if (existing.size() != plan.documents().size()) {
            throw new MarkdownImportInvariantException("Markdown import metadata differs from its immutable plan");
        }
        for (MarkdownImportPlan.MarkdownDocument item : plan.documents()) {
            Document document = existing.stream().filter(candidate -> candidate.getId().equals(item.documentId()))
                    .findFirst().orElseThrow(() -> new MarkdownImportInvariantException("Markdown import document is missing"));
            if (!plan.knowledgeBaseId().equals(document.getKbId()) || !item.objectKey().equals(document.getObjectKey())
                    || !item.fileName().equals(document.getFileName())
                    || !"text/markdown".equals(document.getMimeType())
                    || document.getFileSize() == null || document.getFileSize() != item.byteSize()
                    || document.getStatus() != DocumentStatus.IMPORTING
                    || !MarkdownImportPlan.deterministicId(plan.jobId(), "quota", item.path())
                            .equals(document.getQuotaReservationId())
                    || !contextFreeImport(plan, document)) {
                throw new MarkdownImportInvariantException("Markdown import document conflicts with its plan");
            }
            UUID reservationId = MarkdownImportPlan.deterministicId(plan.jobId(), "quota", item.path());
            quotas.verifyImportReservation(reservationId, operation.getTenantId(), operation.getCreatedBy(),
                    plan.knowledgeBaseId(), item.documentId(),
                    "markdown:" + plan.jobId() + ":" + reservationId,
                    item.byteSize(), "sha256:" + item.sha256());
            List<DocumentAsset> actualAssets = assets.findByDocIdOrderByCreatedAtAsc(document.getId());
            if (actualAssets.size() != item.assets().size()) {
                throw new MarkdownImportInvariantException("Markdown import assets differ from their plan");
            }
            for (MarkdownImportPlan.Asset expected : item.assets()) {
                DocumentAsset actual = actualAssets.stream()
                        .filter(candidate -> expected.assetId().equals(candidate.getId()))
                        .findFirst().orElseThrow(() -> new MarkdownImportInvariantException(
                                "Markdown import asset identity differs from its plan"));
                if (!plan.knowledgeBaseId().equals(actual.getKbId())
                        || !item.documentId().equals(actual.getDocId())
                        || !expected.reference().equals(actual.getRelativePath())
                        || !expected.objectKey().equals(actual.getObjectKey())
                        || !expected.mimeType().equals(actual.getMimeType())
                        || !expected.fileName().equals(actual.getFileName())
                        || actual.getFileSize() == null || actual.getFileSize() != expected.byteSize()) {
                    throw new MarkdownImportInvariantException("Markdown import asset conflicts with its plan");
                }
            }
        }
    }

    private boolean contextFreeImport(MarkdownImportPlan plan, Document document) {
        return document.getImportJobId() != null && plan.jobId().equals(document.getImportJobId());
    }

    private void completeStep(UUID jobId, String key) {
        OperationStep step = steps.findByJobIdAndStepKey(jobId, key)
                .orElseThrow(() -> new MarkdownImportInvariantException("Markdown operation step is missing: " + key));
        if (step.getStatus() == OperationStepStatus.COMPLETED) return;
        if (step.getStatus() != OperationStepStatus.RUNNING) {
            throw new MarkdownImportInvariantException("Markdown operation step is not running: " + key);
        }
        step.setStatus(OperationStepStatus.COMPLETED); step.setCompletedAt(Instant.now());
        step.setLastError(null); step.setNextAttemptAt(null); steps.saveAndFlush(step);
    }
}
