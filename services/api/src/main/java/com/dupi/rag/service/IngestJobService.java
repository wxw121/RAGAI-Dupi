package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.config.LlmProperties;
import com.dupi.rag.config.RedisQueueProperties;
import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.dto.AuditAlertResponse;
import com.dupi.rag.dto.IngestCallbackAckResponse;
import com.dupi.rag.dto.IngestDiagnosisResponse;
import com.dupi.rag.dto.IngestJobResponse;
import com.dupi.rag.dto.IngestStatusUpdate;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IngestJobService {

    private static final long STALLED_JOB_SECONDS = 900;

    private final IngestJobRepository ingestJobRepository;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final IngestJobProducer ingestJobProducer;
    private final IngestOutboxService ingestOutboxService;
    private final DocumentTombstoneService documentTombstoneService;
    private final RedisQueueProperties redisQueueProperties;
    private final LlmProperties llmProperties;
    private final MilvusVectorService milvusVectorService;
    private final VectorCleanupTaskService vectorCleanupTaskService;
    private final AuditLogService auditLogService;
    private final IngestFailureNotificationService failureNotificationService;

    public IngestJobResponse getLatestByDoc(UUID docId) {
        IngestJob job = ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId)
                .orElseThrow(() -> new ResourceNotFoundException("Ingest job not found for doc: " + docId));
        return toResponse(job);
    }

    public List<IngestJobResponse> listByKb(UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        return ingestJobRepository.findByKbIdOrderByCreatedAtDesc(kbId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditAlertResponse> summarizeAlerts() {
        long openFailures = ingestJobRepository.countByStatusIn(List.of(
                IngestJobStatus.FAILED,
                IngestJobStatus.DEAD_LETTER
        ));
        if (openFailures <= 0) {
            return List.of();
        }
        return List.of(AuditAlertResponse.builder()
                .code("INGEST_FAILURES_OPEN")
                .severity("WARN")
                .message("Open ingest failed or dead-letter jobs need operator review")
                .count(openFailures)
                .threshold(0)
                .build());
    }

    @Transactional
    public IngestCallbackAckResponse handleStatusUpdate(IngestStatusUpdate update) {
        UUID jobId = UUID.fromString(update.getJobId());
        UUID docId = UUID.fromString(update.getDocId());
        if (documentTombstoneService.isDeleted(docId)) {
            log.info("Ignored ingest status update for tombstoned document {}, job {}", docId, jobId);
            return IngestCallbackAckResponse.ignored("document_tombstoned");
        }
        IngestJob job = findJobForUpdate(jobId);

        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        if (!job.getDocId().equals(doc.getId()) || !job.getKbId().equals(doc.getKbId())) {
            throw new IllegalArgumentException("Ingest status update does not match job/document");
        }

        UUID callbackExecutionId = parseUuid(update.getExecutionId());
        if (job.getExecutionId() != null && callbackExecutionId == null) {
            return IngestCallbackAckResponse.ignored("missing_execution");
        }
        if (callbackExecutionId != null && job.getExecutionId() != null && !callbackExecutionId.equals(job.getExecutionId())) {
            return IngestCallbackAckResponse.ignored("stale_execution");
        }
        if (job.getExecutionId() != null && update.getSequence() == null) {
            return IngestCallbackAckResponse.ignored("missing_sequence");
        }
        if (update.getSequence() != null && update.getSequence() <= safeCallbackSequence(job)) {
            return IngestCallbackAckResponse.ignored("stale_sequence");
        }

        IngestJobStatus nextStatus = parseStatus(update.getStatus());
        if (isTerminal(job.getStatus())) {
            return IngestCallbackAckResponse.ignored("terminal_state");
        }
        if (job.getStatus() == IngestJobStatus.CANCEL_REQUESTED
                && nextStatus != IngestJobStatus.CANCELLED) {
            return IngestCallbackAckResponse.ignored("cancel_requested");
        }
        if (!isLegalCallbackTransition(job.getStatus(), nextStatus)) {
            return IngestCallbackAckResponse.ignored("illegal_transition");
        }

        if (update.getStage() != null) {
            job.setStage(IngestStage.valueOf(update.getStage().toUpperCase()));
        }
        if (nextStatus != null) {
            job.setStatus(nextStatus);
        }
        if (update.getSequence() != null) {
            job.setCallbackSequence(update.getSequence());
        }
        job.setErrorMessage(update.getErrorMessage());

        if (update.getChunks() != null && !update.getChunks().isEmpty()) {
            chunkRepository.deleteByDocId(doc.getId());
            for (IngestStatusUpdate.ChunkPayload payload : update.getChunks()) {
                Chunk chunk = Chunk.builder()
                        .id(UUID.fromString(payload.getId()))
                        .kbId(doc.getKbId())
                        .docId(doc.getId())
                        .chunkIndex(payload.getChunkIndex())
                        .content(payload.getContent())
                        .tokenCount(payload.getTokenCount())
                        .metadata(payload.getMetadata())
                        .milvusId(payload.getMilvusId())
                        .build();
                chunkRepository.save(chunk);
            }
        }

        if ("COMPLETED".equalsIgnoreCase(update.getStatus())) {
            doc.setStatus(DocumentStatus.COMPLETED);
            doc.setErrorMessage(null);
            job.setStage(IngestStage.COMPLETED);
            job.setCompletedAt(Instant.now());
        } else if ("CANCELLED".equalsIgnoreCase(update.getStatus())) {
            doc.setStatus(DocumentStatus.CANCELLED);
            doc.setErrorMessage(null);
            job.setStage(IngestStage.CANCELLED);
            job.setCompletedAt(Instant.now());
        } else if ("FAILED".equalsIgnoreCase(update.getStatus())) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(update.getErrorMessage());
            job.setStage(IngestStage.FAILED);
            failureNotificationService.recordTerminalFailure(job, doc);
        } else {
            doc.setStatus(DocumentStatus.PROCESSING);
        }

        documentRepository.save(doc);
        ingestJobRepository.save(job);
        return IngestCallbackAckResponse.ok();
    }

    @Transactional
    public IngestJobResponse claim(UUID jobId, UUID executionId, String workerId, Duration leaseDuration) {
        IngestJob job = findJobForUpdate(jobId);
        UUID currentExecutionId = ensureExecutionId(job);
        if (!currentExecutionId.equals(executionId)) {
            throw new IllegalStateException("Ingest execution mismatch");
        }
        if (job.getStatus() != IngestJobStatus.PENDING || job.getStage() != IngestStage.QUEUED) {
            throw new IllegalStateException("Ingest job is not claimable");
        }
        Instant now = Instant.now();
        job.setStatus(IngestJobStatus.PROCESSING);
        job.setStage(IngestStage.PARSING);
        job.setClaimedBy(workerId);
        job.setStartedAt(now);
        job.setLeaseExpiresAt(now.plus(leaseDuration == null ? Duration.ofSeconds(60) : leaseDuration));
        job.setErrorMessage(null);
        ingestJobRepository.save(job);
        return toResponse(job);
    }

    @Transactional
    public IngestJobResponse refreshLease(UUID jobId, UUID executionId, String workerId, Duration leaseDuration) {
        IngestJob job = findJobForUpdate(jobId);
        if (!ensureExecutionId(job).equals(executionId)) {
            throw new IllegalStateException("Ingest execution mismatch");
        }
        if (job.getStatus() != IngestJobStatus.PROCESSING && job.getStatus() != IngestJobStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("Ingest job is not running");
        }
        if (job.getClaimedBy() != null && !job.getClaimedBy().equals(workerId)) {
            throw new IllegalStateException("Ingest job is claimed by another worker");
        }
        job.setClaimedBy(workerId);
        job.setLeaseExpiresAt(Instant.now().plus(leaseDuration == null ? Duration.ofSeconds(60) : leaseDuration));
        ingestJobRepository.save(job);
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public boolean isCancellationRequested(UUID jobId, UUID executionId) {
        IngestJob job = ingestJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Ingest job not found: " + jobId));
        if (job.getExecutionId() == null || !job.getExecutionId().equals(executionId)) {
            return true;
        }
        return job.getStatus() == IngestJobStatus.CANCEL_REQUESTED || isTerminal(job.getStatus());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getExecutionState(UUID jobId, UUID executionId) {
        IngestJob job = ingestJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Ingest job not found: " + jobId));
        boolean executionCurrent = executionId.equals(job.getExecutionId());
        boolean running = job.getStatus() == IngestJobStatus.PROCESSING
                || job.getStatus() == IngestJobStatus.CANCEL_REQUESTED;
        boolean leaseExpired = executionCurrent
                && running
                && job.getLeaseExpiresAt() != null
                && !job.getLeaseExpiresAt().isAfter(Instant.now());
        boolean requeueEligible = executionCurrent
                && job.getStatus() == IngestJobStatus.PENDING
                && job.getStage() == IngestStage.QUEUED;
        return Map.of(
                "status", job.getStatus(),
                "executionCurrent", executionCurrent,
                "terminal", isTerminal(job.getStatus()),
                "leaseExpired", leaseExpired,
                "requeueEligible", requeueEligible
        );
    }

    @Transactional
    public IngestJobResponse cancelForKnowledgeBase(UUID kbId, UUID jobId) {
        knowledgeBaseService.findOrThrow(kbId);
        IngestJob job = findJobForUpdate(jobId);
        if (!job.getKbId().equals(kbId)) {
            throw new IllegalArgumentException("Ingest job does not belong to knowledge base: " + kbId);
        }
        Document doc = documentRepository.findById(job.getDocId())
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        if (job.getStatus() == IngestJobStatus.PENDING) {
            job.setStatus(IngestJobStatus.CANCELLED);
            job.setStage(IngestStage.CANCELLED);
            job.setCompletedAt(Instant.now());
            job.setErrorMessage(null);
            doc.setStatus(DocumentStatus.CANCELLED);
            doc.setErrorMessage(null);
            ingestOutboxService.cancelPendingForJob(jobId, "ingest cancelled by user");
        } else if (job.getStatus() == IngestJobStatus.PROCESSING) {
            job.setStatus(IngestJobStatus.CANCEL_REQUESTED);
            job.setCancelRequestedAt(Instant.now());
            doc.setStatus(DocumentStatus.PROCESSING);
        } else if (!isTerminal(job.getStatus())) {
            job.setStatus(IngestJobStatus.CANCEL_REQUESTED);
            job.setCancelRequestedAt(Instant.now());
        }
        ingestJobRepository.save(job);
        documentRepository.save(doc);
        return toResponse(job, doc);
    }

    @Transactional
    public IngestJobResponse retry(UUID jobId) {
        IngestJob job = findJobForUpdate(jobId);
        KnowledgeBase kb = knowledgeBaseService.findSystemOrThrow(job.getKbId());
        return retryJob(job, kb);
    }

    @Transactional
    public IngestJobResponse retryForKnowledgeBase(UUID kbId, UUID jobId) {
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        IngestJob job = findJobForUpdate(jobId);
        if (!job.getKbId().equals(kbId)) {
            throw new IllegalArgumentException("Ingest job does not belong to knowledge base: " + kbId);
        }
        return retryJob(job, kb);
    }

    private IngestJobResponse retryJob(IngestJob job, KnowledgeBase kb) {
        if (job.getStatus() != IngestJobStatus.FAILED
                && job.getStatus() != IngestJobStatus.DEAD_LETTER) {
            throw new IllegalStateException("Only failed or dead-letter ingest jobs can be retried");
        }
        if (job.getStatus() != IngestJobStatus.DEAD_LETTER && job.getRetryCount() >= maxRecoveryAttempts()) {
            throw new IllegalStateException("Max retries exceeded");
        }
        job.setRetryCount(job.getStatus() == IngestJobStatus.DEAD_LETTER ? 0 : safeRetryCount(job) + 1);
        job.setStatus(IngestJobStatus.PENDING);
        job.setStage(IngestStage.QUEUED);
        job.setErrorMessage(null);
        rotateExecution(job);
        ingestJobRepository.save(job);

        Document doc = documentRepository.findById(job.getDocId())
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        doc.setStatus(DocumentStatus.PENDING);
        doc.setErrorMessage(null);
        documentRepository.save(doc);
        ingestOutboxService.record(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());
        auditLogService.recordSuccess(
                "INGEST_JOB_RETRY",
                "INGEST_JOB",
                job.getId(),
                "Retried ingest job " + job.getId() + " for knowledge base " + kb.getId()
        );

        return toResponse(job, doc);
    }

    @Transactional
    public List<IngestJobResponse> reindexKnowledgeBase(UUID kbId) {
        return reindexKnowledgeBase(
                kbId,
                llmProperties.getEmbedding().getModel(),
                llmProperties.getEmbedding().getDimension()
        );
    }

    @Transactional
    public List<IngestJobResponse> reindexKnowledgeBase(UUID kbId, String embeddingModel, int embeddingDimension) {
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        kb.setEmbeddingModel(embeddingModel);
        kb.setEmbeddingDimension(embeddingDimension);

        vectorCleanupTaskService.enqueueKnowledgeBase(kbId);
        try {
            milvusVectorService.deleteByKbId(kbId);
        } catch (Exception e) {
            log.warn("Failed to delete Milvus vectors before reindexing knowledge base {}", kbId, e);
            // 重建以数据库状态为主：向量库短暂不可用时，仍清理本地 chunks 并重建摄入任务；
            // 残留向量交给补偿任务继续清理，避免 reindex 操作被外部存储抖动卡死。
        }

        chunkRepository.deleteByKbId(kbId);
        List<IngestJobResponse> responses = documentRepository.findByKbIdOrderByCreatedAtDesc(kbId).stream()
                .map(doc -> requeueDocumentForReindex(kb, doc))
                .toList();
        auditLogService.recordSuccess(
                "KNOWLEDGE_BASE_REINDEX",
                "KNOWLEDGE_BASE",
                kbId,
                "Reindexed knowledge base " + kbId + " with " + responses.size() + " document(s)"
        );
        return responses;
    }

    @Scheduled(cron = "${dupi.ingest.recovery-cron:0 */2 * * * *}")
    public void recoverQueuedJobsOnSchedule() {
        int recovered = recoverQueuedJobs();
        if (recovered > 0) {
            log.info("Recovered {} queued ingest job(s)", recovered);
        }
    }

    @Transactional
    public int recoverQueuedJobs() {
        List<IngestJob> jobs = ingestJobRepository.findTop20ByStatusAndStageOrderByCreatedAtAsc(
                IngestJobStatus.PENDING, IngestStage.QUEUED);
        int recovered = recoverExpiredProcessingJobs();
        for (IngestJob job : jobs) {
            Document doc = documentRepository.findById(job.getDocId()).orElse(null);
            if (doc == null || doc.getStatus() != DocumentStatus.PENDING) {
                continue;
            }
            try {
                KnowledgeBase kb = knowledgeBaseService.findSystemOrThrow(job.getKbId());
                ingestJobProducer.enqueue(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());
                job.setErrorMessage(null);
                doc.setStatus(DocumentStatus.PROCESSING);
                doc.setErrorMessage(null);
                ingestJobRepository.save(job);
                documentRepository.save(doc);
                recovered++;
            } catch (Exception e) {
                log.warn("Failed to recover queued ingest job {}", job.getId(), e);
                markRecoveryFailure(job, doc, e);
            }
        }
        return recovered;
    }

    private int recoverExpiredProcessingJobs() {
        Instant now = Instant.now();
        List<IngestJob> jobs = ingestJobRepository.findTop20ByStatusAndLeaseExpiresAtBeforeOrderByUpdatedAtAsc(
                IngestJobStatus.PROCESSING, now);
        int recovered = 0;
        for (IngestJob job : jobs) {
            Document doc = documentRepository.findById(job.getDocId()).orElse(null);
            if (doc == null || doc.getStatus() != DocumentStatus.PROCESSING) {
                continue;
            }
            try {
                KnowledgeBase kb = knowledgeBaseService.findSystemOrThrow(job.getKbId());
                rotateExecution(job);
                job.setStatus(IngestJobStatus.PENDING);
                job.setStage(IngestStage.QUEUED);
                job.setErrorMessage(null);
                doc.setStatus(DocumentStatus.PENDING);
                doc.setErrorMessage(null);
                ingestJobRepository.save(job);
                documentRepository.save(doc);
                ingestOutboxService.record(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());
                recovered++;
            } catch (Exception e) {
                log.warn("Failed to recover expired processing ingest job {}", job.getId(), e);
                markRecoveryFailure(job, doc, e);
            }
        }
        return recovered + finalizeExpiredCancellations(now);
    }

    private int finalizeExpiredCancellations(Instant now) {
        List<IngestJob> jobs = ingestJobRepository.findTop20ByStatusAndLeaseExpiresAtBeforeOrderByUpdatedAtAsc(
                IngestJobStatus.CANCEL_REQUESTED, now);
        for (IngestJob job : jobs) {
            vectorCleanupTaskService.enqueueDocument(job.getDocId());
            job.setStatus(IngestJobStatus.CANCELLED);
            job.setStage(IngestStage.CANCELLED);
            job.setCompletedAt(now);
            job.setClaimedBy(null);
            job.setLeaseExpiresAt(null);
            job.setErrorMessage(null);
            ingestJobRepository.save(job);

            documentRepository.findById(job.getDocId()).ifPresent(doc -> {
                doc.setStatus(DocumentStatus.CANCELLED);
                doc.setErrorMessage(null);
                documentRepository.save(doc);
            });
        }
        return jobs.size();
    }

    private IngestJobResponse requeueDocumentForReindex(KnowledgeBase kb, Document doc) {
        Instant now = Instant.now();
        IngestJob job = IngestJob.builder()
                .id(UUID.randomUUID())
                .kbId(kb.getId())
                .docId(doc.getId())
                .status(IngestJobStatus.PENDING)
                .stage(IngestStage.QUEUED)
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        ingestJobRepository.save(job);

        doc.setStatus(DocumentStatus.PENDING);
        doc.setErrorMessage(null);
        ingestOutboxService.record(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());
        documentRepository.save(doc);
        return toResponse(job, doc);
    }

    private void markRecoveryFailure(IngestJob job, Document doc, Exception e) {
        int attempts = safeRetryCount(job) + 1;
        job.setRetryCount(attempts);
        String reason = errorReason(e);
        if (attempts >= maxRecoveryAttempts()) {
            job.setStatus(IngestJobStatus.DEAD_LETTER);
            job.setStage(IngestStage.DEAD_LETTER);
            job.setErrorMessage("Ingest recovery dead-letter after " + attempts + " attempts: " + reason);
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage("Ingest job moved to dead-letter after " + attempts + " recovery attempts: " + reason);
            failureNotificationService.recordTerminalFailure(job, doc);
            ingestJobRepository.save(job);
            documentRepository.save(doc);
            return;
        }
        job.setStatus(IngestJobStatus.PENDING);
        job.setStage(IngestStage.QUEUED);
        job.setErrorMessage("Waiting for ingest queue recovery: " + reason);
        ingestJobRepository.save(job);
    }

    private int maxRecoveryAttempts() {
        int configured = redisQueueProperties.getMaxRecoveryAttempts();
        return configured > 0 ? configured : 3;
    }

    private int safeRetryCount(IngestJob job) {
        return job.getRetryCount() == null ? 0 : job.getRetryCount();
    }

    private long safeCallbackSequence(IngestJob job) {
        return job.getCallbackSequence() == null ? 0L : job.getCallbackSequence();
    }

    private UUID ensureExecutionId(IngestJob job) {
        if (job.getExecutionId() == null) {
            job.setExecutionId(UUID.randomUUID());
        }
        return job.getExecutionId();
    }

    private IngestJob findJobForUpdate(UUID jobId) {
        Optional<IngestJob> locked = ingestJobRepository.findByIdForUpdate(jobId);
        if (locked != null && locked.isPresent()) {
            return locked.get();
        }
        return ingestJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Ingest job not found: " + jobId));
    }

    private void rotateExecution(IngestJob job) {
        job.setExecutionId(UUID.randomUUID());
        job.setCallbackSequence(0L);
        job.setClaimedBy(null);
        job.setLeaseExpiresAt(null);
        job.setStartedAt(null);
        job.setCompletedAt(null);
        job.setCancelRequestedAt(null);
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return UUID.fromString(value);
    }

    private IngestJobStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return IngestJobStatus.valueOf(value.toUpperCase());
    }

    private boolean isTerminal(IngestJobStatus status) {
        return status == IngestJobStatus.COMPLETED
                || status == IngestJobStatus.FAILED
                || status == IngestJobStatus.DEAD_LETTER
                || status == IngestJobStatus.CANCELLED;
    }

    private boolean isLegalCallbackTransition(IngestJobStatus current, IngestJobStatus next) {
        if (current == IngestJobStatus.PENDING) {
            return next == IngestJobStatus.PROCESSING;
        }
        if (current == IngestJobStatus.PROCESSING) {
            return next == IngestJobStatus.PROCESSING
                    || next == IngestJobStatus.COMPLETED
                    || next == IngestJobStatus.FAILED
                    || next == IngestJobStatus.CANCELLED;
        }
        return current == IngestJobStatus.CANCEL_REQUESTED
                && next == IngestJobStatus.CANCELLED;
    }

    private String errorReason(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }

    private IngestJobResponse toResponse(IngestJob job) {
        Document doc = documentRepository.findById(job.getDocId()).orElse(null);
        return toResponse(job, doc);
    }

    public IngestJobResponse toResponse(IngestJob job, Document doc) {
        return IngestJobResponse.builder()
                .id(job.getId())
                .executionId(job.getExecutionId())
                .kbId(job.getKbId())
                .docId(job.getDocId())
                .documentFileName(doc == null ? null : doc.getFileName())
                .documentStatus(doc == null ? null : doc.getStatus())
                .status(job.getStatus())
                .stage(job.getStage())
                .retryCount(job.getRetryCount())
                .callbackSequence(job.getCallbackSequence())
                .errorMessage(job.getErrorMessage())
                .cancelRequestedAt(job.getCancelRequestedAt())
                .diagnosis(diagnose(job, doc))
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }

    private IngestDiagnosisResponse diagnose(IngestJob job, Document doc) {
        Instant now = Instant.now();
        long ageSeconds = elapsedSeconds(job.getCreatedAt(), now);
        long lastUpdatedSeconds = elapsedSeconds(job.getUpdatedAt(), now);
        boolean running = job.getStatus() == IngestJobStatus.PENDING || job.getStatus() == IngestJobStatus.PROCESSING;
        boolean stalled = running && lastUpdatedSeconds >= STALLED_JOB_SECONDS;
        if (stalled) {
            return IngestDiagnosisResponse.builder()
                    .severity("warning")
                    .summary("摄入任务长时间无进展")
                    .nextAction(stalledNextAction(job))
                    .retryable(false)
                    .stalled(true)
                    .ageSeconds(ageSeconds)
                    .lastUpdatedSeconds(lastUpdatedSeconds)
                    .build();
        }
        if (job.getStatus() == IngestJobStatus.COMPLETED) {
            return IngestDiagnosisResponse.builder()
                    .severity("info")
                    .summary("文档索引已完成")
                    .nextAction("可以开始提问，或在 RAG 评估页检查检索命中质量。")
                    .retryable(false)
                    .stalled(false)
                    .ageSeconds(ageSeconds)
                    .lastUpdatedSeconds(lastUpdatedSeconds)
                    .build();
        }
        if (job.getStatus() == IngestJobStatus.DEAD_LETTER) {
            return IngestDiagnosisResponse.builder()
                    .severity("error")
                    .summary("摄入任务已进入死信：" + failureReason(job, doc))
                    .nextAction("检查队列、Worker、对象存储和 Embedding 配置后，再手动重试摄入。")
                    .retryable(true)
                    .stalled(false)
                    .ageSeconds(ageSeconds)
                    .lastUpdatedSeconds(lastUpdatedSeconds)
                    .build();
        }
        if (job.getStatus() == IngestJobStatus.FAILED || (doc != null && doc.getStatus() == DocumentStatus.FAILED)) {
            return IngestDiagnosisResponse.builder()
                    .severity("error")
                    .summary("摄入失败：" + failureReason(job, doc))
                    .nextAction("检查文档格式、Embedding 配置和 Worker 日志，必要时手动重试摄入。")
                    .retryable(canRetry(job))
                    .stalled(false)
                    .ageSeconds(ageSeconds)
                    .lastUpdatedSeconds(lastUpdatedSeconds)
                    .build();
        }
        if (job.getStatus() == IngestJobStatus.PROCESSING) {
            return IngestDiagnosisResponse.builder()
                    .severity("info")
                    .summary("文档正在摄入和索引")
                    .nextAction("等待 Worker 完成当前阶段；如长时间无进展再检查 Worker 日志。")
                    .retryable(false)
                    .stalled(false)
                    .ageSeconds(ageSeconds)
                    .lastUpdatedSeconds(lastUpdatedSeconds)
                    .build();
        }
        return IngestDiagnosisResponse.builder()
                .severity("info")
                .summary("文档等待摄入队列处理")
                .nextAction("等待队列调度；如果持续停留在队列中，请检查 Redis 队列和 Worker。")
                .retryable(false)
                .stalled(false)
                .ageSeconds(ageSeconds)
                .lastUpdatedSeconds(lastUpdatedSeconds)
                .build();
    }

    private String stalledNextAction(IngestJob job) {
        if (job.getStage() == IngestStage.QUEUED) {
            return "检查 Redis 队列积压和 Worker 是否在线，确认后等待恢复任务重新投递。";
        }
        return "检查 Worker 当前阶段日志、Embedding 配置和外部向量服务状态。";
    }

    private boolean canRetry(IngestJob job) {
        return job.getStatus() == IngestJobStatus.DEAD_LETTER || safeRetryCount(job) < maxRecoveryAttempts();
    }

    private String failureReason(IngestJob job, Document doc) {
        String reason = firstText(job.getErrorMessage(), doc == null ? null : doc.getErrorMessage());
        return reason == null ? "未提供错误信息" : reason;
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private long elapsedSeconds(Instant start, Instant end) {
        if (start == null || end == null) {
            return 0;
        }
        return Math.max(0, Duration.between(start, end).getSeconds());
    }
}
