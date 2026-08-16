package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OperationStepLifecycleTest {
    @Test
    void terminalStepTransitionsClearRetryTimeAndContextRetryUsesProvidedJobTime() {
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        OperationStepRepository steps = mock(OperationStepRepository.class);
        OperationJobWriteService writes = mock(OperationJobWriteService.class);
        OperationJobClaimService claims = mock(OperationJobClaimService.class);
        OperationJobService service = new OperationJobService(jobs, steps, writes, claims);
        UUID jobId = UUID.randomUUID();
        OperationExecutionContext context = new OperationExecutionContext(jobId, UUID.randomUUID(), 1, OperationPhase.FORWARD);
        OperationStep step = OperationStep.builder().jobId(jobId).stepKey("store").status(OperationStepStatus.RUNNING)
                .nextAttemptAt(Instant.now().plusSeconds(99)).build();
        when(steps.findByJobIdAndStepKey(jobId, "store")).thenReturn(Optional.of(step));
        Instant jobRetryAt = Instant.now().plusSeconds(120);

        service.retryStep(context, "store", "down", jobRetryAt);
        assertThat(step.getNextAttemptAt()).isEqualTo(jobRetryAt);
        step.setStatus(OperationStepStatus.RUNNING);
        service.failStep(context, "store", "down");

        assertThat(step.getStatus()).isEqualTo(OperationStepStatus.FAILED);
        assertThat(step.getNextAttemptAt()).isNull();
    }
}
