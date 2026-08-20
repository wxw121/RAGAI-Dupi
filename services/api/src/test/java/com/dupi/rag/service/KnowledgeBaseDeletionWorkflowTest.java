package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.DocumentAsset;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.repository.OperationStepRepository;
import com.dupi.rag.repository.OperationJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KnowledgeBaseDeletionWorkflowTest {

    @Test
    void minioFailureLeavesInventoryIncompleteAndRequestsRetryBeforeFinalDelete() {
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        OperationExecutionContext context = context(jobId);
        OperationStep asset = step(jobId, 1, "asset-1", "DELETE_OBJECT", "assets/logo.png");

        OperationStepRepository steps = mock(OperationStepRepository.class);
        OperationJobService operations = mock(OperationJobService.class);
        OperationJobClaimService claims = mock(OperationJobClaimService.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        MilvusVectorService vectors = mock(MilvusVectorService.class);
        KnowledgeBaseDeletionPersistenceService persistence = mock(KnowledgeBaseDeletionPersistenceService.class);
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of(asset));
        doThrow(new IllegalStateException("minio down")).when(storage).deleteChecked("assets/logo.png");

        KnowledgeBaseDeletionWorkflow workflow = new KnowledgeBaseDeletionWorkflow(
                steps, operations, claims, storage, vectors, persistence);

        assertThatThrownBy(() -> workflow.executeForward(context))
                .isInstanceOf(RetryableOperationException.class)
                .hasMessageContaining("object storage");

        verify(operations).startStep(context, "asset-1");
        verify(operations).retryStep(eq(context), eq("asset-1"), contains("minio down"), any(Instant.class));
        verify(persistence, never()).completeDeletion(any(), any());
        assertThat(asset.getStatus()).isEqualTo(OperationStepStatus.PENDING);
    }

    @Test
    void executesPersistedInventoryInOrderAndFinalizesOnlyAfterEveryExternalScope() {
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        OperationExecutionContext context = context(jobId);
        List<OperationStep> inventory = List.of(
                step(jobId, 1, "asset-a", "DELETE_OBJECT", "assets/a.png"),
                step(jobId, 2, "source-b", "DELETE_OBJECT", "documents/b.md"),
                step(jobId, 3, "profile", "DELETE_PROFILE_VECTORS", kbId.toString()),
                step(jobId, 4, "legacy", "DELETE_LEGACY_VECTORS", kbId.toString()),
                step(jobId, 5, "sparse-v7", "DELETE_SPARSE_VECTORS", kbId + ":7"),
                step(jobId, 6, "finalize", "FINALIZE_DELETE", kbId.toString()));

        OperationStepRepository steps = mock(OperationStepRepository.class);
        OperationJobService operations = mock(OperationJobService.class);
        OperationJobClaimService claims = mock(OperationJobClaimService.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        MilvusVectorService vectors = mock(MilvusVectorService.class);
        KnowledgeBaseDeletionPersistenceService persistence = mock(KnowledgeBaseDeletionPersistenceService.class);
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(inventory);

        new KnowledgeBaseDeletionWorkflow(steps, operations, claims, storage, vectors, persistence)
                .executeForward(context);

        var order = inOrder(storage, vectors, persistence);
        order.verify(storage).deleteChecked("assets/a.png");
        order.verify(storage).deleteChecked("documents/b.md");
        order.verify(vectors).deleteProfileByKbIdForCleanup(kbId);
        order.verify(vectors).deleteLegacyByKbIdForCleanup(kbId);
        order.verify(vectors).deleteSparseByKbIdForCleanup(kbId, List.of(7));
        order.verify(persistence).completeDeletion(context, "finalize");
        assertThat(new KnowledgeBaseDeletionWorkflow(steps, operations, claims, storage, vectors, persistence).type())
                .isEqualTo(OperationType.KNOWLEDGE_BASE_DELETE);
    }

    @Test
    void transientFinalTransactionFailureRequestsJobRetryWithoutCompletingFinalStep() {
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        OperationExecutionContext context = context(jobId);
        OperationStep finalize = step(jobId, 1, "finalize", "FINALIZE_DELETE", kbId.toString());
        OperationStepRepository steps = mock(OperationStepRepository.class);
        OperationJobService operations = mock(OperationJobService.class);
        OperationJobClaimService claims = mock(OperationJobClaimService.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        MilvusVectorService vectors = mock(MilvusVectorService.class);
        KnowledgeBaseDeletionPersistenceService persistence = mock(KnowledgeBaseDeletionPersistenceService.class);
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of(finalize));
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(persistence).completeDeletion(context, "finalize");

        KnowledgeBaseDeletionWorkflow workflow = new KnowledgeBaseDeletionWorkflow(
                steps, operations, claims, storage, vectors, persistence);

        assertThatThrownBy(() -> workflow.executeForward(context))
                .isInstanceOf(RetryableOperationException.class)
                .hasMessageContaining("final transaction");
        assertThat(finalize.getStatus()).isEqualTo(OperationStepStatus.PENDING);
        verifyNoInteractions(storage, vectors);
        verifyNoInteractions(operations);
    }

    @Test
    void realRunnerSchedulesMinioFailureWithoutRemovingKnowledgeBaseOrAssetMetadata() {
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        KnowledgeBase knowledgeBase = KnowledgeBase.builder().id(kbId).tenantId("tenant-a").build();
        DocumentAsset assetMetadata = DocumentAsset.builder().id(assetId).kbId(kbId)
                .objectKey("assets/logo.png").build();
        OperationJob job = OperationJob.builder().id(jobId).tenantId("tenant-a").createdBy("alice")
                .operationType(OperationType.KNOWLEDGE_BASE_DELETE).aggregateType("KNOWLEDGE_BASE")
                .aggregateId(kbId).status(OperationStatus.PREPARED).phase(OperationPhase.FORWARD)
                .runnable(true).phaseAttemptCount(0).attemptCount(0).claimEpoch(0L).retryEpoch(0L)
                .nextAttemptAt(Instant.now()).build();
        OperationStep assetStep = step(jobId, 1, "asset-1", "DELETE_OBJECT", "assets/logo.png");

        OperationJobRepository jobs = mock(OperationJobRepository.class);
        OperationStepRepository steps = mock(OperationStepRepository.class);
        OperationJobService operations = mock(OperationJobService.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        MilvusVectorService vectors = mock(MilvusVectorService.class);
        KnowledgeBaseDeletionPersistenceService persistence = mock(KnowledgeBaseDeletionPersistenceService.class);
        when(jobs.claimNextForUpdate(any(Instant.class))).thenReturn(java.util.Optional.of(job));
        when(jobs.findByIdForUpdate(jobId)).thenReturn(java.util.Optional.of(job));
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of(assetStep));
        doAnswer(invocation -> {
            assetStep.setStatus(OperationStepStatus.RUNNING);
            return assetStep;
        }).when(operations).startStep(any(), eq("asset-1"));
        doAnswer(invocation -> {
            assetStep.setStatus(OperationStepStatus.RETRY_WAIT);
            assetStep.setLastError(invocation.getArgument(2));
            return assetStep;
        }).when(operations).retryStep(any(), eq("asset-1"), anyString(), any(Instant.class));
        doThrow(new IllegalStateException("minio down")).when(storage).deleteChecked("assets/logo.png");

        OperationJobClaimService claims = new OperationJobClaimService(jobs);
        KnowledgeBaseDeletionWorkflow workflow = new KnowledgeBaseDeletionWorkflow(
                steps, operations, claims, storage, vectors, persistence);
        OperationJobRunner runner = new OperationJobRunner(claims, List.of(workflow), 1, true);

        assertThat(runner.runOne()).isTrue();
        assertThat(job.getStatus()).isEqualTo(OperationStatus.RETRY_WAIT);
        assertThat(assetStep.getStatus()).isEqualTo(OperationStepStatus.RETRY_WAIT);
        assertThat(assetStep.getLastError()).contains("minio down");
        assertThat(knowledgeBase.getId()).isEqualTo(kbId);
        assertThat(assetMetadata.getId()).isEqualTo(assetId);
        verify(persistence, never()).completeDeletion(any(), any());
    }

    @Test
    void realRunnerAcknowledgesAtomicFinalTransactionAsCompleted() {
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        OperationJob job = OperationJob.builder().id(jobId).tenantId("tenant-a").createdBy("alice")
                .operationType(OperationType.KNOWLEDGE_BASE_DELETE).aggregateType("KNOWLEDGE_BASE")
                .aggregateId(kbId).status(OperationStatus.PREPARED).phase(OperationPhase.FORWARD)
                .runnable(true).phaseAttemptCount(0).attemptCount(0).claimEpoch(0L).retryEpoch(0L)
                .nextAttemptAt(Instant.now()).build();
        OperationStep finalize = step(jobId, 1, "finalize-delete", "FINALIZE_DELETE", kbId.toString());
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        OperationStepRepository steps = mock(OperationStepRepository.class);
        OperationJobService operations = mock(OperationJobService.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        MilvusVectorService vectors = mock(MilvusVectorService.class);
        KnowledgeBaseDeletionPersistenceService persistence = mock(KnowledgeBaseDeletionPersistenceService.class);
        when(jobs.claimNextForUpdate(any(Instant.class))).thenReturn(java.util.Optional.of(job));
        when(jobs.findByIdForUpdate(jobId)).thenReturn(java.util.Optional.of(job));
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of(finalize));
        doAnswer(invocation -> {
            finalize.setStatus(OperationStepStatus.COMPLETED);
            job.setStatus(OperationStatus.COMPLETED);
            job.setRunnable(false);
            job.setClaimToken(null);
            job.setLeaseExpiresAt(null);
            return null;
        }).when(persistence).completeDeletion(any(), eq("finalize-delete"));
        OperationJobClaimService claims = new OperationJobClaimService(jobs);
        OperationJobRunner runner = new OperationJobRunner(claims, List.of(new KnowledgeBaseDeletionWorkflow(
                steps, operations, claims, storage, vectors, persistence)), 1, true);

        assertThat(runner.runOne()).isTrue();
        assertThat(job.getStatus()).isEqualTo(OperationStatus.COMPLETED);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getClaimEpoch()).isEqualTo(1L);
        assertThat(finalize.getStatus()).isEqualTo(OperationStepStatus.COMPLETED);
        verify(persistence).completeDeletion(any(OperationExecutionContext.class), eq("finalize-delete"));
    }

    private OperationExecutionContext context(UUID jobId) {
        return new OperationExecutionContext(jobId, UUID.randomUUID(), 1, 0,
                OperationPhase.FORWARD, "tenant-a", "alice");
    }

    private OperationStep step(UUID jobId, int sequence, String key, String type, String resource) {
        return OperationStep.builder().jobId(jobId).sequenceNumber(sequence).stepKey(key).stepType(type)
                .resourceRef(resource).status(OperationStepStatus.PENDING).build();
    }
}
