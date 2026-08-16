package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationJobRunnerTest {

    private final UUID jobId = UUID.randomUUID();
    private final OperationJobClaimService claimService = mock(OperationJobClaimService.class);
    private final OperationWorkflow recoveryWorkflow = mock(OperationWorkflow.class);

    @Test
    void runnerDelegatesClaimedJobToItsDomainWorkflowAndCompletesIt() {
        when(recoveryWorkflow.type()).thenReturn(OperationType.RECOVERY_ARCHIVE_IMPORT);
        when(claimService.claimNext()).thenReturn(Optional.of(job(OperationType.RECOVERY_ARCHIVE_IMPORT)));

        new OperationJobRunner(claimService, List.of(recoveryWorkflow)).runOne();

        verify(recoveryWorkflow).executeForward(any(OperationExecutionContext.class));
        verify(claimService).complete(any(OperationExecutionContext.class));
    }

    @Test
    void transientFailureMovesJobToRetryWait() {
        when(recoveryWorkflow.type()).thenReturn(OperationType.RECOVERY_ARCHIVE_IMPORT);
        when(claimService.claimNext()).thenReturn(Optional.of(job(OperationType.RECOVERY_ARCHIVE_IMPORT)));
        doThrow(new RetryableOperationException("minio unavailable"))
                .when(recoveryWorkflow).executeForward(any(OperationExecutionContext.class));

        new OperationJobRunner(claimService, List.of(recoveryWorkflow)).runOne();

        verify(claimService).scheduleRetry(any(OperationExecutionContext.class), contains("minio unavailable"));
    }

    @Test
    void unknownOperationTypeFailsWithoutInvokingAnUnrelatedWorkflow() {
        when(recoveryWorkflow.type()).thenReturn(OperationType.RECOVERY_ARCHIVE_IMPORT);
        when(claimService.claimNext()).thenReturn(Optional.of(job(OperationType.KNOWLEDGE_BASE_DELETE)));

        new OperationJobRunner(claimService, List.of(recoveryWorkflow)).runOne();

        verify(claimService).fail(jobId, jobId, "No workflow registered for operation type KNOWLEDGE_BASE_DELETE");
    }

    @Test
    void duplicateWorkflowHandlersAreRejectedAtConstruction() {
        OperationWorkflow duplicate = mock(OperationWorkflow.class);
        when(recoveryWorkflow.type()).thenReturn(OperationType.RECOVERY_ARCHIVE_IMPORT);
        when(duplicate.type()).thenReturn(OperationType.RECOVERY_ARCHIVE_IMPORT);

        assertThatThrownBy(() -> new OperationJobRunner(claimService, List.of(recoveryWorkflow, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate workflow handler");
    }

    private OperationJob job(OperationType type) {
        return OperationJob.builder()
                .id(jobId)
                .claimToken(jobId)
                .operationType(type)
                .status(OperationStatus.RUNNING)
                .build();
    }
}
