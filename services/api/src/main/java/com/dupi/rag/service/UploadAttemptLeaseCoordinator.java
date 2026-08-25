package com.dupi.rag.service;

import com.dupi.rag.config.UploadQuotaProperties;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.exception.OperationConflictException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps a durable upload-writer fence alive without holding a database transaction across MinIO I/O. */
@Service
@Slf4j
final class UploadAttemptLeaseCoordinator implements AutoCloseable {
    private final UploadQuotaService quota;
    private final Duration leaseDuration;
    private final ScheduledExecutorService scheduler;

    @Autowired
    UploadAttemptLeaseCoordinator(UploadQuotaService quota, UploadQuotaProperties properties) {
        this(quota, Duration.ofSeconds(Math.max(1L, properties.getAttemptLeaseSeconds())),
                Executors.newScheduledThreadPool(1, runnable -> {
                    Thread thread = new Thread(runnable, "upload-attempt-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    UploadAttemptLeaseCoordinator(
            UploadQuotaService quota,
            Duration leaseDuration,
            ScheduledExecutorService scheduler) {
        this.quota = quota;
        this.leaseDuration = leaseDuration;
        this.scheduler = scheduler;
    }

    UploadAttemptLease acquire(UploadQuotaReservation reservation) {
        return quota.acquireWriterLease(reservation);
    }

    UploadAttemptHeartbeat start(UploadAttemptLease lease) {
        if (!quota.renewWriterLease(lease)) {
            throw new OperationConflictException(
                    "Upload writer ownership was lost before object I/O started");
        }
        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        long intervalMillis = Math.max(1L, leaseDuration.toMillis() / 3L);
        var task = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!quota.renewWriterLease(lease)) {
                    ownershipLost.set(true);
                }
            } catch (RuntimeException failure) {
                log.warn("Failed to renew upload writer lease for reservation {}",
                        lease.reservationId(), failure);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return new UploadAttemptHeartbeat(task, ownershipLost);
    }

    boolean owns(UploadAttemptLease lease) {
        return quota.ownsWriterLease(lease);
    }

    @Override
    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
    }
}
