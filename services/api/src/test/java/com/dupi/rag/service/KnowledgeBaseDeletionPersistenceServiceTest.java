package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.DocumentAsset;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.exception.RecoveryConflictException;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeBaseDeletionPersistenceServiceTest {
    private final KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentAssetRepository assets = mock(DocumentAssetRepository.class);
    private final RetrievalProfileRepository profiles = mock(RetrievalProfileRepository.class);
    private final SparseMigrationRepository sparseMigrations = mock(SparseMigrationRepository.class);
    private final RecoveryArchiveRepository archives = mock(RecoveryArchiveRepository.class);
    private final RecoveryRestoreJobRepository restores = mock(RecoveryRestoreJobRepository.class);
    private final OperationJobRepository jobs = mock(OperationJobRepository.class);
    private final OperationStepRepository steps = mock(OperationStepRepository.class);
    private final VectorCleanupTaskRepository vectorTasks = mock(VectorCleanupTaskRepository.class);
    private final DocumentTombstoneRepository tombstones = mock(DocumentTombstoneRepository.class);
    private final IngestFailureNotificationRepository notifications = mock(IngestFailureNotificationRepository.class);
    private final OperationDomainGuard guard = mock(OperationDomainGuard.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final RecoveryActivityProbe activity = mock(RecoveryActivityProbe.class);

    private KnowledgeBaseDeletionPersistenceService persistence;

    @BeforeEach
    void setUp() {
        persistence = new KnowledgeBaseDeletionPersistenceService(knowledgeBases, documents, assets, profiles, sparseMigrations,
                archives, restores, jobs, steps, vectorTasks, tombstones, notifications, guard, audit,
                List.of(activity));
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(steps.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void submitLocksLifecycleAndPersistsCompleteOrderedInventoryBeforeRunnableJob() {
        UUID kbId = UUID.randomUUID();
        UUID docA = new UUID(0, 10);
        UUID docB = new UUID(0, 20);
        UUID assetA = new UUID(0, 1);
        UUID sparseProfile = UUID.randomUUID();
        UUID denseOnlyProfile = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).tenantId("tenant-a").name("kb")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.READY).build();
        when(knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a")).thenReturn(Optional.of(kb));
        when(documents.findByKbIdOrderByIdAsc(kbId)).thenReturn(List.of(
                Document.builder().id(docA).kbId(kbId).objectKey("source/a.md").build(),
                Document.builder().id(docB).kbId(kbId).objectKey("source/b.md").build()));
        when(assets.findByKbIdOrderByIdAsc(kbId)).thenReturn(List.of(
                DocumentAsset.builder().id(assetA).kbId(kbId).docId(docA).objectKey("asset/a.png").build()));
        when(profiles.findByKbIdOrderByVersionDesc(kbId)).thenReturn(List.of(
                RetrievalProfile.builder().id(sparseProfile).kbId(kbId).version(7).build(),
                RetrievalProfile.builder().id(denseOnlyProfile).kbId(kbId).version(3).build()));
        when(sparseMigrations.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(
                com.dupi.rag.domain.entity.SparseMigration.builder().id(UUID.randomUUID()).kbId(kbId)
                        .profileId(sparseProfile).build()));

        UUID jobId = persistence.submit(kbId, "tenant-a", "alice");

        assertThat(jobId).isNotNull();
        assertThat(kb.getLifecycleStatus()).isEqualTo(KnowledgeBaseLifecycleStatus.DELETING);
        ArgumentCaptor<OperationJob> job = ArgumentCaptor.forClass(OperationJob.class);
        verify(jobs).saveAndFlush(job.capture());
        assertThat(job.getValue().getOperationType()).isEqualTo(OperationType.KNOWLEDGE_BASE_DELETE);
        assertThat(job.getValue().getStatus()).isEqualTo(OperationStatus.PREPARED);
        assertThat(job.getValue().getRunnable()).isTrue();
        assertThat(job.getValue().getInput()).containsEntry("knowledgeBaseId", kbId.toString());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OperationStep>> inventory = ArgumentCaptor.forClass(List.class);
        verify(steps).saveAll(inventory.capture());
        assertThat(inventory.getValue()).extracting(OperationStep::getSequenceNumber)
                .containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(inventory.getValue()).extracting(OperationStep::getStepType)
                .containsExactly("DELETE_OBJECT", "DELETE_OBJECT", "DELETE_OBJECT",
                        "DELETE_PROFILE_VECTORS", "DELETE_LEGACY_VECTORS",
                        "DELETE_SPARSE_VECTORS", "FINALIZE_DELETE");
        assertThat(inventory.getValue()).extracting(OperationStep::getResourceRef)
                .containsExactly("asset/a.png", "source/a.md", "source/b.md", kbId.toString(), kbId.toString(),
                        kbId + ":7", kbId.toString());
        verify(audit).recordSuccessInCurrentTransactionForTenant(eq("tenant-a"),
                eq("KNOWLEDGE_BASE_DELETE_SUBMIT"), eq("KNOWLEDGE_BASE"), eq(kbId), contains(jobId.toString()));
    }

    @Test
    void repeatedSubmitWhileDeletingReturnsExistingJobWithoutRebuildingInventory() {
        UUID kbId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).tenantId("tenant-a")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.DELETING).build();
        OperationJob existing = OperationJob.builder().id(jobId).tenantId("tenant-a")
                .operationType(OperationType.KNOWLEDGE_BASE_DELETE).aggregateId(kbId)
                .idempotencyKey("knowledge-base-delete:" + kbId).build();
        when(knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a")).thenReturn(Optional.of(kb));
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.KNOWLEDGE_BASE_DELETE, "knowledge-base-delete:" + kbId))
                .thenReturn(Optional.of(existing));

        assertThat(persistence.submit(kbId, "tenant-a", "alice")).isEqualTo(jobId);

        verifyNoInteractions(documents, assets, profiles);
        verify(jobs, never()).saveAndFlush(any());
        verify(steps, never()).saveAll(any());
    }

    @Test
    void recoveryArchiveOrActiveWorkRejectsSubmissionBeforeLifecycleChange() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).tenantId("tenant-a")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.READY).build();
        when(knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a")).thenReturn(Optional.of(kb));
        when(archives.findByTenantIdAndSourceKnowledgeBaseIdOrderByCreatedAtDesc("tenant-a", kbId))
                .thenReturn(List.of(com.dupi.rag.domain.entity.RecoveryArchive.builder().id(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> persistence.submit(kbId, "tenant-a", "alice"))
                .isInstanceOf(RecoveryConflictException.class).hasMessageContaining("recovery archive");
        assertThat(kb.getLifecycleStatus()).isEqualTo(KnowledgeBaseLifecycleStatus.READY);

        reset(archives);
        when(activity.hasActiveWork(kbId)).thenReturn(true);
        assertThatThrownBy(() -> persistence.submit(kbId, "tenant-a", "alice"))
                .isInstanceOf(RecoveryConflictException.class).hasMessageContaining("active work");
        assertThat(kb.getLifecycleStatus()).isEqualTo(KnowledgeBaseLifecycleStatus.READY);
    }

    @Test
    void activeDurableImportRejectsDeletionBeforeInventoryCanBecomeStale() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).tenantId("tenant-a")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.READY).build();
        when(knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a")).thenReturn(Optional.of(kb));
        when(jobs.existsByTenantIdAndAggregateTypeAndAggregateIdAndStatusIn(
                eq("tenant-a"), eq("KNOWLEDGE_BASE"), eq(kbId), anyList())).thenReturn(true);

        assertThatThrownBy(() -> persistence.submit(kbId, "tenant-a", "alice"))
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("durable operation");

        assertThat(kb.getLifecycleStatus()).isEqualTo(KnowledgeBaseLifecycleStatus.READY);
        verifyNoInteractions(documents, assets, profiles);
    }

    @Test
    void finalTransactionDeletesOnlyAfterAllExternalStepsAreCompleted() {
        UUID kbId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        OperationExecutionContext context = new OperationExecutionContext(jobId, UUID.randomUUID(), 2, 0,
                OperationPhase.FORWARD, "tenant-a", "alice");
        OperationJob job = OperationJob.builder().id(jobId).aggregateId(kbId).aggregateType("KNOWLEDGE_BASE")
                .operationType(OperationType.KNOWLEDGE_BASE_DELETE).tenantId("tenant-a")
                .status(OperationStatus.RUNNING).phase(OperationPhase.FORWARD).claimToken(context.claimToken())
                .claimEpoch(2L).retryEpoch(0L).leaseExpiresAt(Instant.now().plusSeconds(30)).build();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).tenantId("tenant-a")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.DELETING).build();
        OperationStep external = OperationStep.builder().jobId(jobId).sequenceNumber(1).stepKey("source")
                .stepType("DELETE_OBJECT").status(OperationStepStatus.COMPLETED).build();
        OperationStep finalize = OperationStep.builder().jobId(jobId).sequenceNumber(2).stepKey("finalize")
                .stepType("FINALIZE_DELETE").status(OperationStepStatus.PENDING).build();
        when(guard.assertActive(context)).thenReturn(job);
        when(knowledgeBases.findByIdForUpdate(kbId)).thenReturn(Optional.of(kb));
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of(external, finalize));

        persistence.completeDeletion(context, "finalize");

        assertThat(finalize.getStatus()).isEqualTo(OperationStepStatus.COMPLETED);
        assertThat(job.getStatus()).isEqualTo(OperationStatus.COMPLETED);
        assertThat(job.getRunnable()).isFalse();
        var order = inOrder(vectorTasks, tombstones, notifications, knowledgeBases, jobs);
        order.verify(vectorTasks).deleteByKnowledgeBaseId(kbId);
        order.verify(tombstones).deleteByKbId(kbId);
        order.verify(notifications).deleteByKbId(kbId);
        order.verify(knowledgeBases).delete(kb);
        order.verify(jobs).saveAndFlush(job);
    }

    @Test
    void finalTransactionRefusesToCascadeWhileAnyExternalStepIsIncomplete() {
        UUID kbId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        OperationExecutionContext context = new OperationExecutionContext(jobId, UUID.randomUUID(), 1, 0,
                OperationPhase.FORWARD, "tenant-a", "alice");
        OperationJob job = OperationJob.builder().id(jobId).aggregateId(kbId).aggregateType("KNOWLEDGE_BASE")
                .operationType(OperationType.KNOWLEDGE_BASE_DELETE).tenantId("tenant-a").build();
        when(guard.assertActive(context)).thenReturn(job);
        when(knowledgeBases.findByIdForUpdate(kbId)).thenReturn(Optional.of(
                KnowledgeBase.builder().id(kbId).tenantId("tenant-a")
                        .lifecycleStatus(KnowledgeBaseLifecycleStatus.DELETING).build()));
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of(
                OperationStep.builder().jobId(jobId).stepKey("source").stepType("DELETE_OBJECT")
                        .status(OperationStepStatus.RETRY_WAIT).build(),
                OperationStep.builder().jobId(jobId).stepKey("finalize").stepType("FINALIZE_DELETE")
                        .status(OperationStepStatus.PENDING).build()));

        assertThatThrownBy(() -> persistence.completeDeletion(context, "finalize"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
        verify(knowledgeBases, never()).delete(any());
    }
}
