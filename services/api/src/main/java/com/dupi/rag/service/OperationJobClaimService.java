package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationPhase;
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
        Optional<OperationJob> selected = operationJobRepository.claimNextForUpdate(now);
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        OperationJob job = selected.get();
        if (job.getStatus() == OperationStatus.RUNNING && safeAttemptCount(job) >= maxAttempts()) {
            job.setStatus(OperationStatus.FAILED);
            job.setCompletedAt(now);
            job.setLastError("operation_attempt_budget_exhausted");
            job.setNextAttemptAt(null);
            clearLease(job);
            operationJobRepository.saveAndFlush(job);
            return claimNext();
        }
        return Optional.ofNullable(job).map(candidate -> {
            if (!isClaimable(candidate, now)) {
                return null;
            }
            candidate.setStatus(OperationStatus.RUNNING);
            candidate.setClaimToken(UUID.randomUUID());
            candidate.setClaimEpoch(safeClaimEpoch(candidate) + 1);
            candidate.setLeaseExpiresAt(now.plusSeconds(leaseSeconds()));
            candidate.setAttemptCount(safeAttemptCount(candidate) + 1);
            return operationJobRepository.saveAndFlush(candidate);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID jobId, UUID claimToken) {
        OperationJob job = claimed(jobId, claimToken);
        job.setStatus(OperationStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        job.setLastError(null);
        job.setNextAttemptAt(null);
        clearLease(job);
        operationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(OperationExecutionContext context) {
        OperationJob job = claimed(context.jobId(), context.claimToken(), context.claimEpoch());
        job.setStatus(OperationStatus.COMPLETED); job.setCompletedAt(Instant.now()); job.setLastError(null); job.setNextAttemptAt(null); clearLease(job);
        operationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleRetry(UUID jobId, UUID claimToken, String error) {
        OperationJob job = claimed(jobId, claimToken);
        if (safeAttemptCount(job) >= maxAttempts()) {
            job.setStatus(OperationStatus.FAILED);
            job.setCompletedAt(Instant.now());
            job.setLastError(limit(error));
            job.setNextAttemptAt(null);
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
    public void scheduleRetry(OperationExecutionContext context, String error) {
        OperationJob job = claimed(context.jobId(), context.claimToken(), context.claimEpoch());
        scheduleRetryClaimed(job, error);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, UUID claimToken, String error) {
        OperationJob job = claimed(jobId, claimToken);
        job.setStatus(OperationStatus.FAILED);
        job.setCompletedAt(Instant.now());
        job.setLastError(limit(error));
        job.setNextAttemptAt(null);
        clearLease(job);
        operationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(OperationExecutionContext context, String error) {
        OperationJob job = claimed(context.jobId(), context.claimToken(), context.claimEpoch());
        job.setStatus(OperationStatus.FAILED); job.setCompletedAt(Instant.now()); job.setLastError(limit(error)); job.setNextAttemptAt(null); clearLease(job);
        operationJobRepository.save(job);
    }

    private void scheduleRetryClaimed(OperationJob job, String error) {
        if (safeAttemptCount(job) >= maxAttempts()) {
            job.setStatus(OperationStatus.FAILED); job.setCompletedAt(Instant.now()); job.setLastError(limit(error)); job.setNextAttemptAt(null); clearLease(job);
        } else {
            job.setStatus(OperationStatus.RETRY_WAIT); job.setLastError(limit(error)); job.setNextAttemptAt(Instant.now().plus(backoff(safeAttemptCount(job)))); clearLease(job);
        }
        operationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void beginCompensation(OperationExecutionContext context, String error) {
        OperationJob job = claimed(context.jobId(), context.claimToken(), context.claimEpoch());
        job.setPhase(OperationPhase.COMPENSATION);
        job.setStatus(OperationStatus.COMPENSATING);
        job.setLastError(limit(error));
        job.setNextAttemptAt(Instant.now());
        clearLease(job);
        operationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationExecutionContext renewLease(OperationExecutionContext context) {
        OperationJob job = claimed(context.jobId(), context.claimToken(), context.claimEpoch());
        job.setLeaseExpiresAt(Instant.now().plusSeconds(leaseSeconds()));
        operationJobRepository.save(job);
        return new OperationExecutionContext(job.getId(), job.getClaimToken(), safeClaimEpoch(job), job.getPhase());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationJob assertActiveClaim(OperationExecutionContext context) {
        return claimed(context.jobId(), context.claimToken(), context.claimEpoch());
    }

    private OperationJob claimed(UUID jobId, UUID claimToken) {
        return claimed(jobId, claimToken, null);
    }

    private OperationJob claimed(UUID jobId, UUID claimToken, Long claimEpoch) {
        OperationJob job = operationJobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + jobId));
        if (job.getStatus() != OperationStatus.RUNNING || claimToken == null || !claimToken.equals(job.getClaimToken())
                || (claimEpoch != null && claimEpoch.longValue() != safeClaimEpoch(job))
                || job.getLeaseExpiresAt() == null || !job.getLeaseExpiresAt().isAfter(Instant.now())) {
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
