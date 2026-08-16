package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.OperationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Short, fenced transactions around external workflow execution. */
@Service
@RequiredArgsConstructor
public class OperationJobClaimService {

    private static final int MAX_ERROR_LENGTH = 2_000;
    private final OperationJobRepository operationJobRepository;

    @Value("${dupi.operations.lease-seconds:60}")
    private int configuredLeaseSeconds;

    @Value("${dupi.operations.max-attempts:5}")
    private int configuredMaxAttempts;

    /** PostgreSQL locks one due row with SKIP LOCKED; expired leases are reclaimed with a new fence. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OperationJob> claimNext() {
        Instant now = Instant.now();
        return operationJobRepository.claimNextForUpdate(now).map(job -> {
            if (!isClaimable(job, now)) {
                return null;
            }
            job.setStatus(OperationStatus.RUNNING);
            job.setClaimToken(UUID.randomUUID());
            job.setClaimEpoch(safeClaimEpoch(job) + 1);
            job.setLeaseExpiresAt(now.plusSeconds(leaseSeconds()));
            job.setAttemptCount(safeAttemptCount(job) + 1);
            return operationJobRepository.saveAndFlush(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID jobId, UUID claimToken) {
        OperationJob job = claimed(jobId, claimToken);
        job.setStatus(OperationStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        job.setLastError(null);
        clearLease(job);
        operationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleRetry(UUID jobId, UUID claimToken, String error) {
        OperationJob job = claimed(jobId, claimToken);
        if (safeAttemptCount(job) >= maxAttempts()) {
            job.setStatus(OperationStatus.FAILED);
            job.setCompletedAt(Instant.now());
            job.setLastError(limit(error));
            clearLease(job);
        } else {
            job.setStatus(OperationStatus.RETRY_WAIT);
            job.setLastError(limit(error));
            job.setNextAttemptAt(Instant.now().plus(backoff(safeAttemptCount(job))));
            clearLease(job);
        }
        operationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, UUID claimToken, String error) {
        OperationJob job = claimed(jobId, claimToken);
        job.setStatus(OperationStatus.FAILED);
        job.setCompletedAt(Instant.now());
        job.setLastError(limit(error));
        clearLease(job);
        operationJobRepository.save(job);
    }

    private OperationJob claimed(UUID jobId, UUID claimToken) {
        OperationJob job = operationJobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + jobId));
        if (job.getStatus() != OperationStatus.RUNNING || claimToken == null || !claimToken.equals(job.getClaimToken())) {
            throw new OperationConflictException("Operation claim is no longer current");
        }
        return job;
    }

    private boolean isClaimable(OperationJob job, Instant now) {
        return job.getStatus() == OperationStatus.PREPARED
                || job.getStatus() == OperationStatus.RETRY_WAIT
                || job.getStatus() == OperationStatus.COMPENSATING
                || (job.getStatus() == OperationStatus.RUNNING
                && job.getLeaseExpiresAt() != null
                && !job.getLeaseExpiresAt().isAfter(now));
    }

    private void clearLease(OperationJob job) {
        job.setClaimToken(null);
        job.setLeaseExpiresAt(null);
    }

    private int safeAttemptCount(OperationJob job) {
        return job.getAttemptCount() == null ? 0 : job.getAttemptCount();
    }

    private long safeClaimEpoch(OperationJob job) {
        return job.getClaimEpoch() == null ? 0L : job.getClaimEpoch();
    }

    private int leaseSeconds() {
        return configuredLeaseSeconds > 0 ? configuredLeaseSeconds : 60;
    }

    private int maxAttempts() {
        return configuredMaxAttempts > 0 ? configuredMaxAttempts : 5;
    }

    private Duration backoff(int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 7);
        return Duration.ofSeconds(Math.min(3_600, 30L * (1L << exponent)));
    }

    private String limit(String error) {
        if (error == null || error.isBlank()) {
            return "operation_failed";
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
