package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.*;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.OperationJobRepository;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationStagingLeasePersistenceTest {
    @Test
    void oldReopenedJobGetsFreshOwnerAndEligibilityLease() {
        UUID id = UUID.randomUUID();
        OperationJob job = OperationJob.builder().id(id).status(OperationStatus.PREPARED)
                .phase(OperationPhase.FORWARD).runnable(false).claimEpoch(7L).build();
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        when(jobs.findByIdForUpdate(id)).thenReturn(Optional.of(job));
        Instant now = Instant.parse("2026-08-25T10:00:00Z");

        OperationStagingLease lease = new OperationStagingLeasePersistence(jobs)
                .acquire(id, now, Duration.ofSeconds(60));

        assertThat(lease.epoch()).isEqualTo(8);
        assertThat(job.getClaimToken()).isEqualTo(lease.token());
        assertThat(job.getLeaseExpiresAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void liveOwnerCannotBeReplaced() {
        UUID id = UUID.randomUUID(); Instant now = Instant.now();
        OperationJob job = OperationJob.builder().id(id).status(OperationStatus.PREPARED)
                .phase(OperationPhase.FORWARD).runnable(false).claimToken(UUID.randomUUID())
                .leaseExpiresAt(now.plusSeconds(1)).build();
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        when(jobs.findByIdForUpdate(id)).thenReturn(Optional.of(job));
        assertThatThrownBy(() -> new OperationStagingLeasePersistence(jobs)
                .acquire(id, now, Duration.ofSeconds(60)))
                .isInstanceOf(OperationConflictException.class);
    }
}
