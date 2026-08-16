package com.dupi.rag.service;

import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.exception.OperationConflictException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

class OperationJobServiceTest {

    private final OperationJobRepository jobs = mock(OperationJobRepository.class);
    private final OperationStepRepository steps = mock(OperationStepRepository.class);
    private final OperationJobWriteService writes = mock(OperationJobWriteService.class);
    private final OperationStepWriteService stepWrites = mock(OperationStepWriteService.class);
    private final OperationJobService service = new OperationJobService(jobs, steps, writes, stepWrites);

    @AfterEach
    void clearContexts() {
        SecurityContext.clear();
        TenantContext.clear();
    }

    @Test
    void getReturnsOrderedStepsForTheCurrentTenantAndAuthorizedKnowledgeBase() {
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.setTenantId("tenant-a");
        SecurityContext.set("operator", "OPERATOR", List.of("KB_READ"), List.of(kbId.toString()));
        when(jobs.findById(jobId)).thenReturn(Optional.of(job(jobId, kbId)));
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of(
                OperationStep.builder().jobId(jobId).stepKey("prepare").sequenceNumber(1).build()
        ));

        var response = service.get(jobId);

        assertThat(response.getId()).isEqualTo(jobId);
        assertThat(response.getSteps()).extracting(step -> step.getStepKey()).containsExactly("prepare");
    }

    @Test
    void getDoesNotRevealJobsFromAnotherTenant() {
        UUID jobId = UUID.randomUUID();
        TenantContext.setTenantId("tenant-a");
        when(jobs.findById(jobId)).thenReturn(Optional.of(OperationJob.builder()
                .id(jobId).tenantId("tenant-b").aggregateType("OTHER").build()));

        assertThatThrownBy(() -> service.get(jobId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void retryReturnsAFailedJobToPreparedStateForItsAuthorizedOwner() {
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.setTenantId("tenant-a");
        SecurityContext.set("operator", "OPERATOR", List.of("KB_READ", "MAINTENANCE"), List.of(kbId.toString()));
        OperationJob job = job(jobId, kbId);
        job.setStatus(OperationStatus.FAILED);
        job.setLastError("minio unavailable");
        job.setNextAttemptAt(Instant.now().plusSeconds(600));
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of());

        var response = service.retry(jobId);

        assertThat(response.getStatus()).isEqualTo(OperationStatus.PREPARED);
        assertThat(job.getNextAttemptAt()).isBefore(Instant.now().plusSeconds(1));
        assertThat(job.getLastError()).isNull();
        verify(jobs).save(job);
    }

    @Test
    void createReturnsExistingJobForTheSameTenantTypeAndIdempotencyKey() {
        UUID kbId = UUID.randomUUID();
        TenantContext.setTenantId("tenant-a");
        OperationJob existing = job(UUID.randomUUID(), kbId);
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.RECOVERY_ARCHIVE_IMPORT, "request-1"))
                .thenReturn(Optional.of(existing));
        when(steps.findByJobIdOrderBySequenceNumberAsc(existing.getId())).thenReturn(List.of());

        var response = service.create(OperationType.RECOVERY_ARCHIVE_IMPORT, "KNOWLEDGE_BASE", kbId,
                "request-1", Map.of("source", "upload"), "operator");

        assertThat(response.getId()).isEqualTo(existing.getId());
        verify(jobs, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void recordingAnAlreadyCompletedStepPreservesItsCompletionForWorkflowRetries() {
        UUID jobId = UUID.randomUUID();
        OperationStep completed = OperationStep.builder()
                .id(UUID.randomUUID())
                .jobId(jobId)
                .stepKey("store-archive")
                .stepType("STORE")
                .sequenceNumber(1)
                .status(com.dupi.rag.domain.enums.OperationStepStatus.COMPLETED)
                .completedAt(Instant.now())
                .build();
        OperationExecutionContext context = new OperationExecutionContext(
                jobId, UUID.randomUUID(), 1, 0, OperationPhase.FORWARD);
        when(stepWrites.recordStep(context, "store-archive", "STORE", "archives/job-id.zip"))
                .thenReturn(completed);

        var result = service.recordStep(context, "store-archive", "STORE", "archives/job-id.zip");

        assertThat(result.getStatus()).isEqualTo(com.dupi.rag.domain.enums.OperationStepStatus.COMPLETED);
        verify(stepWrites).recordStep(context, "store-archive", "STORE", "archives/job-id.zip");
    }

    @Test
    void manualRetryPreservesCompensationDirectionAndReopensOnlyFailedSteps() {
        UUID jobId = UUID.randomUUID(); UUID kbId = UUID.randomUUID();
        TenantContext.setTenantId("tenant-a");
        SecurityContext.set("operator", "OPERATOR", List.of("KB_READ"), List.of(kbId.toString()));
        OperationJob job = job(jobId, kbId); job.setStatus(OperationStatus.FAILED);
        job.setPhase(OperationPhase.COMPENSATION); job.setPhaseAttemptCount(5);
        OperationStep failed = OperationStep.builder().jobId(jobId)
                .status(com.dupi.rag.domain.enums.OperationStepStatus.FAILED)
                .startedAt(Instant.now().minusSeconds(30)).completedAt(Instant.now())
                .lastError("old error").build();
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(steps.findByJobIdAndStatus(jobId, com.dupi.rag.domain.enums.OperationStepStatus.FAILED)).thenReturn(List.of(failed));
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of());

        assertThat(service.retry(jobId).getStatus()).isEqualTo(OperationStatus.COMPENSATING);
        assertThat(failed.getStatus()).isEqualTo(com.dupi.rag.domain.enums.OperationStepStatus.RETRY_WAIT);
        assertThat(job.getRetryEpoch()).isEqualTo(1L);
        assertThat(job.getPhaseAttemptCount()).isZero();
        assertThat(failed.getStartedAt()).isNull();
        assertThat(failed.getCompletedAt()).isNull();
        assertThat(failed.getLastError()).isNull();
    }

    @Test
    void staleExecutionContextCannotStartAStep() {
        UUID jobId = UUID.randomUUID();
        OperationExecutionContext context = new OperationExecutionContext(
                jobId, UUID.randomUUID(), 4, 0, OperationPhase.FORWARD);
        doThrow(new com.dupi.rag.exception.OperationConflictException("Operation claim is no longer current"))
                .when(stepWrites).startStep(context, "store");

        assertThatThrownBy(() -> service.startStep(context, "store"))
                .isInstanceOf(com.dupi.rag.exception.OperationConflictException.class);
    }

    @Test
    void recoveryImportReplayReturnsSameRunnableJobWhenImmutablePlanMatches() {
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        RecoveryArchiveImportPlan plan = plan(kbId, "a".repeat(64));
        TenantContext.setTenantId("tenant-a");
        OperationJob job = job(jobId, kbId);
        job.setRunnable(true);
        job.setStatus(OperationStatus.RUNNING);
        job.setInput(plan.toInput());
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.RECOVERY_ARCHIVE_IMPORT, "request-1"))
                .thenReturn(Optional.of(job));
        when(steps.findByJobIdAndStepKey(jobId, "stage-zip")).thenReturn(Optional.of(
                OperationStep.builder().status(com.dupi.rag.domain.enums.OperationStepStatus.COMPLETED).build()));
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of());

        RecoveryImportIntake intake = service.createOrResumeRecoveryImport(plan, "request-1", "operator");

        assertThat(intake.job().getId()).isEqualTo(jobId);
        assertThat(intake.published()).isTrue();
        verify(writes, org.mockito.Mockito.never()).insertRecoveryImport(any(), any(), any(), any());
    }

    @Test
    void recoveryImportReplayRejectsSameKeyForDifferentZipOrKnowledgeBase() {
        UUID kbId = UUID.randomUUID();
        TenantContext.setTenantId("tenant-a");
        OperationJob existing = job(UUID.randomUUID(), kbId);
        existing.setInput(plan(kbId, "a".repeat(64)).toInput());
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.RECOVERY_ARCHIVE_IMPORT, "request-1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createOrResumeRecoveryImport(
                plan(kbId, "b".repeat(64)), "request-1", "operator"))
                .isInstanceOf(OperationConflictException.class);
        assertThatThrownBy(() -> service.createOrResumeRecoveryImport(
                plan(UUID.randomUUID(), "a".repeat(64)), "request-1", "operator"))
                .isInstanceOf(OperationConflictException.class);
    }

    @Test
    void concurrentRecoveryImportLoserReloadsWinnerAfterFailedInsertTransaction() {
        UUID kbId = UUID.randomUUID();
        RecoveryArchiveImportPlan plan = plan(kbId, "a".repeat(64));
        TenantContext.setTenantId("tenant-a");
        OperationJob winner = job(UUID.randomUUID(), kbId); winner.setInput(plan.toInput());
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.RECOVERY_ARCHIVE_IMPORT, "request-1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(writes.insertRecoveryImport("tenant-a", plan, "request-1", "operator"))
                .thenThrow(new DataIntegrityViolationException("unique winner"));
        when(steps.findByJobIdAndStepKey(winner.getId(), "stage-zip")).thenReturn(Optional.of(
                OperationStep.builder().status(com.dupi.rag.domain.enums.OperationStepStatus.PENDING).build()));
        when(steps.findByJobIdOrderBySequenceNumberAsc(winner.getId())).thenReturn(List.of());

        assertThat(service.createOrResumeRecoveryImport(plan, "request-1", "operator").job().getId())
                .isEqualTo(winner.getId());
        verify(jobs, times(2)).findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.RECOVERY_ARCHIVE_IMPORT, "request-1");
    }

    @Test
    void concurrentSameKeySubmissionsConvergeOnOneWinner() throws Exception {
        UUID kbId = UUID.randomUUID(); RecoveryArchiveImportPlan plan = plan(kbId, "a".repeat(64));
        CyclicBarrier firstReads = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<OperationJob> winner = new AtomicReference<>();
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.RECOVERY_ARCHIVE_IMPORT, "race-key")).thenAnswer(call -> {
            if (reads.incrementAndGet() <= 2) { firstReads.await(5, TimeUnit.SECONDS); return Optional.empty(); }
            return Optional.ofNullable(winner.get());
        });
        when(writes.insertRecoveryImport("tenant-a", plan, "race-key", "operator")).thenAnswer(call -> {
            OperationJob candidate = job(UUID.randomUUID(), kbId); candidate.setInput(plan.toInput());
            if (winner.compareAndSet(null, candidate)) return candidate;
            throw new DataIntegrityViolationException("unique winner");
        });
        when(steps.findByJobIdAndStepKey(any(), eq("stage-zip"))).thenReturn(Optional.of(
                OperationStep.builder().status(com.dupi.rag.domain.enums.OperationStepStatus.PENDING).build()));
        when(steps.findByJobIdOrderBySequenceNumberAsc(any())).thenReturn(List.of());

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { TenantContext.setTenantId("tenant-a"); try {
                return service.createOrResumeRecoveryImport(plan, "race-key", "operator").job().getId();
            } finally { TenantContext.clear(); }});
            var second = pool.submit(() -> { TenantContext.setTenantId("tenant-a"); try {
                return service.createOrResumeRecoveryImport(plan, "race-key", "operator").job().getId();
            } finally { TenantContext.clear(); }});

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void recoveryImportRejectsPlanFromAnotherTenantBeforeCreating() {
        TenantContext.setTenantId("tenant-a");
        assertThatThrownBy(() -> service.createOrResumeRecoveryImport(
                new RecoveryArchiveImportPlan(UUID.randomUUID(), "tenant-b", UUID.randomUUID(), null,
                        "embedding", 3, Map.of(), "c".repeat(64), "d".repeat(64), "operator", List.of()),
                "request-1", "operator"))
                .isInstanceOf(OperationConflictException.class);
        verifyNoInteractions(writes);
    }

    @Test
    void sameRecoveryImportKeyAlreadyOwnedByAnotherTenantIsAConflict() {
        UUID kbId = UUID.randomUUID(); RecoveryArchiveImportPlan plan = new RecoveryArchiveImportPlan(
                UUID.randomUUID(), "tenant-b", kbId, null, "embedding", 3, Map.of(),
                "c".repeat(64), "d".repeat(64), "operator", List.of());
        TenantContext.setTenantId("tenant-b");
        OperationJob otherTenant = job(UUID.randomUUID(), UUID.randomUUID());
        otherTenant.setTenantId("tenant-a");
        when(jobs.findFirstByOperationTypeAndIdempotencyKeyOrderByCreatedAtAsc(
                OperationType.RECOVERY_ARCHIVE_IMPORT, "shared-key"))
                .thenReturn(Optional.of(otherTenant));

        assertThatThrownBy(() -> service.createOrResumeRecoveryImport(plan, "shared-key", "operator"))
                .isInstanceOf(OperationConflictException.class);
        verifyNoInteractions(writes);
    }

    @Test
    void recoveryImportPublishGateRejectsMissingPendingAndFailedIntakeSteps() {
        UUID jobId = UUID.randomUUID(); UUID kbId = UUID.randomUUID();
        RecoveryArchiveImportPlan plan = plan(kbId, "a".repeat(64));
        OperationJob job = job(jobId, kbId); job.setInput(plan.toInput());
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        when(steps.findByJobIdAndStepKey(jobId, "stage-zip"))
                .thenReturn(Optional.empty(), Optional.of(OperationStep.builder()
                        .status(com.dupi.rag.domain.enums.OperationStepStatus.PENDING).build()),
                        Optional.of(OperationStep.builder()
                                .status(com.dupi.rag.domain.enums.OperationStepStatus.FAILED).build()));

        assertThatThrownBy(() -> service.publishRecoveryImport(jobId, plan))
                .isInstanceOf(OperationConflictException.class);
        assertThatThrownBy(() -> service.publishRecoveryImport(jobId, plan))
                .isInstanceOf(OperationConflictException.class);
        assertThatThrownBy(() -> service.publishRecoveryImport(jobId, plan))
                .isInstanceOf(OperationConflictException.class);
        assertThat(job.getRunnable()).isFalse();
    }

    @Test
    void recoveryImportPublishGateLocksAndPublishesOnlyMatchingCompletedIntake() {
        UUID jobId = UUID.randomUUID(); UUID kbId = UUID.randomUUID();
        RecoveryArchiveImportPlan plan = plan(kbId, "a".repeat(64));
        OperationJob job = job(jobId, kbId); job.setInput(plan.toInput());
        OperationStep stage = OperationStep.builder().status(com.dupi.rag.domain.enums.OperationStepStatus.COMPLETED)
                .resourceRef("recovery-staging/" + jobId + ".zip").build();
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        when(steps.findByJobIdAndStepKey(jobId, "stage-zip")).thenReturn(Optional.of(stage));
        when(jobs.saveAndFlush(job)).thenReturn(job);
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of(stage));

        var response = service.publishRecoveryImport(jobId, plan);

        assertThat(response.getId()).isEqualTo(jobId);
        assertThat(job.getRunnable()).isTrue();
        verify(jobs).findByIdForUpdate(jobId);
        verify(jobs).saveAndFlush(job);
    }

    private static RecoveryArchiveImportPlan plan(UUID kbId, String zipSha) {
        return new RecoveryArchiveImportPlan(UUID.randomUUID(), "tenant-a", kbId, null,
                "embedding", 3, Map.of("metric", "COSINE"), "c".repeat(64), zipSha,
                "operator", List.of(new RecoveryArchiveImportPlan.Entry(
                "record:a", "RECORD", "records/a.json", 3, "d".repeat(64))));
    }

    private static OperationJob job(UUID jobId, UUID kbId) {
        return OperationJob.builder()
                .id(jobId)
                .tenantId("tenant-a")
                .operationType(OperationType.RECOVERY_ARCHIVE_IMPORT)
                .aggregateType("KNOWLEDGE_BASE")
                .aggregateId(kbId)
                .status(OperationStatus.PREPARED)
                .idempotencyKey("request-1")
                .input(Map.of())
                .attemptCount(0)
                .nextAttemptAt(Instant.now())
                .createdBy("operator")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
