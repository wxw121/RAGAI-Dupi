package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStagingAttempt;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStagingAttemptState;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStagingAttemptRepository;
import com.dupi.rag.repository.OperationStepRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OperationStagingPublishTransactionTest {

    @Test
    void recoveryTransfersTheExactAttemptBeforeMakingTheJobRunnable() {
        Fixture fixture = fixture(OperationType.RECOVERY_ARCHIVE_IMPORT);
        String sha = "a".repeat(64);
        var plan = new RecoveryArchiveImportPlan(UUID.randomUUID(), "tenant-a", UUID.randomUUID(),
                null, "embedding", 3, Map.of(), "b".repeat(64), sha, "operator", List.of());
        fixture.job.setAggregateId(plan.knowledgeBaseId());
        fixture.job.setInput(plan.toInput());
        var stage = step(fixture.job.getId(), RecoveryArchiveImportIntakeWriteService.STAGE_STEP,
                OperationStepStatus.PENDING);
        when(fixture.steps.findByJobIdAndStepKey(fixture.job.getId(),
                RecoveryArchiveImportIntakeWriteService.STAGE_STEP)).thenReturn(Optional.of(stage));
        String key = "recovery-staging/" + sha + "/" + fixture.job.getId() + ".zip.attempt-2-" + fixture.lease.token();

        var writes = new RecoveryArchiveImportIntakeWriteService(fixture.jobs, fixture.steps,
                mock(KnowledgeBaseRepository.class), mock(AuditLogService.class), fixture.attemptService);
        writes.completeStageAndPublish(fixture.job.getId(), plan,
                new StoredRecoveryObject("recovery", key, 1, sha, "version"), fixture.lease);

        assertPublished(fixture);
    }

    @Test
    void markdownTransfersTheExactAttemptBeforeMakingTheJobRunnable() {
        Fixture fixture = fixture(OperationType.MARKDOWN_PACKAGE_IMPORT);
        UUID kbId = UUID.randomUUID();
        String sha = "c".repeat(64);
        var entry = new MarkdownImportPlan.Entry("a.md", "text/markdown", 1, sha,
                "markdown-staging/a", null);
        var plan = new MarkdownImportPlan(fixture.job.getId(), kbId, "d".repeat(64),
                List.of(entry), List.of());
        fixture.job.setAggregateId(kbId);
        fixture.job.setInput(plan.toInput());
        var stage = step(fixture.job.getId(), MarkdownImportIntakeWriteService.stageStep(entry),
                OperationStepStatus.COMPLETED);
        when(fixture.steps.findByJobIdAndStepKey(fixture.job.getId(), stage.getStepKey()))
                .thenReturn(Optional.of(stage));

        var writes = new MarkdownImportIntakeWriteService(fixture.jobs, fixture.steps,
                mock(KnowledgeBaseRepository.class), mock(AuditLogService.class), fixture.attemptService);
        writes.publishRunnable(fixture.job.getId(), plan, fixture.lease);

        assertPublished(fixture);
    }

    private static void assertPublished(Fixture fixture) {
        assertThat(fixture.attempt.getState()).isEqualTo(OperationStagingAttemptState.CLEANED);
        assertThat(fixture.job.getRunnable()).isTrue();
        assertThat(fixture.job.getClaimToken()).isNull();
        verify(fixture.attempts).save(fixture.attempt);
    }

    private static Fixture fixture(OperationType type) {
        UUID jobId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        var lease = new OperationStagingLease(jobId, token, 2L);
        var job = OperationJob.builder().id(jobId).tenantId("tenant-a").operationType(type)
                .aggregateType("KNOWLEDGE_BASE").aggregateId(UUID.randomUUID())
                .status(OperationStatus.PREPARED).phase(OperationPhase.FORWARD).runnable(false)
                .idempotencyKey("key").input(Map.of()).attemptCount(0).claimToken(token)
                .claimEpoch(2L).leaseExpiresAt(Instant.now().plusSeconds(60)).createdBy("operator").build();
        var attempt = OperationStagingAttempt.builder().id(UUID.randomUUID()).jobId(jobId)
                .tenantId("tenant-a").stepKey("stage").storageType("MINIO").objectKey("attempt")
                .state(OperationStagingAttemptState.ACTIVE).ownerToken(token).ownerEpoch(2L)
                .leaseExpiresAt(job.getLeaseExpiresAt()).lastActivityAt(Instant.now()).build();
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        OperationStepRepository steps = mock(OperationStepRepository.class);
        OperationStagingAttemptRepository attempts = mock(OperationStagingAttemptRepository.class);
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        when(jobs.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(attempts.findByJobIdAndOwnerEpoch(jobId, 2L)).thenReturn(List.of(attempt));
        return new Fixture(job, attempt, lease, jobs, steps, attempts,
                new OperationStagingAttemptService(jobs, steps, attempts));
    }

    private static OperationStep step(UUID jobId, String key, OperationStepStatus status) {
        return OperationStep.builder().id(UUID.randomUUID()).jobId(jobId).sequenceNumber(1)
                .stepKey(key).stepType("STAGE").resourceRef("staging").status(status)
                .attemptCount(0).build();
    }

    private record Fixture(OperationJob job, OperationStagingAttempt attempt,
                           OperationStagingLease lease, OperationJobRepository jobs,
                           OperationStepRepository steps, OperationStagingAttemptRepository attempts,
                           OperationStagingAttemptService attemptService) { }
}
