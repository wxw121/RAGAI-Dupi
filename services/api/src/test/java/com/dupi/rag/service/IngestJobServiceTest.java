package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.config.LlmProperties;
import com.dupi.rag.config.RedisQueueProperties;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.dto.IngestStatusUpdate;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestJobServiceTest {

    @Mock IngestJobRepository ingestJobRepository;
    @Mock DocumentRepository documentRepository;
    @Mock ChunkRepository chunkRepository;
    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock IngestJobProducer ingestJobProducer;
    @Mock IngestOutboxService ingestOutboxService;
    @Mock DocumentTombstoneService documentTombstoneService;
    @Mock MilvusVectorService milvusVectorService;
    @Mock VectorCleanupTaskService vectorCleanupTaskService;
    @Mock AuditLogService auditLogService;

    IngestJobService service() {
        RedisQueueProperties redisQueueProperties = new RedisQueueProperties();
        redisQueueProperties.setMaxRecoveryAttempts(3);
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.getEmbedding().setModel("current-model");
        llmProperties.getEmbedding().setDimension(1024);
        return new IngestJobService(
                ingestJobRepository,
                documentRepository,
                chunkRepository,
                knowledgeBaseService,
                ingestJobProducer,
                ingestOutboxService,
                documentTombstoneService,
                redisQueueProperties,
                llmProperties,
                milvusVectorService,
                vectorCleanupTaskService,
                auditLogService
        );
    }

    @Test
    void handleCompletedUpdateReplacesChunksAndMarksDocumentComplete() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        Document doc = doc(kbId, docId);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        IngestStatusUpdate update = IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .status("completed")
                .stage("indexing")
                .chunks(List.of(new IngestStatusUpdate.ChunkPayload(
                        chunkId.toString(), 0, "content", 3, Map.of("heading", "H"), "milvus-1")))
                .build();

        service().handleStatusUpdate(update);

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.COMPLETED);
        assertThat(job.getStage()).isEqualTo(IngestStage.COMPLETED);
        verify(chunkRepository).deleteByDocId(docId);
        verify(chunkRepository).save(argThat(c -> c.getId().equals(chunkId) && c.getMetadata().get("heading").equals("H")));
    }

    @Test
    void handleFailedUpdateStoresErrorOnDocumentAndJob() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        Document doc = doc(kbId, docId);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .status("failed")
                .errorMessage("bad pdf")
                .build());

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(doc.getErrorMessage()).isEqualTo("bad pdf");
        assertThat(job.getStage()).isEqualTo(IngestStage.FAILED);
    }

    @Test
    void handleProcessingUpdateRejectsMismatchedDocument() {
        UUID kbId = UUID.randomUUID();
        UUID jobDocId = UUID.randomUUID();
        UUID payloadDocId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job(kbId, jobDocId, jobId)));
        when(documentRepository.findById(payloadDocId)).thenReturn(Optional.of(doc(kbId, payloadDocId)));

        assertThatThrownBy(() -> service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(payloadDocId.toString())
                .status("processing")
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void retryResetsJobAndEnqueuesAgainUntilRetryLimit() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setRetryCount(2);
        Document doc = doc(kbId, docId);
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(kb);

        var response = service().retry(jobId);

        assertThat(response.getRetryCount()).isEqualTo(3);
        assertThat(response.getStatus()).isEqualTo(IngestJobStatus.PENDING);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        verify(ingestOutboxService).record(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());

        job.setRetryCount(3);
        assertThatThrownBy(() -> service().retry(jobId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retryForKnowledgeBaseValidatesTenantScopedKbAndJobOwnership() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        Document doc = doc(kbId, docId);
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        var response = service().retryForKnowledgeBase(kbId, jobId);

        assertThat(response.getId()).isEqualTo(jobId);
        assertThat(response.getStatus()).isEqualTo(IngestJobStatus.PENDING);
        verify(knowledgeBaseService).findOrThrow(kbId);
        verify(knowledgeBaseService, never()).findSystemOrThrow(kbId);
        verify(ingestOutboxService).record(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
        verify(auditLogService).recordSuccess(
                eq("INGEST_JOB_RETRY"),
                eq("INGEST_JOB"),
                eq(jobId),
                contains(kbId.toString())
        );

        UUID otherKbId = UUID.randomUUID();
        IngestJob mismatchedJob = job(otherKbId, docId, UUID.randomUUID());
        when(ingestJobRepository.findById(mismatchedJob.getId())).thenReturn(Optional.of(mismatchedJob));
        assertThatThrownBy(() -> service().retryForKnowledgeBase(kbId, mismatchedJob.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void recoverQueuedJobsReenqueuesPersistedJobsAndMarksDocumentsProcessing() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        Document doc = doc(kbId, docId);
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        when(ingestJobRepository.findTop20ByStatusAndStageOrderByCreatedAtAsc(
                IngestJobStatus.PENDING, IngestStage.QUEUED)).thenReturn(List.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(kb);

        int recovered = service().recoverQueuedJobs();

        assertThat(recovered).isEqualTo(1);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        verify(ingestJobProducer).enqueue(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());
        verify(documentRepository).save(doc);
    }

    @Test
    void recoverQueuedJobsSkipsMissingOrAlreadyProcessingDocuments() {
        UUID missingDocId = UUID.randomUUID();
        UUID processingDocId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        IngestJob missingDocJob = job(kbId, missingDocId, UUID.randomUUID());
        IngestJob processingDocJob = job(kbId, processingDocId, UUID.randomUUID());
        Document processingDoc = doc(kbId, processingDocId);
        processingDoc.setStatus(DocumentStatus.PROCESSING);
        when(ingestJobRepository.findTop20ByStatusAndStageOrderByCreatedAtAsc(
                IngestJobStatus.PENDING, IngestStage.QUEUED)).thenReturn(List.of(missingDocJob, processingDocJob));
        when(documentRepository.findById(missingDocId)).thenReturn(Optional.empty());
        when(documentRepository.findById(processingDocId)).thenReturn(Optional.of(processingDoc));

        int recovered = service().recoverQueuedJobs();

        assertThat(recovered).isZero();
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
        verify(ingestJobRepository, never()).save(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void recoverQueuedJobsOnScheduleDelegatesToRecoveryQuery() {
        when(ingestJobRepository.findTop20ByStatusAndStageOrderByCreatedAtAsc(
                IngestJobStatus.PENDING, IngestStage.QUEUED)).thenReturn(List.of());

        service().recoverQueuedJobsOnSchedule();

        verify(ingestJobRepository).findTop20ByStatusAndStageOrderByCreatedAtAsc(
                IngestJobStatus.PENDING, IngestStage.QUEUED);
    }

    @Test
    void recoverQueuedJobsLeavesJobQueuedWhenRedisIsStillUnavailable() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        Document doc = doc(kbId, docId);
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        when(ingestJobRepository.findTop20ByStatusAndStageOrderByCreatedAtAsc(
                IngestJobStatus.PENDING, IngestStage.QUEUED)).thenReturn(List.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(kb);
        doThrow(new IllegalStateException("redis down"))
                .when(ingestJobProducer).enqueue(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());

        int recovered = service().recoverQueuedJobs();

        assertThat(recovered).isZero();
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.PENDING);
        assertThat(job.getStage()).isEqualTo(IngestStage.QUEUED);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        verify(documentRepository, never()).save(doc);
    }

    @Test
    void recoverQueuedJobsMovesJobToDeadLetterAfterConfiguredAttempts() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setRetryCount(2);
        Document doc = doc(kbId, docId);
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        when(ingestJobRepository.findTop20ByStatusAndStageOrderByCreatedAtAsc(
                IngestJobStatus.PENDING, IngestStage.QUEUED)).thenReturn(List.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(kb);
        doThrow(new IllegalStateException("redis down"))
                .when(ingestJobProducer).enqueue(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());

        int recovered = service().recoverQueuedJobs();

        assertThat(recovered).isZero();
        assertThat(job.getRetryCount()).isEqualTo(3);
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.DEAD_LETTER);
        assertThat(job.getStage()).isEqualTo(IngestStage.DEAD_LETTER);
        assertThat(job.getErrorMessage()).contains("redis down");
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(doc.getErrorMessage()).contains("dead-letter");
        verify(ingestJobRepository).save(job);
        verify(documentRepository).save(doc);
    }

    @Test
    void reindexKnowledgeBaseClearsChunksAndRequeuesAllDocumentsWithCurrentEmbeddingConfig() {
        UUID kbId = UUID.randomUUID();
        UUID firstDocId = UUID.randomUUID();
        UUID secondDocId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .embeddingModel("old-model")
                .embeddingDimension(128)
                .build();
        Document first = doc(kbId, firstDocId);
        Document second = doc(kbId, secondDocId);
        second.setObjectKey("kb/b.md");
        second.setFileName("b.md");
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(first, second));

        var responses = service().reindexKnowledgeBase(kbId, "current-model", 1024);

        assertThat(responses).hasSize(2);
        assertThat(kb.getEmbeddingModel()).isEqualTo("current-model");
        assertThat(kb.getEmbeddingDimension()).isEqualTo(1024);
        assertThat(first.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(first.getErrorMessage()).isNull();
        assertThat(second.getStatus()).isEqualTo(DocumentStatus.PENDING);
        verify(chunkRepository).deleteByKbId(kbId);
        verify(ingestJobRepository, times(2)).save(any(IngestJob.class));
        verify(ingestOutboxService).record(any(IngestJob.class), eq(kb), eq(first.getObjectKey()), eq(first.getFileName()), eq(first.getMimeType()));
        verify(ingestOutboxService).record(any(IngestJob.class), eq(kb), eq(second.getObjectKey()), eq(second.getFileName()), eq(second.getMimeType()));
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
        verify(auditLogService).recordSuccess(
                eq("KNOWLEDGE_BASE_REINDEX"),
                eq("KNOWLEDGE_BASE"),
                eq(kbId),
                contains("2 document")
        );
    }

    @Test
    void reindexKnowledgeBaseDefaultOverloadUsesConfiguredEmbeddingAndKeepsRecoveryStateWhenQueueFails() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .embeddingModel("old-model")
                .embeddingDimension(128)
                .build();
        Document doc = doc(kbId, docId);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(ingestOutboxService).record(any(IngestJob.class), eq(kb), eq(doc.getObjectKey()), eq(doc.getFileName()), eq(doc.getMimeType()));

        assertThatThrownBy(() -> service().reindexKnowledgeBase(kbId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis unavailable");
        verify(vectorCleanupTaskService).enqueueKnowledgeBase(kbId);
        verify(milvusVectorService).deleteByKbId(kbId);
        verify(documentRepository, never()).save(doc);
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test
    void reindexKnowledgeBaseContinuesWhenImmediateVectorDeleteFails() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        Document doc = doc(kbId, docId);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc));
        doThrow(new IllegalStateException("milvus unavailable")).when(milvusVectorService).deleteByKbId(kbId);

        var responses = service().reindexKnowledgeBase(kbId, "model", 256);

        assertThat(responses).hasSize(1);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        verify(chunkRepository).deleteByKbId(kbId);
        verify(ingestOutboxService).record(any(IngestJob.class), eq(kb), eq(doc.getObjectKey()), eq(doc.getFileName()), eq(doc.getMimeType()));
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test
    void handleStatusUpdateIgnoresTombstonedDocumentWithoutRestoringChunks() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(documentTombstoneService.isDeleted(docId)).thenReturn(true);

        service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .status("completed")
                .stage("completed")
                .chunks(List.of(new IngestStatusUpdate.ChunkPayload(
                        UUID.randomUUID().toString(), 0, "stale", 1, Map.of(), "milvus-1")))
                .build());

        verify(ingestJobRepository, never()).findById(any());
        verify(documentRepository, never()).findById(any());
        verify(chunkRepository, never()).deleteByDocId(any());
        verify(chunkRepository, never()).save(any());
    }

    @Test
    void listAndGetLatestMapJobsToResponses() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, UUID.randomUUID());
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId)).thenReturn(Optional.of(job));
        when(ingestJobRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(job));

        IngestJobService service = service();

        assertThat(service.getLatestByDoc(docId).getDocId()).isEqualTo(docId);
        assertThat(service.listByKb(kbId)).hasSize(1);
        verify(knowledgeBaseService).findOrThrow(kbId);
    }

    private static IngestJob job(UUID kbId, UUID docId, UUID jobId) {
        return IngestJob.builder()
                .id(jobId)
                .kbId(kbId)
                .docId(docId)
                .status(IngestJobStatus.PENDING)
                .stage(IngestStage.QUEUED)
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private static Document doc(UUID kbId, UUID docId) {
        return Document.builder()
                .id(docId)
                .kbId(kbId)
                .fileName("a.md")
                .objectKey("kb/a.md")
                .mimeType("text/markdown")
                .fileSize(1L)
                .status(DocumentStatus.PENDING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
