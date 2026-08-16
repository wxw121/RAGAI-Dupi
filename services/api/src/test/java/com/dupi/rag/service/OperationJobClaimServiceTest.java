package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.OperationJobRepository;
import org.junit.jupiter.api.Test;
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
    private final OperationJobClaimService service = new OperationJobClaimService(jobs);

    @Test
    void expiredAttemptAtBudgetIsTerminalizedBeforeClaimingTheNextJob() {
        ReflectionTestUtils.setField(service, "configuredMaxAttempts", 2);
        OperationJob exhausted = job(OperationStatus.RUNNING, 2);
        exhausted.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        OperationJob next = job(OperationStatus.PREPARED, 0);
        when(jobs.claimNextForUpdate(any())).thenReturn(Optional.of(exhausted), Optional.of(next));
        when(jobs.saveAndFlush(next)).thenReturn(next);

        assertThat(service.claimNext()).containsSame(next);
        assertThat(exhausted.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(exhausted.getNextAttemptAt()).isNull();
        assertThat(next.getClaimToken()).isNotNull();
        verify(jobs).saveAndFlush(exhausted);
    }

    @Test
    void renewLeaseRejectsAnOldTokenWithoutPersisting() {
        UUID id = UUID.randomUUID();
        OperationJob claimed = job(OperationStatus.RUNNING, 1);
        claimed.setId(id); claimed.setClaimToken(UUID.randomUUID()); claimed.setClaimEpoch(3L);
        claimed.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        when(jobs.findByIdForUpdate(id)).thenReturn(Optional.of(claimed));
        OperationExecutionContext stale = new OperationExecutionContext(id, UUID.randomUUID(), 2, OperationPhase.FORWARD);

        assertThatThrownBy(() -> service.renewLease(stale)).isInstanceOf(OperationConflictException.class);
        verify(jobs, never()).save(any());
    }

    @Test
    void compensationRetryRetainsItsPhaseAndTerminalFailureHasNoNextAttempt() {
        UUID id = UUID.randomUUID(); UUID token = UUID.randomUUID();
        OperationJob claimed = job(OperationStatus.RUNNING, 5);
        claimed.setId(id); claimed.setClaimToken(token); claimed.setClaimEpoch(1L);
        claimed.setLeaseExpiresAt(Instant.now().plusSeconds(30)); claimed.setPhase(OperationPhase.COMPENSATION);
        when(jobs.findByIdForUpdate(id)).thenReturn(Optional.of(claimed));

        service.scheduleRetry(new OperationExecutionContext(id, token, 1, OperationPhase.COMPENSATION), "down");

        assertThat(claimed.getPhase()).isEqualTo(OperationPhase.COMPENSATION);
        assertThat(claimed.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(claimed.getNextAttemptAt()).isNull();
    }

    private static OperationJob job(OperationStatus status, int attempts) {
        return OperationJob.builder().id(UUID.randomUUID()).status(status).phase(OperationPhase.FORWARD)
                .attemptCount(attempts).runnable(true).nextAttemptAt(Instant.now()).claimEpoch(0L).build();
    }
}
