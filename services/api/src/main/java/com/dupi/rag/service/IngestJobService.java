package com.dupi.rag.service;

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
    private final VectorCleanupTaskService vectorCleanupTaskService;
    private final AuditLogService auditLogService;
    private final ProfileIndexStateService profileIndexStateService;

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
    public void handleStatusUpdate(IngestStatusUpdate update) {
        UUID jobId = UUID.fromString(update.getJobId());
        UUID docId = UUID.fromString(update.getDocId());
        if (documentTombstoneService.isDeleted(docId)) {
            log.info("Ignored ingest status update for tombstoned document {}, job {}", docId, jobId);
            return;
        }
        boolean completedUpdate = "COMPLETED".equalsIgnoreCase(update.getStatus());
        IngestJob job = ingestJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Ingest job not found: " + jobId));

        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        if (!job.getDocId().equals(doc.getId()) || !job.getKbId().equals(doc.getKbId())) {
            throw new IllegalArgumentException("Ingest status update does not match job/document");
        }
        boolean wasV2Ready = completedUpdate && profileIndexStateService.isV2Ready(doc.getKbId());
        boolean wasV2Activated = completedUpdate && profileIndexStateService.isV2Activated(doc.getKbId());

        if (update.getStage() != null) {
            job.setStage(IngestStage.valueOf(update.getStage().toUpperCase()));
        }
        if (update.getStatus() != null) {
            job.setStatus(IngestJobStatus.valueOf(update.getStatus().toUpperCase()));
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

        if (completedUpdate) {
            doc.setStatus(DocumentStatus.COMPLETED);
            doc.setErrorMessage(null);
            if (update.getIndexSchemaVersion() != null) {
                doc.setIndexSchemaVersion(update.getIndexSchemaVersion());
            }
            job.setStage(IngestStage.COMPLETED);
        } else if ("FAILED".equalsIgnoreCase(update.getStatus())) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setIndexSchemaVersion(1);
            doc.setErrorMessage(update.getErrorMessage());
            job.setStage(IngestStage.FAILED);
        } else {
            doc.setStatus(DocumentStatus.PROCESSING);
            doc.setIndexSchemaVersion(1);
        }

        documentRepository.save(doc);
        ingestJobRepository.save(job);
        if (completedUpdate) {
            KnowledgeBase kb = knowledgeBaseService.findSystemOrThrow(doc.getKbId());
            profileIndexStateService.bumpRevision(kb);
            if (!wasV2Activated && !wasV2Ready && profileIndexStateService.isV2Ready(doc.getKbId())) {
                profileIndexStateService.activateV2Index(kb);
                vectorCleanupTaskService.enqueueLegacyKnowledgeBase(doc.getKbId());
            }
        }
    }

    @Transactional
    public IngestJobResponse retry(UUID jobId) {
        IngestJob job = ingestJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Ingest job not found: " + jobId));
        KnowledgeBase kb = knowledgeBaseService.findSystemOrThrow(job.getKbId());
        return retryJob(job, kb);
    }

    @Transactional
    public IngestJobResponse retryForKnowledgeBase(UUID kbId, UUID jobId) {
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        IngestJob job = ingestJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Ingest job not found: " + jobId));
        if (!job.getKbId().equals(kbId)) {
            throw new IllegalArgumentException("Ingest job does not belong to knowledge base: " + kbId);
        }
        return retryJob(job, kb);
    }

    private IngestJobResponse retryJob(IngestJob job, KnowledgeBase kb) {
        if (job.getStatus() != IngestJobStatus.DEAD_LETTER && job.getRetryCount() >= maxRecoveryAttempts()) {
            throw new IllegalStateException("Max retries exceeded");
        }
        job.setRetryCount(job.getStatus() == IngestJobStatus.DEAD_LETTER ? 0 : safeRetryCount(job) + 1);
        job.setStatus(IngestJobStatus.PENDING);
        job.setStage(IngestStage.QUEUED);
        job.setErrorMessage(null);
        ingestJobRepository.save(job);

        Document doc = documentRepository.findById(job.getDocId())
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        doc.setStatus(DocumentStatus.PENDING);
        doc.setIndexSchemaVersion(1);
        doc.setErrorMessage(null);
        documentRepository.save(doc);
        profileIndexStateService.bumpRevision(kb);
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
        List<Document> documents = documentRepository.findByKbIdOrderByCreatedAtDesc(kbId);
        profileIndexStateService.resetForReindex(kb, documents);
        vectorCleanupTaskService.completePendingProfileKnowledgeBase(kbId);
        List<IngestJobResponse> responses = documents.stream()
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
        int recovered = 0;
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
                .kbId(job.getKbId())
                .docId(job.getDocId())
                .documentFileName(doc == null ? null : doc.getFileName())
                .documentStatus(doc == null ? null : doc.getStatus())
                .status(job.getStatus())
                .stage(job.getStage())
                .retryCount(job.getRetryCount())
                .errorMessage(job.getErrorMessage())
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
