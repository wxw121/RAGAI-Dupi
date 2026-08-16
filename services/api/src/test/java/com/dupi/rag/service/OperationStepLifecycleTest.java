package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.repository.OperationStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OperationStepLifecycleTest {
    private final OperationStepRepository steps = mock(OperationStepRepository.class);
    private final OperationDomainGuard guard = mock(OperationDomainGuard.class);
    private final OperationStepWriteService service = new OperationStepWriteService(steps, guard);
    private final UUID jobId = UUID.randomUUID();
    private final OperationExecutionContext context = new OperationExecutionContext(
            jobId, UUID.randomUUID(), 1, 2, OperationPhase.FORWARD);

    @BeforeEach
    void returnSavedStep() {
        when(steps.saveAndFlush(any(OperationStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void retryWaitRunningRetryAndFailureMaintainConsistentTimestamps() {
        Instant retryAt = Instant.now().plusSeconds(120);
        OperationStep step = OperationStep.builder().jobId(jobId).stepKey("store")
                .status(OperationStepStatus.RETRY_WAIT).startedAt(Instant.now().minusSeconds(30))
                .completedAt(Instant.now().minusSeconds(20)).lastError("old")
                .nextAttemptAt(Instant.now().plusSeconds(5)).build();
        when(steps.findByJobIdAndStepKey(jobId, "store")).thenReturn(Optional.of(step));

        service.startStep(context, "store");
        assertThat(step.getStatus()).isEqualTo(OperationStepStatus.RUNNING);
        assertThat(step.getStartedAt()).isNotNull();
        assertThat(step.getCompletedAt()).isNull();
        assertThat(step.getLastError()).isNull();
        assertThat(step.getNextAttemptAt()).isNull();

        service.retryStep(context, "store", "down", retryAt);
        assertThat(step.getStatus()).isEqualTo(OperationStepStatus.RETRY_WAIT);
        assertThat(step.getStartedAt()).isNull();
        assertThat(step.getCompletedAt()).isNull();
        assertThat(step.getLastError()).isEqualTo("down");
        assertThat(step.getNextAttemptAt()).isEqualTo(retryAt);

        service.startStep(context, "store");
        service.failStep(context, "store", "still down");
        assertThat(step.getStatus()).isEqualTo(OperationStepStatus.FAILED);
        assertThat(step.getCompletedAt()).isNotNull();
        assertThat(step.getNextAttemptAt()).isNull();
        verify(guard, times(4)).assertActive(context);
    }

    @Test
    void recordStepIsGuardedAndUsesContextRetryEpoch() {
        when(steps.findByJobIdAndStepKey(jobId, "store")).thenReturn(Optional.empty());
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of());

        OperationStep step = service.recordStep(context, " store ", " STORE ", "private-ref");

        assertThat(step.getStepKey()).isEqualTo("store");
        assertThat(step.getRetryEpoch()).isEqualTo(2L);
        assertThat(step.getNextAttemptAt()).isNotNull();
        verify(guard).assertActive(context);
        verify(steps).saveAndFlush(step);
    }

    @Test
    void completeAndCompensateClearRetrySchedule() {
        OperationStep step = OperationStep.builder().jobId(jobId).stepKey("store")
                .status(OperationStepStatus.RUNNING).nextAttemptAt(Instant.now()).build();
        when(steps.findByJobIdAndStepKey(jobId, "store")).thenReturn(Optional.of(step));

        service.completeStep(context, "store");
        assertThat(step.getStatus()).isEqualTo(OperationStepStatus.COMPLETED);
        assertThat(step.getNextAttemptAt()).isNull();

        step.setNextAttemptAt(Instant.now());
        service.compensateStep(context, "store");
        assertThat(step.getStatus()).isEqualTo(OperationStepStatus.COMPENSATED);
        assertThat(step.getNextAttemptAt()).isNull();
    }

    @Test
    void compensationCanReconcileEveryForwardStepState() {
        for (OperationStepStatus status : List.of(OperationStepStatus.PENDING,
                OperationStepStatus.RUNNING, OperationStepStatus.RETRY_WAIT,
                OperationStepStatus.FAILED, OperationStepStatus.COMPLETED)) {
            String key = "cleanup-" + status;
            OperationStep step = OperationStep.builder().jobId(jobId).stepKey(key)
                    .status(status).nextAttemptAt(Instant.now()).build();
            when(steps.findByJobIdAndStepKey(jobId, key)).thenReturn(Optional.of(step));

            service.compensateStep(context, key);

            assertThat(step.getStatus()).isEqualTo(OperationStepStatus.COMPENSATED);
            assertThat(step.getNextAttemptAt()).isNull();
        }
    }

    @Test
    void staleOwnerCannotReachRecordStepRepositoryWrites() {
        doThrow(new com.dupi.rag.exception.OperationConflictException("stale"))
                .when(guard).assertActive(context);

        assertThatThrownBy(() -> service.recordStep(context, "store", "STORE", "ref"))
                .isInstanceOf(com.dupi.rag.exception.OperationConflictException.class);
        verifyNoInteractions(steps);
    }
}
