package com.dupi.rag.service;

import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.entity.OperationStagingAttempt;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MarkdownImportIntakeServiceTest {

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void intakeIntentLocksReadyKnowledgeBaseBeforeCreatingJob() throws Exception {
        MarkdownImportPlan plan = plan("same-key", "a.md", "a");
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        OperationStepRepository steps = mock(OperationStepRepository.class);
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        KnowledgeBase ready = KnowledgeBase.builder().id(plan.knowledgeBaseId()).tenantId("tenant-a")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.READY).build();
        when(knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(plan.knowledgeBaseId(), "tenant-a"))
                .thenReturn(Optional.of(ready));
        when(jobs.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        new MarkdownImportIntakeWriteService(jobs, steps, knowledgeBases, audit)
                .insert("tenant-a", "same-key", "alice", plan);

        var order = inOrder(knowledgeBases, jobs);
        order.verify(knowledgeBases).findByIdAndTenantIdForUpdateAnyStatus(plan.knowledgeBaseId(), "tenant-a");
        order.verify(jobs).saveAndFlush(argThat(job -> job.getStatus() == OperationStatus.PREPARED
                && !job.getRunnable()));
        verify(audit).recordOperationInCurrentTransaction(
                "tenant-a", AuditLogService.OPERATION_SUBMIT, plan.jobId(), "Operation submitted");
    }

    @Test
    void deletionFirstPreventsMarkdownIntakeCreation() throws Exception {
        MarkdownImportPlan plan = plan("same-key", "a.md", "a");
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        OperationStepRepository steps = mock(OperationStepRepository.class);
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        KnowledgeBase deleting = KnowledgeBase.builder().id(plan.knowledgeBaseId()).tenantId("tenant-a")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.DELETING).build();
        when(knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(plan.knowledgeBaseId(), "tenant-a"))
                .thenReturn(Optional.of(deleting));

        assertThatThrownBy(() -> new MarkdownImportIntakeWriteService(jobs, steps, knowledgeBases)
                .insert("tenant-a", "same-key", "alice", plan))
                .isInstanceOf(OperationConflictException.class);
        verifyNoInteractions(jobs, steps);
    }

    @Test
    void jobStaysNonRunnableUntilEveryDeterministicStageIsDurable() throws Exception {
        TenantContext.setTenantId("tenant-a");
        MarkdownImportPlan plan = plan("same-key", "a.md", "a", "b.md", "b");
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        MarkdownImportIntakeWriteService writes = mock(MarkdownImportIntakeWriteService.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        OperationJob prepared = job(plan, false);
        OperationJob runnable = job(plan, true);
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.MARKDOWN_PACKAGE_IMPORT, "same-key")).thenReturn(Optional.empty());
        when(writes.insert("tenant-a", "same-key", "alice", plan)).thenReturn(prepared);
        when(writes.publishRunnable(plan.jobId(), plan)).thenReturn(runnable);
        when(storage.inspect(anyString(), anyLong(), anyString())).thenReturn(null);
        MarkdownImportIntakeService intake = new MarkdownImportIntakeService(jobs, writes, storage);

        var response = intake.submit(plan, "same-key", "alice");

        assertThat(response.getId()).isEqualTo(plan.jobId());
        verify(storage, times(2)).uploadIfAbsent(anyString(), any(), anyLong(), anyString());
        verify(writes, times(2)).completeStage(eq(plan.jobId()), eq(plan), any());
        var order = inOrder(writes);
        order.verify(writes).insert("tenant-a", "same-key", "alice", plan);
        order.verify(writes).validatePlan(prepared, plan);
        order.verify(writes, times(2)).completeStage(eq(plan.jobId()), eq(plan), any());
        order.verify(writes).publishRunnable(plan.jobId(), plan);
    }

    @Test
    void productionIntakeUsesAttemptKeyAndExactOwnerThroughPublish() throws Exception {
        TenantContext.setTenantId("tenant-a");
        MarkdownImportPlan plan = plan("owned", "a.md", "a");
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        MarkdownImportIntakeWriteService writes = mock(MarkdownImportIntakeWriteService.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        OperationStagingLeaseCoordinator leases = mock(OperationStagingLeaseCoordinator.class);
        OperationStagingAttemptService attempts = mock(OperationStagingAttemptService.class);
        OperationStagingHeartbeat heartbeat = mock(OperationStagingHeartbeat.class);
        OperationJob prepared = job(plan, false); OperationJob runnable = job(plan, true);
        OperationStagingLease lease = new OperationStagingLease(plan.jobId(), UUID.randomUUID(), 3);
        String actualKey = plan.entries().get(0).stagingKey() + ".attempt-3-" + lease.token();
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(anyString(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(writes.insert("tenant-a", "owned", "alice", plan)).thenReturn(prepared);
        when(leases.acquire(plan.jobId())).thenReturn(lease);
        when(leases.start(lease)).thenReturn(heartbeat);
        when(attempts.arm(eq(lease), anyString(), eq("MINIO"), anyString()))
                .thenReturn(OperationStagingAttempt.builder().objectKey(actualKey).build());
        when(storage.inspect(anyString(), anyLong(), anyString())).thenReturn(null);
        when(writes.publishRunnable(plan.jobId(), plan, lease)).thenReturn(runnable);

        new MarkdownImportIntakeService(jobs, writes, storage, leases, attempts)
                .submit(plan, "owned", "alice");

        verify(storage).uploadIfAbsent(eq(actualKey), any(), anyLong(), anyString());
        verify(writes).completeStage(plan.jobId(), plan, plan.entries().get(0), lease, actualKey);
        verify(writes).publishRunnable(plan.jobId(), plan, lease);
        verify(heartbeat).close();
    }

    @Test
    void sameKeyReplayReturnsRunnableWinnerWithoutRestagingAndDifferentPlanConflicts() throws Exception {
        TenantContext.setTenantId("tenant-a");
        MarkdownImportPlan plan = plan("same-key", "a.md", "a");
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        MarkdownImportIntakeWriteService writes = mock(MarkdownImportIntakeWriteService.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        OperationJob winner = job(plan, true);
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.MARKDOWN_PACKAGE_IMPORT, "same-key")).thenReturn(Optional.of(winner));
        MarkdownImportIntakeService intake = new MarkdownImportIntakeService(jobs, writes, storage);

        assertThat(intake.submit(plan, "same-key", "alice").getId()).isEqualTo(plan.jobId());
        verifyNoInteractions(storage);

        MarkdownImportPlan different = plan("same-key", "a.md", "different");
        doThrow(new OperationConflictException("different input")).when(writes).validatePlan(winner, different);
        assertThatThrownBy(() -> intake.submit(different, "same-key", "alice"))
                .isInstanceOf(OperationConflictException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void partialStageFailureDurablySchedulesCompensation() throws Exception {
        TenantContext.setTenantId("tenant-a");
        MarkdownImportPlan plan = plan("same-key", "a.md", "a", "b.md", "b");
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        MarkdownImportIntakeWriteService writes = mock(MarkdownImportIntakeWriteService.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        OperationJob prepared = job(plan, false);
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(anyString(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(writes.insert(anyString(), anyString(), anyString(), eq(plan))).thenReturn(prepared);
        when(storage.inspect(anyString(), anyLong(), anyString())).thenReturn(null);
        when(storage.uploadIfAbsent(anyString(), any(), anyLong(), anyString()))
                .thenReturn(MinioStorageService.ObjectWriteResult.CREATED)
                .thenThrow(new IllegalStateException("minio down"));
        MarkdownImportIntakeService intake = new MarkdownImportIntakeService(jobs, writes, storage);

        assertThatThrownBy(() -> intake.submit(plan, "same-key", "alice"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("minio down");
        verify(writes).scheduleCleanup(plan.jobId(), plan, "minio down");
        verify(writes, never()).publishRunnable(any(), any());
    }

    @Test
    void delayedSameKeyStageFailureCannotCompensateACompletedWinner() throws Exception {
        MarkdownImportPlan plan = plan("same-key", "a.md", "a");
        OperationJob job = job(plan, false);
        job.setPhase(OperationPhase.FORWARD);
        OperationStep stage = OperationStep.builder().jobId(plan.jobId()).sequenceNumber(1)
                .stepKey(MarkdownImportIntakeWriteService.stageStep(plan.entries().get(0)))
                .stepType("STAGE_OBJECT").resourceRef(plan.entries().get(0).stagingKey())
                .status(OperationStepStatus.PENDING).attemptCount(0).build();
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        OperationStepRepository steps = mock(OperationStepRepository.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.MARKDOWN_PACKAGE_IMPORT, "same-key"))
                .thenReturn(Optional.of(job));
        when(jobs.findByIdForUpdate(plan.jobId())).thenReturn(Optional.of(job));
        when(jobs.findById(plan.jobId())).thenReturn(Optional.of(job));
        when(steps.findByJobIdAndStepKey(eq(plan.jobId()), anyString())).thenReturn(Optional.of(stage));
        CountDownLatch delayedEnteredStorage = new CountDownLatch(1);
        CountDownLatch winnerPublished = new CountDownLatch(1);
        when(storage.inspect(anyString(), anyLong(), anyString())).thenAnswer(invocation -> {
            if (Thread.currentThread().getName().contains("delayed")) {
                delayedEnteredStorage.countDown();
                assertThat(winnerPublished.await(5, TimeUnit.SECONDS)).isTrue();
                throw new IllegalStateException("late staging outage");
            }
            return new MinioStorageService.ObjectInspection(
                    MinioStorageService.ObjectState.MATCHING, 1, plan.entries().get(0).sha256());
        });
        when(jobs.saveAndFlush(job)).thenAnswer(invocation -> {
            if (job.getStatus() == OperationStatus.PREPARED && Boolean.TRUE.equals(job.getRunnable())) {
                job.setStatus(OperationStatus.COMPLETED);
                job.setRunnable(false);
                winnerPublished.countDown();
            }
            return job;
        });
        MarkdownImportIntakeService intake = new MarkdownImportIntakeService(
                jobs, new MarkdownImportIntakeWriteService(jobs, steps, mock(KnowledgeBaseRepository.class)), storage);
        var executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("delayed-intake");
            return thread;
        });
        try {
            var delayed = executor.submit(() -> {
                TenantContext.setTenantId("tenant-a");
                try { return intake.submit(plan, "same-key", "alice"); }
                finally { TenantContext.clear(); }
            });
            assertThat(delayedEnteredStorage.await(5, TimeUnit.SECONDS)).isTrue();
            var winner = executor.submit(() -> {
                Thread.currentThread().setName("winner-intake");
                TenantContext.setTenantId("tenant-a");
                try { return intake.submit(plan, "same-key", "alice"); }
                finally { TenantContext.clear(); }
            });

            assertThat(winner.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(OperationStatus.COMPLETED);
            assertThat(delayed.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(OperationStatus.COMPLETED);
            assertThat(job.getStatus()).isEqualTo(OperationStatus.COMPLETED);
            assertThat(job.getPhase()).isEqualTo(OperationPhase.FORWARD);
            assertThat(job.getRunnable()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    private static MarkdownImportPlan plan(String key, String... entries) throws Exception {
        UUID jobId = MarkdownImportIntakeService.jobId("tenant-a", key);
        return new MarkdownPackageParser().parse(jobId, UUID.randomUUID(), zip(entries).getInputStream());
    }

    private static OperationJob job(MarkdownImportPlan plan, boolean runnable) {
        return OperationJob.builder().id(plan.jobId()).tenantId("tenant-a")
                .operationType(OperationType.MARKDOWN_PACKAGE_IMPORT).aggregateType("KNOWLEDGE_BASE")
                .aggregateId(plan.knowledgeBaseId()).input(plan.toInput()).idempotencyKey("same-key")
                .createdBy("alice").status(OperationStatus.PREPARED).runnable(runnable).build();
    }

    private static MockMultipartFile zip(String... entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int index = 0; index < entries.length; index += 2) {
                zip.putNextEntry(new ZipEntry(entries[index])); zip.write(entries[index + 1].getBytes()); zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", "docs.zip", "application/zip", bytes.toByteArray());
    }
}
