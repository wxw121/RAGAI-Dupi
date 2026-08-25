package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStagingAttempt;
import com.dupi.rag.domain.enums.*;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStagingAttemptRepository;
import com.dupi.rag.repository.OperationStepRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
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

        OperationStagingLease lease = new OperationStagingLeasePersistence(
                jobs, mock(OperationStagingAttemptRepository.class))
                .acquire(id, now, Duration.ofSeconds(60));

        assertThat(lease.epoch()).isEqualTo(8);
        assertThat(job.getClaimToken()).isEqualTo(lease.token());
        assertThat(job.getLeaseExpiresAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void liveOwnerCannotBeReplaced() {
        UUID id = UUID.randomUUID(); Instant now = Instant.now().plusSeconds(3600);
        OperationJob job = OperationJob.builder().id(id).status(OperationStatus.PREPARED)
                .phase(OperationPhase.FORWARD).runnable(false).claimToken(UUID.randomUUID())
                .leaseExpiresAt(now.plusSeconds(1)).build();
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        when(jobs.findByIdForUpdate(id)).thenReturn(Optional.of(job));
        assertThatThrownBy(() -> new OperationStagingLeasePersistence(
                jobs, mock(OperationStagingAttemptRepository.class))
                .acquire(id, now, Duration.ofSeconds(60)))
                .isInstanceOf(OperationConflictException.class);
    }

    @Test
    void takeoverMovesOnlyTheExpiredOwnersActiveAttemptToCleanupPending() {
        UUID id = UUID.randomUUID(); Instant now = Instant.now();
        UUID oldToken = UUID.randomUUID();
        OperationJob job = OperationJob.builder().id(id).status(OperationStatus.PREPARED)
                .phase(OperationPhase.FORWARD).runnable(false).claimToken(oldToken)
                .claimEpoch(1L).leaseExpiresAt(now.minusSeconds(1)).build();
        OperationStagingAttempt oldActive = OperationStagingAttempt.builder().id(UUID.randomUUID())
                .jobId(id).ownerToken(oldToken).ownerEpoch(1L)
                .storageType("MINIO").objectKey("shared-stage.attempt-1")
                .state(OperationStagingAttemptState.ACTIVE).leaseExpiresAt(now.minusSeconds(1)).build();
        OperationStagingAttempt otherOwner = OperationStagingAttempt.builder().id(UUID.randomUUID())
                .jobId(id).ownerToken(UUID.randomUUID()).ownerEpoch(1L)
                .state(OperationStagingAttemptState.ACTIVE).leaseExpiresAt(now.minusSeconds(1)).build();
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        OperationStagingAttemptRepository attempts = mock(OperationStagingAttemptRepository.class);
        when(jobs.findByIdForUpdate(id)).thenReturn(Optional.of(job));
        when(attempts.findByJobIdAndOwnerEpoch(id, 1L)).thenReturn(List.of(oldActive, otherOwner));

        OperationStagingLease next = new OperationStagingLeasePersistence(jobs, attempts)
                .acquire(id, now, Duration.ofSeconds(60));

        assertThat(next.epoch()).isEqualTo(2);
        assertThat(oldActive.getState()).isEqualTo(OperationStagingAttemptState.CLEANUP_PENDING);
        assertThat(oldActive.getLeaseExpiresAt()).isEqualTo(now);
        assertThat(otherOwner.getState()).isEqualTo(OperationStagingAttemptState.ACTIVE);
        verify(attempts).save(oldActive);
        verify(attempts, never()).save(otherOwner);

        OperationStagingAttempt current = OperationStagingAttempt.builder().id(UUID.randomUUID())
                .jobId(id).ownerToken(next.token()).ownerEpoch(next.epoch())
                .state(OperationStagingAttemptState.ACTIVE)
                .leaseExpiresAt(now.plusSeconds(60)).build();
        when(attempts.findByJobIdAndOwnerEpoch(id, next.epoch())).thenReturn(List.of(current));
        OperationStagingAttemptService staging = new OperationStagingAttemptService(
                jobs, mock(OperationStepRepository.class), attempts);
        staging.transferToPublished(next);
        assertThat(current.getState()).isEqualTo(OperationStagingAttemptState.CLEANED);
        assertThatThrownBy(() -> staging.transferToPublished(
                new OperationStagingLease(id, oldToken, 1)))
                .isInstanceOf(OperationConflictException.class);

        when(attempts.findCleanupPending(PageRequest.of(0, 1))).thenReturn(List.of(oldActive.getId()));
        when(attempts.findByIdForUpdate(oldActive.getId())).thenReturn(Optional.of(oldActive));
        MinioStorageService minio = mock(MinioStorageService.class);
        when(minio.existsChecked(oldActive.getObjectKey())).thenReturn(true);
        OperationStagingCleanupService cleanup = new OperationStagingCleanupService(
                attempts, new OperationStagingCleanupPersistence(attempts), minio,
                mock(RecoveryStorageService.class), 1, Duration.ofMinutes(5),
                Clock.fixed(now, ZoneOffset.UTC));

        assertThat(cleanup.cleanupPending()).isOne();
        assertThat(oldActive.getState()).isEqualTo(OperationStagingAttemptState.CLEANED);
        verify(minio).deleteChecked(oldActive.getObjectKey());
    }
}
