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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    @Mock IngestFailureNotificationService failureNotificationService;
    @Mock ProfileIndexStateService profileIndexStateService;

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
                auditLogService,
                failureNotificationService,
                profileIndexStateService
        );
    }

    @Test
    void handleCompletedUpdateReplacesChunksAndMarksDocumentComplete() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setStatus(IngestJobStatus.PROCESSING);
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
        job.setStatus(IngestJobStatus.PROCESSING);
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
        verify(failureNotificationService).recordTerminalFailure(job, doc);
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
    void handleStatusUpdateLocksJobBeforeCheckingCallbackSequence() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setStatus(IngestJobStatus.PROCESSING);
        Document doc = doc(kbId, docId);
        when(ingestJobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .status("processing")
                .stage("parsing")
                .build());

        verify(ingestJobRepository).findByIdForUpdate(jobId);
        verify(ingestJobRepository, never()).findById(jobId);
    }

    @Test
    void retryResetsJobAndEnqueuesAgainUntilRetryLimit() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setStatus(IngestJobStatus.FAILED);
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
    void retryRotatesExecutionIdAndResetsCallbackSequence() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID firstExecution = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setStatus(IngestJobStatus.FAILED);
        job.setExecutionId(firstExecution);
        job.setCallbackSequence(7L);
        job.setClaimedBy("worker-a");
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        job.setCancelRequestedAt(Instant.now());
        Document doc = doc(kbId, docId);
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(kb);

        var response = service().retry(jobId);

        assertThat(response.getStatus()).isEqualTo(IngestJobStatus.PENDING);
        assertThat(job.getExecutionId()).isNotEqualTo(firstExecution);
        assertThat(job.getCallbackSequence()).isZero();
        assertThat(job.getClaimedBy()).isNull();
        assertThat(job.getLeaseExpiresAt()).isNull();
        assertThat(job.getCancelRequestedAt()).isNull();
    }

    @Test
    void claimValidExecutionMovesQueuedJobToProcessingWithLease() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setExecutionId(executionId);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        var response = service().claim(jobId, executionId, "worker-a", Duration.ofSeconds(45));

        assertThat(response.getStatus()).isEqualTo(IngestJobStatus.PROCESSING);
        assertThat(job.getStage()).isEqualTo(IngestStage.PARSING);
        assertThat(job.getClaimedBy()).isEqualTo("worker-a");
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(job.getLeaseExpiresAt()).isAfter(Instant.now());
        verify(ingestJobRepository).save(job);

        assertThatThrownBy(() -> service().claim(jobId, UUID.randomUUID(), "worker-b", Duration.ofSeconds(45)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution");
    }

    @Test
    void claimRejectsGeneratedExecutionMismatchAndNonQueuedJob() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setExecutionId(null);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service().claim(
                jobId, UUID.randomUUID(), "worker-a", Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution");
        assertThat(job.getExecutionId()).isNotNull();

        job.setStatus(IngestJobStatus.PROCESSING);
        job.setStage(IngestStage.PARSING);
        assertThatThrownBy(() -> service().claim(
                jobId, job.getExecutionId(), "worker-a", Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not claimable");
    }

    @Test
    void refreshLeaseUpdatesRunningJobAndRejectsMismatchOrTerminalState() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setExecutionId(executionId);
        job.setStatus(IngestJobStatus.PROCESSING);
        job.setStage(IngestStage.EMBEDDING);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc(kbId, docId)));

        var response = service().refreshLease(jobId, executionId, "worker-b", null);

        assertThat(response.getStatus()).isEqualTo(IngestJobStatus.PROCESSING);
        assertThat(job.getClaimedBy()).isEqualTo("worker-b");
        assertThat(job.getLeaseExpiresAt()).isAfter(Instant.now());
        verify(ingestJobRepository).save(job);

        assertThatThrownBy(() -> service().refreshLease(
                jobId, UUID.randomUUID(), "worker-c", Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution");

        assertThatThrownBy(() -> service().refreshLease(
                jobId, executionId, "worker-c", Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claimed by another worker");

        job.setStatus(IngestJobStatus.COMPLETED);
        assertThatThrownBy(() -> service().refreshLease(
                jobId, executionId, "worker-b", Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");

        job.setStatus(IngestJobStatus.CANCEL_REQUESTED);
        assertThat(service().refreshLease(
                jobId, executionId, "worker-b", Duration.ofSeconds(10)).getStatus())
                .isEqualTo(IngestJobStatus.CANCEL_REQUESTED);
    }

    @Test
    void cancellationCheckStopsCancelledTerminalAndStaleExecutions() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setExecutionId(executionId);
        job.setStatus(IngestJobStatus.PROCESSING);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertThat(service().isCancellationRequested(jobId, executionId)).isFalse();
        assertThat(service().isCancellationRequested(jobId, UUID.randomUUID())).isTrue();

        job.setStatus(IngestJobStatus.CANCEL_REQUESTED);
        assertThat(service().isCancellationRequested(jobId, executionId)).isTrue();

        job.setStatus(IngestJobStatus.COMPLETED);
        assertThat(service().isCancellationRequested(jobId, executionId)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executionStateReportsCurrentTerminalAndExpiredLeaseSignals() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setExecutionId(executionId);
        job.setStatus(IngestJobStatus.PROCESSING);
        job.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        AtomicReference<Map<String, Object>> result = new AtomicReference<>();

        assertThatCode(() -> {
            var method = IngestJobService.class.getMethod("getExecutionState", UUID.class, UUID.class);
            result.set((Map<String, Object>) method.invoke(service(), jobId, executionId));
        }).doesNotThrowAnyException();

        assertThat(result.get())
                .containsEntry("status", IngestJobStatus.PROCESSING)
                .containsEntry("executionCurrent", true)
                .containsEntry("terminal", false)
                .containsEntry("leaseExpired", true);
    }

    @Test
    void executionStateMarksOnlyCurrentQueuedExecutionEligibleForRequeue() {
        UUID jobId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        IngestJob job = job(UUID.randomUUID(), UUID.randomUUID(), jobId);
        job.setExecutionId(executionId);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        Map<String, Object> current = service().getExecutionState(jobId, executionId);
        Map<String, Object> stale = service().getExecutionState(jobId, UUID.randomUUID());

        assertThat(current).containsEntry("requeueEligible", true);
        assertThat(stale).containsEntry("requeueEligible", false);
    }

    @Test
    void cancelQueuedJobTerminatesAndCancelsPendingOutbox() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        Document doc = doc(kbId, docId);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        var response = service().cancelForKnowledgeBase(kbId, jobId);

        assertThat(response.getStatus()).isEqualTo(IngestJobStatus.CANCELLED);
        assertThat(job.getStage()).isEqualTo(IngestStage.CANCELLED);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.CANCELLED);
        verify(ingestOutboxService).cancelPendingForJob(jobId, "ingest cancelled by user");
        verify(failureNotificationService, never()).recordTerminalFailure(any(), any());
        verify(ingestJobRepository).save(job);
        verify(documentRepository).save(doc);
    }

    @Test
    void cancelRunningJobPersistsCancelRequestedState() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setStatus(IngestJobStatus.PROCESSING);
        job.setStage(IngestStage.EMBEDDING);
        Document doc = doc(kbId, docId);
        doc.setStatus(DocumentStatus.PROCESSING);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        var response = service().cancelForKnowledgeBase(kbId, jobId);

        assertThat(response.getStatus()).isEqualTo(IngestJobStatus.CANCEL_REQUESTED);
        assertThat(job.getStage()).isEqualTo(IngestStage.EMBEDDING);
        assertThat(job.getCancelRequestedAt()).isNotNull();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
    }

    @Test
    void cancelForKnowledgeBaseLocksJobBeforeTransition() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        Document doc = doc(kbId, docId);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(ingestJobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        service().cancelForKnowledgeBase(kbId, jobId);

        verify(ingestJobRepository).findByIdForUpdate(jobId);
        verify(ingestJobRepository, never()).findById(jobId);
    }

    @Test
    void retryLocksJobAndRejectsNonTerminalExecution() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setStatus(IngestJobStatus.PROCESSING);
        when(ingestJobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());

        assertThatThrownBy(() -> service().retry(jobId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed or dead-letter");

        verify(ingestJobRepository).findByIdForUpdate(jobId);
        verify(ingestJobRepository, never()).findById(jobId);
        verify(ingestOutboxService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void handleStatusUpdateIgnoresStaleExecutionAndDuplicateSequence() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setExecutionId(executionId);
        job.setStatus(IngestJobStatus.PROCESSING);
        job.setCallbackSequence(5L);
        Document doc = doc(kbId, docId);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        var staleExecution = service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .executionId(UUID.randomUUID().toString())
                .sequence(6L)
                .status("completed")
                .build());
        var duplicate = service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .executionId(executionId.toString())
                .sequence(5L)
                .status("completed")
                .build());

        assertThat(staleExecution.isIgnored()).isTrue();
        assertThat(duplicate.isIgnored()).isTrue();
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.PROCESSING);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        verify(chunkRepository, never()).deleteByDocId(any());
    }

    @Test
    void handleStatusUpdateIgnoresCallbacksMissingExecutionOrSequenceForExecutedJobs() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setExecutionId(executionId);
        job.setStatus(IngestJobStatus.PROCESSING);
        Document doc = doc(kbId, docId);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        var missingExecution = service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .sequence(1L)
                .status("completed")
                .build());
        var missingSequence = service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .executionId(executionId.toString())
                .status("completed")
                .build());

        assertThat(missingExecution.isIgnored()).isTrue();
        assertThat(missingExecution.getReason()).isEqualTo("missing_execution");
        assertThat(missingSequence.isIgnored()).isTrue();
        assertThat(missingSequence.getReason()).isEqualTo("missing_sequence");
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.PROCESSING);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
    }

    @Test
    void handleStatusUpdateDoesNotResurrectTerminalJob() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setExecutionId(executionId);
        job.setStatus(IngestJobStatus.COMPLETED);
        job.setStage(IngestStage.COMPLETED);
        Document doc = doc(kbId, docId);
        doc.setStatus(DocumentStatus.COMPLETED);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        var ignored = service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .executionId(executionId.toString())
                .sequence(1L)
                .status("failed")
                .errorMessage("late failure")
                .build());

        assertThat(ignored.isIgnored()).isTrue();
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.COMPLETED);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(doc.getErrorMessage()).isNull();
    }

    @Test
    void handleStatusUpdateRejectsIllegalForwardTransitions() {
        UUID kbId = UUID.randomUUID();
        UUID pendingDocId = UUID.randomUUID();
        UUID pendingJobId = UUID.randomUUID();
        IngestJob pending = job(kbId, pendingDocId, pendingJobId);
        Document pendingDoc = doc(kbId, pendingDocId);
        when(ingestJobRepository.findByIdForUpdate(pendingJobId)).thenReturn(Optional.of(pending));
        when(documentRepository.findById(pendingDocId)).thenReturn(Optional.of(pendingDoc));

        var unclaimedTerminal = service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(pendingJobId.toString())
                .docId(pendingDocId.toString())
                .status("completed")
                .build());

        UUID processingDocId = UUID.randomUUID();
        UUID processingJobId = UUID.randomUUID();
        IngestJob processing = job(kbId, processingDocId, processingJobId);
        processing.setStatus(IngestJobStatus.PROCESSING);
        Document processingDoc = doc(kbId, processingDocId);
        processingDoc.setStatus(DocumentStatus.PROCESSING);
        when(ingestJobRepository.findByIdForUpdate(processingJobId)).thenReturn(Optional.of(processing));
        when(documentRepository.findById(processingDocId)).thenReturn(Optional.of(processingDoc));

        var backwards = service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(processingJobId.toString())
                .docId(processingDocId.toString())
                .status("pending")
                .build());

        assertThat(unclaimedTerminal.isIgnored()).isTrue();
        assertThat(unclaimedTerminal.getReason()).isEqualTo("illegal_transition");
        assertThat(pending.getStatus()).isEqualTo(IngestJobStatus.PENDING);
        assertThat(pendingDoc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(backwards.isIgnored()).isTrue();
        assertThat(backwards.getReason()).isEqualTo("illegal_transition");
        assertThat(processing.getStatus()).isEqualTo(IngestJobStatus.PROCESSING);
        verify(ingestJobRepository, never()).save(pending);
        verify(ingestJobRepository, never()).save(processing);
    }

    @Test
    void handleStatusUpdateKeepsTerminalJobImmutableForRepeatedTerminalCallback() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setExecutionId(executionId);
        job.setCallbackSequence(4L);
        job.setStatus(IngestJobStatus.COMPLETED);
        job.setStage(IngestStage.COMPLETED);
        Document doc = doc(kbId, docId);
        doc.setStatus(DocumentStatus.COMPLETED);
        when(ingestJobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        var ignored = service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .executionId(executionId.toString())
                .sequence(5L)
                .status("completed")
                .stage("completed")
                .chunks(List.of(new IngestStatusUpdate.ChunkPayload(
                        UUID.randomUUID().toString(), 0, "duplicate", 1, Map.of(), "milvus-1")))
                .build());

        assertThat(ignored.isIgnored()).isTrue();
        assertThat(ignored.getReason()).isEqualTo("terminal_state");
        assertThat(job.getCallbackSequence()).isEqualTo(4L);
        verify(chunkRepository, never()).deleteByDocId(docId);
        verify(ingestJobRepository, never()).save(job);
        verify(documentRepository, never()).save(doc);
    }

    @Test
    void handleStatusUpdateTracksSequenceAndAllowsOnlyCancelledAfterCancelRequest() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setExecutionId(executionId);
        job.setStatus(IngestJobStatus.PROCESSING);
        job.setCallbackSequence(0L);
        Document doc = doc(kbId, docId);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        var processing = service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .executionId(executionId.toString())
                .sequence(1L)
                .status("processing")
                .build());

        assertThat(processing.isIgnored()).isFalse();
        assertThat(job.getCallbackSequence()).isEqualTo(1L);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSING);

        job.setStatus(IngestJobStatus.CANCEL_REQUESTED);
        var rejected = service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .executionId(executionId.toString())
                .sequence(2L)
                .status("processing")
                .build());
        assertThat(rejected.isIgnored()).isTrue();
        assertThat(rejected.getReason()).isEqualTo("cancel_requested");

        var cancelled = service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .executionId(executionId.toString())
                .sequence(2L)
                .status("cancelled")
                .build());
        assertThat(cancelled.isIgnored()).isFalse();
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.CANCELLED);
        assertThat(job.getStage()).isEqualTo(IngestStage.CANCELLED);
        assertThat(job.getCompletedAt()).isNotNull();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.CANCELLED);
    }

    @Test
    void cancelForKnowledgeBaseRejectsWrongOwnerAndKeepsExistingRequest() {
        UUID kbId = UUID.randomUUID();
        UUID otherKbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID wrongJobId = UUID.randomUUID();
        IngestJob wrongJob = job(otherKbId, docId, wrongJobId);
        when(ingestJobRepository.findById(wrongJobId)).thenReturn(Optional.of(wrongJob));

        assertThatThrownBy(() -> service().cancelForKnowledgeBase(kbId, wrongJobId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");

        UUID requestedJobId = UUID.randomUUID();
        IngestJob requested = job(kbId, docId, requestedJobId);
        requested.setStatus(IngestJobStatus.CANCEL_REQUESTED);
        Document doc = doc(kbId, docId);
        when(ingestJobRepository.findById(requestedJobId)).thenReturn(Optional.of(requested));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        var response = service().cancelForKnowledgeBase(kbId, requestedJobId);

        assertThat(response.getStatus()).isEqualTo(IngestJobStatus.CANCEL_REQUESTED);
        assertThat(requested.getCancelRequestedAt()).isNotNull();
        verify(ingestJobRepository).save(requested);
        verify(documentRepository).save(doc);
    }

    @Test
    void retryForKnowledgeBaseValidatesTenantScopedKbAndJobOwnership() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setStatus(IngestJobStatus.FAILED);
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
    void recoverQueuedJobsRotatesExpiredProcessingLeaseThroughCommittedOutbox() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID staleExecution = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setExecutionId(staleExecution);
        job.setStatus(IngestJobStatus.PROCESSING);
        job.setStage(IngestStage.EMBEDDING);
        job.setClaimedBy("worker-a");
        job.setLeaseExpiresAt(Instant.now().minusSeconds(30));
        job.setCallbackSequence(4L);
        Document doc = doc(kbId, docId);
        doc.setStatus(DocumentStatus.PROCESSING);
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        when(ingestJobRepository.findTop20ByStatusAndLeaseExpiresAtBeforeOrderByUpdatedAtAsc(
                eq(IngestJobStatus.PROCESSING), any(Instant.class))).thenReturn(List.of(job));
        when(ingestJobRepository.findTop20ByStatusAndStageOrderByCreatedAtAsc(
                IngestJobStatus.PENDING, IngestStage.QUEUED)).thenAnswer(ignored ->
                job.getStatus() == IngestJobStatus.PENDING ? List.of(job) : List.of());
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(kb);

        int recovered = service().recoverQueuedJobs();

        assertThat(recovered).isEqualTo(1);
        assertThat(job.getExecutionId()).isNotEqualTo(staleExecution);
        assertThat(job.getCallbackSequence()).isZero();
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.PENDING);
        assertThat(job.getStage()).isEqualTo(IngestStage.QUEUED);
        assertThat(job.getClaimedBy()).isNull();
        assertThat(job.getLeaseExpiresAt()).isNull();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        verify(ingestOutboxService).record(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test
    void recoverQueuedJobsFinalizesExpiredCancellationAndSchedulesVectorCleanup() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setExecutionId(UUID.randomUUID());
        job.setStatus(IngestJobStatus.CANCEL_REQUESTED);
        job.setStage(IngestStage.INDEXING);
        job.setClaimedBy("worker-a");
        job.setLeaseExpiresAt(Instant.now().minusSeconds(30));
        job.setCancelRequestedAt(Instant.now().minusSeconds(60));
        Document doc = doc(kbId, docId);
        doc.setStatus(DocumentStatus.PROCESSING);
        when(ingestJobRepository.findTop20ByStatusAndLeaseExpiresAtBeforeOrderByUpdatedAtAsc(
                eq(IngestJobStatus.PROCESSING), any(Instant.class))).thenReturn(List.of());
        when(ingestJobRepository.findTop20ByStatusAndLeaseExpiresAtBeforeOrderByUpdatedAtAsc(
                eq(IngestJobStatus.CANCEL_REQUESTED), any(Instant.class))).thenReturn(List.of(job));
        when(ingestJobRepository.findTop20ByStatusAndStageOrderByCreatedAtAsc(
                IngestJobStatus.PENDING, IngestStage.QUEUED)).thenReturn(List.of());
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        int recovered = service().recoverQueuedJobs();

        assertThat(recovered).isEqualTo(1);
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.CANCELLED);
        assertThat(job.getStage()).isEqualTo(IngestStage.CANCELLED);
        assertThat(job.getCompletedAt()).isNotNull();
        assertThat(job.getClaimedBy()).isNull();
        assertThat(job.getLeaseExpiresAt()).isNull();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.CANCELLED);
        verify(vectorCleanupTaskService).enqueueDocument(docId);
        verify(ingestJobRepository).save(job);
        verify(documentRepository).save(doc);
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
        verify(failureNotificationService, never()).recordTerminalFailure(any(), any());
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
    void recoverQueuedJobsOnScheduleLogsSuccessfulRecoveryPath() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, UUID.randomUUID());
        Document doc = doc(kbId, docId);
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        when(ingestJobRepository.findTop20ByStatusAndStageOrderByCreatedAtAsc(
                IngestJobStatus.PENDING, IngestStage.QUEUED)).thenReturn(List.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(kb);

        service().recoverQueuedJobsOnSchedule();

        verify(ingestJobProducer).enqueue(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());
    }

    @Test
    void recoverQueuedJobsUsesExceptionTypeWhenQueueErrorHasNoMessage() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, UUID.randomUUID());
        Document doc = doc(kbId, docId);
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        when(ingestJobRepository.findTop20ByStatusAndStageOrderByCreatedAtAsc(
                IngestJobStatus.PENDING, IngestStage.QUEUED)).thenReturn(List.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(kb);
        doThrow(new IllegalStateException())
                .when(ingestJobProducer).enqueue(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());

        assertThat(service().recoverQueuedJobs()).isZero();

        assertThat(job.getErrorMessage()).contains("IllegalStateException");
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
    void reindexKnowledgeBaseRollsDocumentsWithoutDeletingTheOnlineIndex() {
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
        verify(profileIndexStateService).resetForReindex(kb, List.of(first, second));
        verify(vectorCleanupTaskService).completePendingProfileKnowledgeBase(kbId);
        verify(chunkRepository, never()).deleteByKbId(kbId);
        verify(milvusVectorService, never()).deleteByKbId(kbId);
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
        verify(profileIndexStateService).resetForReindex(kb, List.of(doc));
        verify(vectorCleanupTaskService).completePendingProfileKnowledgeBase(kbId);
        verify(vectorCleanupTaskService, never()).enqueueKnowledgeBase(kbId);
        verify(milvusVectorService, never()).deleteByKbId(kbId);
        verify(documentRepository, never()).save(doc);
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test
    void reindexKnowledgeBaseDoesNotDeleteVectorsBeforeReplacementCompletes() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        Document doc = doc(kbId, docId);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc));
        var responses = service().reindexKnowledgeBase(kbId, "model", 256);

        assertThat(responses).hasSize(1);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        verify(profileIndexStateService).resetForReindex(kb, List.of(doc));
        verify(vectorCleanupTaskService).completePendingProfileKnowledgeBase(kbId);
        verify(chunkRepository, never()).deleteByKbId(kbId);
        verify(milvusVectorService, never()).deleteByKbId(kbId);
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

    @Test
    void getLatestMapsDocumentMetadataAndFailedDiagnosis() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, UUID.randomUUID());
        job.setStatus(IngestJobStatus.FAILED);
        job.setStage(IngestStage.FAILED);
        job.setRetryCount(1);
        job.setErrorMessage("bad pdf");
        Document doc = doc(kbId, docId);
        doc.setStatus(DocumentStatus.FAILED);
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        var response = service().getLatestByDoc(docId);

        assertThat(response.getDocumentFileName()).isEqualTo("a.md");
        assertThat(response.getDocumentStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(response.getDiagnosis().getSeverity()).isEqualTo("error");
        assertThat(response.getDiagnosis().getSummary()).contains("bad pdf");
        assertThat(response.getDiagnosis().getNextAction()).contains("Embedding");
        assertThat(response.getDiagnosis().isRetryable()).isTrue();
        assertThat(response.getDiagnosis().isStalled()).isFalse();
        assertThat(response.getDiagnosis().getAgeSeconds()).isGreaterThanOrEqualTo(0);
        assertThat(response.getDiagnosis().getLastUpdatedSeconds()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void getLatestDiagnosisWarnsWhenQueuedJobIsStalled() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, UUID.randomUUID());
        job.setUpdatedAt(Instant.now().minusSeconds(1_200));
        job.setCreatedAt(Instant.now().minusSeconds(1_500));
        Document doc = doc(kbId, docId);
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        var response = service().getLatestByDoc(docId);

        assertThat(response.getDiagnosis().getSeverity()).isEqualTo("warning");
        assertThat(response.getDiagnosis().isStalled()).isTrue();
        assertThat(response.getDiagnosis().getSummary()).contains("无进展");
        assertThat(response.getDiagnosis().getNextAction()).contains("队列").contains("Worker");
        assertThat(response.getDiagnosis().isRetryable()).isFalse();
        assertThat(response.getDiagnosis().getLastUpdatedSeconds()).isGreaterThanOrEqualTo(1_200);
    }

    @Test
    void getLatestDiagnosisCoversCompletedProcessingPendingAndProcessingStalledStates() {
        UUID kbId = UUID.randomUUID();
        UUID completedDocId = UUID.randomUUID();
        IngestJob completed = job(kbId, completedDocId, UUID.randomUUID());
        completed.setStatus(IngestJobStatus.COMPLETED);
        completed.setStage(IngestStage.COMPLETED);
        Document completedDoc = doc(kbId, completedDocId);
        completedDoc.setStatus(DocumentStatus.COMPLETED);
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(completedDocId)).thenReturn(Optional.of(completed));
        when(documentRepository.findById(completedDocId)).thenReturn(Optional.of(completedDoc));

        UUID processingDocId = UUID.randomUUID();
        IngestJob processing = job(kbId, processingDocId, UUID.randomUUID());
        processing.setStatus(IngestJobStatus.PROCESSING);
        processing.setStage(IngestStage.PARSING);
        Document processingDoc = doc(kbId, processingDocId);
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(processingDocId)).thenReturn(Optional.of(processing));
        when(documentRepository.findById(processingDocId)).thenReturn(Optional.of(processingDoc));

        UUID pendingDocId = UUID.randomUUID();
        IngestJob pending = job(kbId, pendingDocId, UUID.randomUUID());
        pending.setCreatedAt(null);
        pending.setUpdatedAt(null);
        Document pendingDoc = doc(kbId, pendingDocId);
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(pendingDocId)).thenReturn(Optional.of(pending));
        when(documentRepository.findById(pendingDocId)).thenReturn(Optional.of(pendingDoc));

        UUID stalledProcessingDocId = UUID.randomUUID();
        IngestJob stalledProcessing = job(kbId, stalledProcessingDocId, UUID.randomUUID());
        stalledProcessing.setStatus(IngestJobStatus.PROCESSING);
        stalledProcessing.setStage(IngestStage.INDEXING);
        stalledProcessing.setUpdatedAt(Instant.now().minusSeconds(1_200));
        Document stalledDoc = doc(kbId, stalledProcessingDocId);
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(stalledProcessingDocId)).thenReturn(Optional.of(stalledProcessing));
        when(documentRepository.findById(stalledProcessingDocId)).thenReturn(Optional.of(stalledDoc));

        IngestJobService service = service();

        assertThat(service.getLatestByDoc(completedDocId).getDiagnosis().getNextAction()).contains("RAG 评估");
        assertThat(service.getLatestByDoc(processingDocId).getDiagnosis().getSummary()).contains("正在摄入");
        assertThat(service.getLatestByDoc(pendingDocId).getDiagnosis().getSummary()).contains("等待摄入队列");
        assertThat(service.getLatestByDoc(pendingDocId).getDiagnosis().getAgeSeconds()).isZero();
        assertThat(service.getLatestByDoc(stalledProcessingDocId).getDiagnosis().getNextAction()).contains("Embedding");
    }

    @Test
    void getLatestDiagnosisCoversDeadLetterAndFailedRetryLimit() {
        UUID kbId = UUID.randomUUID();
        UUID deadLetterDocId = UUID.randomUUID();
        IngestJob deadLetter = job(kbId, deadLetterDocId, UUID.randomUUID());
        deadLetter.setStatus(IngestJobStatus.DEAD_LETTER);
        deadLetter.setStage(IngestStage.DEAD_LETTER);
        Document deadLetterDoc = doc(kbId, deadLetterDocId);
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(deadLetterDocId)).thenReturn(Optional.of(deadLetter));
        when(documentRepository.findById(deadLetterDocId)).thenReturn(Optional.of(deadLetterDoc));

        UUID failedDocId = UUID.randomUUID();
        IngestJob failed = job(kbId, failedDocId, UUID.randomUUID());
        failed.setStatus(IngestJobStatus.FAILED);
        failed.setStage(IngestStage.FAILED);
        failed.setRetryCount(3);
        failed.setErrorMessage(" ");
        Document failedDoc = doc(kbId, failedDocId);
        failedDoc.setStatus(DocumentStatus.FAILED);
        failedDoc.setErrorMessage("doc parse failed");
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(failedDocId)).thenReturn(Optional.of(failed));
        when(documentRepository.findById(failedDocId)).thenReturn(Optional.of(failedDoc));

        IngestJobService service = service();

        var deadLetterDiagnosis = service.getLatestByDoc(deadLetterDocId).getDiagnosis();
        assertThat(deadLetterDiagnosis.getSummary()).contains("死信").contains("未提供错误信息");
        assertThat(deadLetterDiagnosis.isRetryable()).isTrue();

        var failedDiagnosis = service.getLatestByDoc(failedDocId).getDiagnosis();
        assertThat(failedDiagnosis.getSummary()).contains("doc parse failed");
        assertThat(failedDiagnosis.isRetryable()).isFalse();
    }

    @Test
    void summarizeAlertsReportsOpenFailedAndDeadLetterJobs() {
        when(ingestJobRepository.countByStatusIn(List.of(
                IngestJobStatus.FAILED,
                IngestJobStatus.DEAD_LETTER
        ))).thenReturn(4L);

        var alerts = service().summarizeAlerts();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getCode()).isEqualTo("INGEST_FAILURES_OPEN");
        assertThat(alerts.get(0).getSeverity()).isEqualTo("WARN");
        assertThat(alerts.get(0).getCount()).isEqualTo(4L);
        assertThat(alerts.get(0).getMessage()).contains("ingest");
    }

    @Test
    void summarizeAlertsReturnsEmptyWhenNoOpenFailuresExist() {
        when(ingestJobRepository.countByStatusIn(List.of(
                IngestJobStatus.FAILED,
                IngestJobStatus.DEAD_LETTER
        ))).thenReturn(0L);

        assertThat(service().summarizeAlerts()).isEmpty();
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
