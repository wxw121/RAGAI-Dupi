package com.dupi.rag.service;

import com.dupi.rag.repository.OperationJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/** Schedules bounded compensation for abandoned staged import intakes. */
@Service
@Slf4j
public class OperationStagingRetentionService {
    private final OperationJobRepository jobs;
    private final OperationStagingRetentionPersistenceService persistence;
    private final int retentionHours;
    private final int cleanupLimit;
    private final Clock clock;

    @Autowired
    public OperationStagingRetentionService(
            OperationJobRepository jobs,
            OperationStagingRetentionPersistenceService persistence,
            @Value("${dupi.operations.staging-retention-hours:24}") int retentionHours,
            @Value("${dupi.operations.staging-cleanup-limit:10}") int cleanupLimit
    ) {
        this(jobs, persistence, retentionHours, cleanupLimit, Clock.systemUTC());
    }

    OperationStagingRetentionService(OperationJobRepository jobs,
                                     OperationStagingRetentionPersistenceService persistence,
                                     int retentionHours, int cleanupLimit, Clock clock) {
        this.jobs = jobs;
        this.persistence = persistence;
        this.retentionHours = Math.max(1, retentionHours);
        this.cleanupLimit = Math.max(1, cleanupLimit);
        this.clock = clock;
    }

    @Scheduled(cron = "${dupi.operations.staging-cleanup-cron:0 0 * * * *}")
    public int scheduleExpiredIntakeCleanup() {
        Instant now = clock.instant();
        Instant cutoff = now.minusSeconds(retentionHours * 3600L);
        int scheduled = 0;
        for (var jobId : jobs.findExpiredStagingIntakes(cutoff, PageRequest.of(0, cleanupLimit))) {
            try {
                if (persistence.scheduleCleanup(jobId, cutoff, now)) {
                    scheduled++;
                }
            } catch (RuntimeException failure) {
                log.warn("Failed to schedule expired staging cleanup for operation {}", jobId, failure);
            }
        }
        return scheduled;
    }
}
