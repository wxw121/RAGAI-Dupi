package com.dupi.rag.service;

import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RecoveryArchiveImportIntakeServiceTest {
    private final OperationJobRepository jobs = mock(OperationJobRepository.class);
    private final OperationStepRepository steps = mock(OperationStepRepository.class);
    private final RecoveryStorageService storage = mock(RecoveryStorageService.class);
    private final RecoveryArchiveImportIntakeWriteService writes =
            new RecoveryArchiveImportIntakeWriteService(jobs, steps);
    private final RecoveryArchiveImportIntakeService service =
            new RecoveryArchiveImportIntakeService(jobs, steps, writes, storage);

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void tenantScopedKeysNeverProbeOrConflictAcrossTenants() {
        UUID kbId = UUID.randomUUID();
        RecoveryArchiveImportPlan planA = plan("tenant-a", kbId, "a".repeat(64));
        RecoveryArchiveImportPlan plan = new RecoveryArchiveImportPlan(planA.sourceArchiveId(), "tenant-b",
                kbId, null, planA.embeddingModel(), planA.embeddingDimension(), planA.collectionSettings(),
                planA.sourceManifestChecksum(), planA.zipSha256(), planA.createdBy(), planA.entries());
        OperationJob tenantA = job("tenant-a", kbId, planA);
        OperationJob tenantB = job("tenant-b", kbId, plan);
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.RECOVERY_ARCHIVE_IMPORT, "same-key"))
                .thenReturn(Optional.of(tenantA));
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-b", OperationType.RECOVERY_ARCHIVE_IMPORT, "same-key"))
                .thenReturn(Optional.of(tenantB));
        when(steps.findByJobIdAndStepKey(tenantA.getId(), "stage-zip"))
                .thenReturn(Optional.of(stage(tenantA.getId(), OperationStepStatus.PENDING, "stage-a")));
        when(steps.findByJobIdAndStepKey(tenantB.getId(), "stage-zip"))
                .thenReturn(Optional.of(stage(tenantB.getId(), OperationStepStatus.PENDING, "stage-b")));

        TenantContext.setTenantId("tenant-a");
        assertThat(service.createOrResume(planA, "same-key", "operator").job().getId())
                .isEqualTo(tenantA.getId());
        TenantContext.setTenantId("tenant-b");
        assertThat(service.createOrResume(plan, "same-key", "operator").job().getId())
                .isEqualTo(tenantB.getId());

        assertThat(java.util.Arrays.stream(OperationJobRepository.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .noneMatch(name -> name.contains("findFirstByOperationTypeAndIdempotencyKey"));
        assertThat(java.util.Arrays.stream(OperationJobService.class.getDeclaredMethods())
                .flatMap(method -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(method.getReturnType()),
                        java.util.Arrays.stream(method.getParameterTypes()))))
                .noneMatch(type -> type.getSimpleName().contains("Recovery"));
    }

    @Test
    void atomicPublishAcceptsTheSameCompletedEvidenceAfterAnotherRequestPublished() {
        RecoveryArchiveImportPlan plan = plan("tenant-a", UUID.randomUUID(), "b".repeat(64));
        OperationJob job = job("tenant-a", plan.knowledgeBaseId(), plan);
        job.setRunnable(true);
        job.setStatus(OperationStatus.RUNNING);
        StoredRecoveryObject evidence = new StoredRecoveryObject("bucket",
                "recovery-staging/" + plan.zipSha256() + "/" + job.getId() + ".zip",
                123, plan.zipSha256(), "etag-7");
        OperationStep stage = stage(job.getId(), OperationStepStatus.COMPLETED,
                RecoveryStageEvidence.encode(evidence));
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(steps.findByJobIdAndStepKey(job.getId(), "stage-zip")).thenReturn(Optional.of(stage));
        when(steps.findByJobIdOrderBySequenceNumberAsc(job.getId())).thenReturn(List.of(stage));

        assertThat(writes.completeStageAndPublish(job.getId(), plan, evidence).getId()).isEqualTo(job.getId());

        verify(jobs).findByIdForUpdate(job.getId());
        verify(jobs, never()).saveAndFlush(any());
        verify(steps, never()).saveAndFlush(any());
    }

    @Test
    void onlyCompletedCompensationCleanupCanReopenTheSameIntake() {
        RecoveryArchiveImportPlan plan = plan("tenant-a", UUID.randomUUID(), "c".repeat(64));
        OperationJob job = job("tenant-a", plan.knowledgeBaseId(), plan);
        job.setPhase(OperationPhase.COMPENSATION);
        job.setStatus(OperationStatus.COMPLETED);
        job.setRunnable(true);
        job.setRetryEpoch(4L);
        job.setCompletedAt(Instant.now());
        OperationStep stage = stage(job.getId(), OperationStepStatus.COMPENSATED, "old-stage");
        OperationStep cleanup = OperationStep.builder().jobId(job.getId()).stepKey("cleanup-staging")
                .stepType("CLEANUP_STAGING").status(OperationStepStatus.COMPLETED).build();
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(steps.findByJobIdAndStepKey(job.getId(), "stage-zip")).thenReturn(Optional.of(stage));
        when(steps.findByJobIdOrderBySequenceNumberAsc(job.getId())).thenReturn(List.of(stage, cleanup));
        when(jobs.saveAndFlush(job)).thenReturn(job);

        writes.reopenCleanedIntake(job.getId(), plan,
                "recovery-staging/" + plan.zipSha256() + "/" + job.getId() + ".zip");

        assertThat(job.getPhase()).isEqualTo(OperationPhase.FORWARD);
        assertThat(job.getStatus()).isEqualTo(OperationStatus.PREPARED);
        assertThat(job.getRunnable()).isFalse();
        assertThat(job.getRetryEpoch()).isEqualTo(5L);
        assertThat(job.getCompletedAt()).isNull();
        assertThat(stage.getStatus()).isEqualTo(OperationStepStatus.PENDING);
        assertThat(cleanup.getStatus()).isEqualTo(OperationStepStatus.PENDING);
        assertThat(cleanup.getRetryEpoch()).isEqualTo(5L);
    }

    @Test
    void partialUploadFailureBecomesSchedulableDurableCompensationWork() {
        RecoveryArchiveImportPlan plan = plan("tenant-a", UUID.randomUUID(), "e".repeat(64));
        OperationJob job = job("tenant-a", plan.knowledgeBaseId(), plan);
        OperationStep stage = stage(job.getId(), OperationStepStatus.PENDING,
                "recovery-staging/" + plan.zipSha256() + "/" + job.getId() + ".zip");
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(steps.findByJobIdAndStepKey(job.getId(), "stage-zip")).thenReturn(Optional.of(stage));
        when(jobs.saveAndFlush(job)).thenReturn(job);

        writes.scheduleCleanup(job.getId(), plan, "partial put; delete timeout");

        assertThat(stage.getStatus()).isEqualTo(OperationStepStatus.FAILED);
        assertThat(stage.getLastError()).contains("delete timeout");
        assertThat(job.getPhase()).isEqualTo(OperationPhase.COMPENSATION);
        assertThat(job.getStatus()).isEqualTo(OperationStatus.COMPENSATING);
        assertThat(job.getRunnable()).isTrue();
        assertThat(job.getNextAttemptAt()).isNotNull();
    }

    @Test
    void concurrentDifferentContentWithTheSameTenantKeyHasOneWinnerAndOneConflict() throws Exception {
        UUID kbId = UUID.randomUUID();
        RecoveryArchiveImportPlan firstPlan = plan("tenant-a", kbId, "1".repeat(64));
        RecoveryArchiveImportPlan secondPlan = plan("tenant-a", kbId, "2".repeat(64));
        CyclicBarrier initialReads = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<OperationJob> winner = new AtomicReference<>();
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.RECOVERY_ARCHIVE_IMPORT, "race-key")).thenAnswer(call -> {
            if (reads.incrementAndGet() <= 2) {
                initialReads.await(5, TimeUnit.SECONDS);
                return Optional.empty();
            }
            return Optional.ofNullable(winner.get());
        });
        RecoveryArchiveImportIntakeWriteService concurrentWrites = mock(RecoveryArchiveImportIntakeWriteService.class);
        RecoveryArchiveImportIntakeService concurrentService =
                new RecoveryArchiveImportIntakeService(jobs, steps, concurrentWrites, storage);
        when(concurrentWrites.insert(eq("tenant-a"), any(), eq("race-key"), eq("operator"), isNull()))
                .thenAnswer(call -> {
                    RecoveryArchiveImportPlan requested = call.getArgument(1);
                    OperationJob candidate = job("tenant-a", kbId, requested);
                    if (winner.compareAndSet(null, candidate)) return candidate;
                    throw new DataIntegrityViolationException("unique winner");
                });
        doAnswer(call -> {
            OperationJob candidate = call.getArgument(0);
            RecoveryArchiveImportPlan requested = call.getArgument(1);
            writes.validatePlan(candidate, requested);
            return null;
        }).when(concurrentWrites).validatePlan(any(), any());
        when(steps.findByJobIdAndStepKey(any(), eq("stage-zip"))).thenAnswer(call -> Optional.of(
                stage(call.getArgument(0), OperationStepStatus.PENDING, "stage")));

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> submit(concurrentService, firstPlan));
            var second = pool.submit(() -> submit(concurrentService, secondPlan));
            Object firstResult = first.get(10, TimeUnit.SECONDS);
            Object secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(firstResult, secondResult).stream().filter(UUID.class::isInstance).toList()).hasSize(1);
            assertThat(List.of(firstResult, secondResult).stream()
                    .filter(com.dupi.rag.exception.OperationConflictException.class::isInstance).toList()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void intakeWriterExposesSeparateInsertAndAtomicLockedPublishTransactions() throws Exception {
        var insert = RecoveryArchiveImportIntakeWriteService.class.getDeclaredMethod("insert",
                String.class, RecoveryArchiveImportPlan.class, String.class, String.class, String.class);
        var publish = RecoveryArchiveImportIntakeWriteService.class.getDeclaredMethod(
                "completeStageAndPublish", UUID.class, RecoveryArchiveImportPlan.class,
                StoredRecoveryObject.class);

        assertThat(insert.getAnnotation(org.springframework.transaction.annotation.Transactional.class)
                .propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
        assertThat(publish.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull();
    }

    private static Object submit(RecoveryArchiveImportIntakeService service,
                                 RecoveryArchiveImportPlan plan) {
        TenantContext.setTenantId("tenant-a");
        try {
            return service.createOrResume(plan, "race-key", "operator").job().getId();
        } catch (RuntimeException failure) {
            return failure;
        } finally {
            TenantContext.clear();
        }
    }

    private static RecoveryArchiveImportPlan plan(String tenant, UUID kbId, String zipSha) {
        return new RecoveryArchiveImportPlan(UUID.randomUUID(), tenant, kbId, null,
                "embedding", 3, Map.of(), "d".repeat(64), zipSha, "operator", List.of());
    }

    private static OperationJob job(String tenant, UUID kbId, RecoveryArchiveImportPlan plan) {
        return OperationJob.builder().id(UUID.randomUUID()).tenantId(tenant)
                .operationType(OperationType.RECOVERY_ARCHIVE_IMPORT).aggregateType("KNOWLEDGE_BASE")
                .aggregateId(kbId).status(OperationStatus.PREPARED).phase(OperationPhase.FORWARD)
                .runnable(false).idempotencyKey("same-key").input(plan.toInput())
                .attemptCount(0).retryEpoch(0L).createdBy("operator").build();
    }

    private static OperationStep stage(UUID jobId, OperationStepStatus status, String ref) {
        return OperationStep.builder().jobId(jobId).sequenceNumber(1).stepKey("stage-zip")
                .stepType("STAGE_UPLOAD").status(status).resourceRef(ref).attemptCount(0).build();
    }
}
