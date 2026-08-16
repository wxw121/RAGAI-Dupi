package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class OperationJobRunnerTest {
    private final OperationJobClaimService claimService = mock(OperationJobClaimService.class);
    private final OperationWorkflow workflow = mock(OperationWorkflow.class);

    @Test
    void runnerDispatchesForwardAndCompletesWithFullContext() {
        when(workflow.type()).thenReturn(OperationType.RECOVERY_ARCHIVE_IMPORT);
        when(claimService.claimNext()).thenReturn(OperationClaimResult.claimed(job(OperationPhase.FORWARD)));

        new OperationJobRunner(claimService, List.of(workflow)).runOne();

        verify(workflow).executeForward(argThat(context -> context.retryEpoch() == 3));
        verify(claimService).complete(any(OperationExecutionContext.class));
    }

    @Test
    void runnerDispatchesCompensationAndCanEnterCompensationFromForward() {
        when(workflow.type()).thenReturn(OperationType.RECOVERY_ARCHIVE_IMPORT);
        OperationJob compensation = job(OperationPhase.COMPENSATION);
        when(claimService.claimNext()).thenReturn(OperationClaimResult.claimed(compensation));

        new OperationJobRunner(claimService, List.of(workflow)).runOne();
        verify(workflow).executeCompensation(any(OperationExecutionContext.class));

        reset(workflow, claimService);
        when(workflow.type()).thenReturn(OperationType.RECOVERY_ARCHIVE_IMPORT);
        when(claimService.claimNext()).thenReturn(OperationClaimResult.claimed(job(OperationPhase.FORWARD)));
        doThrow(new CompensateOperationException("undo")).when(workflow)
                .executeForward(any(OperationExecutionContext.class));

        new OperationJobRunner(claimService, List.of(workflow)).runOne();
        verify(claimService).beginCompensation(any(OperationExecutionContext.class), contains("undo"));
    }

    @Test
    void transientFailureMovesJobToRetryWait() {
        when(workflow.type()).thenReturn(OperationType.RECOVERY_ARCHIVE_IMPORT);
        when(claimService.claimNext()).thenReturn(OperationClaimResult.claimed(job(OperationPhase.FORWARD)));
        doThrow(new RetryableOperationException("minio unavailable"))
                .when(workflow).executeForward(any(OperationExecutionContext.class));

        new OperationJobRunner(claimService, List.of(workflow)).runOne();

        verify(claimService).scheduleRetry(any(OperationExecutionContext.class), contains("minio unavailable"));
    }

    @Test
    void disabledRunnerDoesNotClaimAndBatchContinuesPastBoundedCleanup() {
        when(workflow.type()).thenReturn(OperationType.RECOVERY_ARCHIVE_IMPORT);
        new OperationJobRunner(claimService, List.of(workflow), 2, 2, false).runScheduled();
        verifyNoInteractions(claimService);

        OperationJob first = job(OperationPhase.FORWARD);
        OperationJob exhausted = job(OperationPhase.FORWARD);
        OperationJob second = job(OperationPhase.FORWARD);
        when(claimService.claimNext()).thenReturn(
                OperationClaimResult.terminalized(exhausted),
                OperationClaimResult.claimed(first),
                OperationClaimResult.claimed(second));

        new OperationJobRunner(claimService, List.of(workflow), 2, 2, true).runScheduled();

        verify(workflow, times(2)).executeForward(any(OperationExecutionContext.class));
        verify(claimService, times(3)).claimNext();
    }

    @Test
    void duplicateWorkflowHandlersAreRejectedAtConstruction() {
        OperationWorkflow duplicate = mock(OperationWorkflow.class);
        when(workflow.type()).thenReturn(OperationType.RECOVERY_ARCHIVE_IMPORT);
        when(duplicate.type()).thenReturn(OperationType.RECOVERY_ARCHIVE_IMPORT);

        assertThatThrownBy(() -> new OperationJobRunner(claimService, List.of(workflow, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate workflow handler");
    }

    private OperationJob job(OperationPhase phase) {
        return OperationJob.builder()
                .id(UUID.randomUUID())
                .claimToken(UUID.randomUUID())
                .claimEpoch(4L)
                .retryEpoch(3L)
                .phase(phase)
                .leaseExpiresAt(Instant.now().plusSeconds(30))
                .operationType(OperationType.RECOVERY_ARCHIVE_IMPORT)
                .status(OperationStatus.RUNNING)
                .build();
    }
}
