package com.dupi.rag.service;

import com.dupi.rag.domain.entity.UploadQuotaReservation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadAttemptLeaseCoordinatorTest {

    @Test
    void startSynchronouslyConfirmsOwnershipBeforeRemoteIoCanBegin() {
        UploadQuotaService quota = mock(UploadQuotaService.class);
        UploadAttemptLease lease = new UploadAttemptLease(
                UUID.randomUUID(), UUID.randomUUID(), "upload-writer:test");
        when(quota.renewWriterLease(lease)).thenReturn(false);

        var executor = Executors.newSingleThreadScheduledExecutor();
        try (UploadAttemptLeaseCoordinator coordinator = new UploadAttemptLeaseCoordinator(
                quota, Duration.ofSeconds(30), executor)) {
            assertThatThrownBy(() -> coordinator.start(lease))
                    .isInstanceOf(com.dupi.rag.exception.OperationConflictException.class)
                    .hasMessageContaining("ownership");
        }

        verify(quota).renewWriterLease(lease);
    }

    @Test
    void stalledUploadRenewsDurableWriterOwnershipPastTheOriginalLease() throws Exception {
        UploadQuotaService quota = mock(UploadQuotaService.class);
        UploadQuotaReservation reservation = UploadQuotaReservation.builder()
                .id(UUID.randomUUID()).attemptId(UUID.randomUUID()).build();
        UploadAttemptLease lease = new UploadAttemptLease(
                reservation.getId(), reservation.getAttemptId(), "upload-writer:test");
        CountDownLatch renewedTwice = new CountDownLatch(2);
        when(quota.acquireWriterLease(reservation)).thenReturn(lease);
        when(quota.renewWriterLease(lease)).thenAnswer(call -> {
            renewedTwice.countDown();
            return true;
        });
        when(quota.ownsWriterLease(lease)).thenReturn(true);

        var executor = Executors.newSingleThreadScheduledExecutor();
        try (UploadAttemptLeaseCoordinator coordinator = new UploadAttemptLeaseCoordinator(
                quota, Duration.ofMillis(90), executor)) {
            assertThat(coordinator.acquire(reservation)).isEqualTo(lease);
            try (UploadAttemptHeartbeat ignored = coordinator.start(lease)) {
                assertThat(renewedTwice.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(coordinator.owns(lease)).isTrue();
            }
        }

        verify(quota, atLeast(2)).renewWriterLease(lease);
        verify(quota).ownsWriterLease(lease);
    }
}
