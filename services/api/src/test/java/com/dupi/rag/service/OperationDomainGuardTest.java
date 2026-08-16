package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.OperationJobRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationDomainGuardTest {
    private final OperationJobRepository jobs = mock(OperationJobRepository.class);
    private final OperationDomainGuard guard = new OperationDomainGuard(jobs);

    @Test
    void rejectsEveryStaleOwnershipDimension() {
        OperationJob job = current();
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        List<OperationExecutionContext> stale = List.of(
                new OperationExecutionContext(UUID.randomUUID(), job.getClaimToken(), 4, 3, job.getPhase()),
                new OperationExecutionContext(job.getId(), UUID.randomUUID(), 4, 3, job.getPhase()),
                new OperationExecutionContext(job.getId(), job.getClaimToken(), 3, 3, job.getPhase()),
                new OperationExecutionContext(job.getId(), job.getClaimToken(), 4, 2, job.getPhase()),
                new OperationExecutionContext(job.getId(), job.getClaimToken(), 4, 3, OperationPhase.COMPENSATION)
        );

        assertThatThrownBy(() -> guard.assertActive(stale.get(0)))
                .isInstanceOf(com.dupi.rag.exception.ResourceNotFoundException.class);
        stale.stream().skip(1).forEach(context ->
                assertThatThrownBy(() -> guard.assertActive(context)).isInstanceOf(OperationConflictException.class));

        job.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> guard.assertActive(context(job))).isInstanceOf(OperationConflictException.class);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        job.setStatus(OperationStatus.RETRY_WAIT);
        assertThatThrownBy(() -> guard.assertActive(context(job))).isInstanceOf(OperationConflictException.class);
    }

    private static OperationExecutionContext context(OperationJob job) {
        return new OperationExecutionContext(job.getId(), job.getClaimToken(), job.getClaimEpoch(),
                job.getRetryEpoch(), job.getPhase());
    }

    private static OperationJob current() {
        return OperationJob.builder().id(UUID.randomUUID()).status(OperationStatus.RUNNING)
                .phase(OperationPhase.FORWARD).claimToken(UUID.randomUUID()).claimEpoch(4L).retryEpoch(3L)
                .leaseExpiresAt(Instant.now().plusSeconds(30)).build();
    }
}
