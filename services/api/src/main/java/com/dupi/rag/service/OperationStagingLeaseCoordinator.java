package com.dupi.rag.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
class OperationStagingLeaseCoordinator implements AutoCloseable {
    private final OperationStagingLeasePersistence persistence;
    private final Duration duration;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    @Autowired
    OperationStagingLeaseCoordinator(OperationStagingLeasePersistence persistence,
            @Value("${dupi.operations.staging-lease-seconds:60}") long seconds) {
        this(persistence, Duration.ofSeconds(Math.max(1, seconds)), Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "operation-staging-heartbeat");
                    thread.setDaemon(true); return thread;
                }));
    }
    OperationStagingLeaseCoordinator(OperationStagingLeasePersistence persistence, Duration duration,
                                      Clock clock, ScheduledExecutorService scheduler) {
        this.persistence = persistence; this.duration = duration; this.clock = clock; this.scheduler = scheduler;
    }
    OperationStagingLease acquire(java.util.UUID jobId) {
        return persistence.acquire(jobId, clock.instant(), duration);
    }
    OperationStagingHeartbeat start(OperationStagingLease lease) {
        if (!persistence.renew(lease, clock.instant(), duration))
            throw new com.dupi.rag.exception.OperationConflictException("Staging ownership was lost before I/O");
        AtomicBoolean lost = new AtomicBoolean();
        long interval = Math.max(1, duration.toMillis() / 3);
        var task = scheduler.scheduleWithFixedDelay(() -> {
            try { if (!persistence.renew(lease, clock.instant(), duration)) lost.set(true); }
            catch (RuntimeException failure) {
                lost.set(true);
                log.warn("Failed to renew staging lease for {}", lease.jobId(), failure);
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
        return new OperationStagingHeartbeat(task, lost);
    }
    boolean owns(OperationStagingLease lease) { return persistence.owns(lease); }
    @Override @PreDestroy public void close() { scheduler.shutdownNow(); }
}
