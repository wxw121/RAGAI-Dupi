package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.OperationJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OperationJobClaimServiceTest {
    private final OperationJobRepository jobs = mock(OperationJobRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final OperationJobClaimService service = new OperationJobClaimService(jobs, audit);

    @Test
    void stateBoundaryAuditsAreRecordedOnceAfterTheFencedTransition() {
        OperationJob claimed = currentClaim(OperationPhase.FORWARD, 1, 1);
        OperationExecutionContext context = context(claimed);
        when(jobs.findByIdForUpdate(claimed.getId())).thenReturn(Optional.of(claimed));

        service.complete(context);

        verify(audit).recordOperationInCurrentTransaction(
                claimed.getTenantId(), AuditLogService.OPERATION_COMPLETE, claimed.getId(), "Operation completed");
        assertThatThrownBy(() -> service.complete(context)).isInstanceOf(OperationConflictException.class);
        verify(audit, times(1)).recordOperationInCurrentTransaction(
                claimed.getTenantId(), AuditLogService.OPERATION_COMPLETE, claimed.getId(), "Operation completed");
    }

    @Test
    void retryCompensationAndFailureAuditTheirActualTransitions() {
        OperationJob retrying = currentClaim(OperationPhase.FORWARD, 1, 1);
        when(jobs.findByIdForUpdate(retrying.getId())).thenReturn(Optional.of(retrying));
        service.scheduleRetry(context(retrying), "temporary");
        verify(audit).recordOperationInCurrentTransaction(
                retrying.getTenantId(), AuditLogService.OPERATION_RETRY, retrying.getId(), "temporary");

        OperationJob compensating = currentClaim(OperationPhase.FORWARD, 1, 1);
        when(jobs.findByIdForUpdate(compensating.getId())).thenReturn(Optional.of(compensating));
        service.beginCompensation(context(compensating), "undo");
        verify(audit).recordOperationInCurrentTransaction(
                compensating.getTenantId(), AuditLogService.OPERATION_COMPENSATE, compensating.getId(), "undo");

        OperationJob failed = currentClaim(OperationPhase.FORWARD, 1, 1);
        when(jobs.findByIdForUpdate(failed.getId())).thenReturn(Optional.of(failed));
        service.fail(context(failed), "permanent");
        verify(audit).recordOperationInCurrentTransaction(
                failed.getTenantId(), AuditLogService.OPERATION_FAIL, failed.getId(), "permanent");
    }

    @Test
    void exhaustedRowIsTerminalizedInOneShortClaimCall() {
        ReflectionTestUtils.setField(service, "configuredMaxAttempts", 2);
        OperationJob exhausted = job(OperationStatus.RUNNING, 8, 2);
        exhausted.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        when(jobs.claimNextForUpdate(any())).thenReturn(Optional.of(exhausted));

        OperationClaimResult result = service.claimNext();

        assertThat(result.kind()).isEqualTo(OperationClaimKind.TERMINALIZED);
        assertThat(exhausted.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(exhausted.getPhase()).isEqualTo(OperationPhase.FORWARD);
        assertThat(exhausted.getNextAttemptAt()).isNull();
        verify(jobs, times(1)).claimNextForUpdate(any());
        verify(jobs).saveAndFlush(exhausted);
    }

    @Test
    void compensationStartsWithItsOwnFirstAttemptEvenAfterForwardBudgetWasConsumed() {
        ReflectionTestUtils.setField(service, "configuredMaxAttempts", 2);
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        OperationJob forward = job(OperationStatus.RUNNING, 2, 2);
        forward.setId(id);
        forward.setClaimToken(token);
        forward.setClaimEpoch(4L);
        forward.setRetryEpoch(7L);
        forward.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        when(jobs.findByIdForUpdate(id)).thenReturn(Optional.of(forward));

        service.beginCompensation(context(forward), "rollback");
        when(jobs.claimNextForUpdate(any())).thenReturn(Optional.of(forward));
        when(jobs.saveAndFlush(forward)).thenReturn(forward);
        OperationClaimResult result = service.claimNext();

        assertThat(result.kind()).isEqualTo(OperationClaimKind.CLAIMED);
        assertThat(forward.getPhase()).isEqualTo(OperationPhase.COMPENSATION);
        assertThat(forward.getAttemptCount()).isEqualTo(3);
        assertThat(forward.getPhaseAttemptCount()).isEqualTo(1);
    }

    @Test
    void compensationRetryExhaustsItsOwnBudgetAndPreservesPhase() {
        ReflectionTestUtils.setField(service, "configuredMaxAttempts", 2);
        OperationJob claimed = currentClaim(OperationPhase.COMPENSATION, 9, 2);
        when(jobs.findByIdForUpdate(claimed.getId())).thenReturn(Optional.of(claimed));

        service.scheduleRetry(context(claimed), "down");

        assertThat(claimed.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(claimed.getPhase()).isEqualTo(OperationPhase.COMPENSATION);
        assertThat(claimed.getNextAttemptAt()).isNull();
    }

    @Test
    void compensationCanRetryOnceThenExhaustItsFreshBudget() {
        ReflectionTestUtils.setField(service, "configuredMaxAttempts", 2);
        OperationJob compensation = currentClaim(OperationPhase.FORWARD, 5, 2);
        when(jobs.findByIdForUpdate(compensation.getId())).thenReturn(Optional.of(compensation));
        service.beginCompensation(context(compensation), "undo");

        when(jobs.claimNextForUpdate(any())).thenReturn(Optional.of(compensation));
        when(jobs.saveAndFlush(compensation)).thenReturn(compensation);
        assertThat(service.claimNext().kind()).isEqualTo(OperationClaimKind.CLAIMED);
        service.scheduleRetry(context(compensation), "temporary");
        assertThat(compensation.getStatus()).isEqualTo(OperationStatus.COMPENSATING);
        assertThat(compensation.getNextAttemptAt()).isNotNull();

        assertThat(service.claimNext().kind()).isEqualTo(OperationClaimKind.CLAIMED);
        service.scheduleRetry(context(compensation), "still down");
        assertThat(compensation.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(compensation.getPhase()).isEqualTo(OperationPhase.COMPENSATION);
        assertThat(compensation.getNextAttemptAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = OperationStatus.class, names = {"PREPARED", "RETRY_WAIT", "COMPENSATING"})
    void everyDueClaimableStatusChecksPhaseBudgetBeforeExecution(OperationStatus status) {
        ReflectionTestUtils.setField(service, "configuredMaxAttempts", 2);
        OperationJob exhausted = job(status, 7, 2);
        if (status == OperationStatus.COMPENSATING) {
            exhausted.setPhase(OperationPhase.COMPENSATION);
        }
        when(jobs.claimNextForUpdate(any())).thenReturn(Optional.of(exhausted));

        assertThat(service.claimNext().kind()).isEqualTo(OperationClaimKind.TERMINALIZED);
        assertThat(exhausted.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(exhausted.getPhaseAttemptCount()).isEqualTo(2);
    }

    @Test
    void fullContextIncludingRetryEpochAndPhaseIsFenced() {
        OperationJob claimed = currentClaim(OperationPhase.COMPENSATION, 1, 1);
        claimed.setRetryEpoch(3L);
        when(jobs.findByIdForUpdate(claimed.getId())).thenReturn(Optional.of(claimed));

        assertThatThrownBy(() -> service.renewLease(new OperationExecutionContext(
                claimed.getId(), claimed.getClaimToken(), claimed.getClaimEpoch(), 2, OperationPhase.COMPENSATION)))
                .isInstanceOf(OperationConflictException.class);
        assertThatThrownBy(() -> service.renewLease(new OperationExecutionContext(
                claimed.getId(), claimed.getClaimToken(), claimed.getClaimEpoch(), 3, OperationPhase.FORWARD)))
                .isInstanceOf(OperationConflictException.class);
        verify(jobs, never()).save(any());
    }

    @Test
    void leaseRenewalExtendsCurrentOwnerAndRejectsAnExpiredOwner() {
        OperationJob claimed = currentClaim(OperationPhase.FORWARD, 1, 1);
        Instant previousExpiry = claimed.getLeaseExpiresAt();
        when(jobs.findByIdForUpdate(claimed.getId())).thenReturn(Optional.of(claimed));

        OperationExecutionContext renewed = service.renewLease(context(claimed));

        assertThat(claimed.getLeaseExpiresAt()).isAfter(previousExpiry);
        assertThat(renewed).isEqualTo(context(claimed));
        verify(jobs).save(claimed);

        claimed.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> service.renewLease(context(claimed)))
                .isInstanceOf(OperationConflictException.class);
    }

    @Test
    void domainCompletionAcknowledgmentAcceptsOnlyTheAtomicallyCompletedCurrentEpoch() {
        OperationJob completed = currentClaim(OperationPhase.FORWARD, 1, 1);
        OperationExecutionContext context = context(completed);
        completed.setStatus(OperationStatus.COMPLETED);
        completed.setClaimToken(null);
        completed.setLeaseExpiresAt(null);
        when(jobs.findByIdForUpdate(completed.getId())).thenReturn(Optional.of(completed));

        service.acknowledgeDomainCompletion(context);

        assertThatThrownBy(() -> service.acknowledgeDomainCompletion(new OperationExecutionContext(
                context.jobId(), context.claimToken(), context.claimEpoch() + 1, context.retryEpoch(), context.phase())))
                .isInstanceOf(OperationConflictException.class);
        verify(jobs, never()).save(any());
    }

    private static OperationExecutionContext context(OperationJob job) {
        return new OperationExecutionContext(job.getId(), job.getClaimToken(), job.getClaimEpoch(),
                job.getRetryEpoch(), job.getPhase(), job.getTenantId(), job.getCreatedBy());
    }

    private static OperationJob currentClaim(OperationPhase phase, int attempts, int phaseAttempts) {
        OperationJob job = job(OperationStatus.RUNNING, attempts, phaseAttempts);
        job.setPhase(phase);
        job.setClaimToken(UUID.randomUUID());
        job.setClaimEpoch(1L);
        job.setRetryEpoch(0L);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        return job;
    }

    private static OperationJob job(OperationStatus status, int attempts, int phaseAttempts) {
        return OperationJob.builder().id(UUID.randomUUID()).tenantId("tenant-a")
                .status(status).phase(OperationPhase.FORWARD)
                .attemptCount(attempts).phaseAttemptCount(phaseAttempts).runnable(true)
                .nextAttemptAt(Instant.now()).claimEpoch(0L).retryEpoch(0L).build();
    }
}
