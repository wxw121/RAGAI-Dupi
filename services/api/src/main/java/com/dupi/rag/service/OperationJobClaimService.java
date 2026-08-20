package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationPhase;
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

    /**
     * Processes at most one selected row. The caller owns cleanup/batch bounds, so every invocation
     * remains a short independent transaction and never recurses while holding row locks.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationClaimResult claimNext() {
        Instant now = Instant.now();
        Optional<OperationJob> selected = operationJobRepository.claimNextForUpdate(now);
        if (selected.isEmpty()) {
            return OperationClaimResult.none();
        }
        OperationJob job = selected.get();
        if (!isClaimable(job, now)) {
            return OperationClaimResult.none();
        }
        if (safePhaseAttemptCount(job) >= maxAttempts()) {
            job.setStatus(OperationStatus.FAILED);
            job.setCompletedAt(now);
            job.setLastError("operation_attempt_budget_exhausted");
            job.setNextAttemptAt(null);
            clearLease(job);
            return OperationClaimResult.terminalized(operationJobRepository.saveAndFlush(job));
        }
        job.setStatus(OperationStatus.RUNNING);
        job.setClaimToken(UUID.randomUUID());
        job.setClaimEpoch(safeClaimEpoch(job) + 1);
        job.setLeaseExpiresAt(now.plusSeconds(leaseSeconds()));
        job.setAttemptCount(safeAttemptCount(job) + 1);
        job.setPhaseAttemptCount(safePhaseAttemptCount(job) + 1);
        return OperationClaimResult.claimed(operationJobRepository.saveAndFlush(job));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(OperationExecutionContext context) {
        OperationJob job = claimed(context);
        job.setStatus(OperationStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        job.setLastError(null);
        job.setNextAttemptAt(null);
        clearLease(job);
        operationJobRepository.save(job);
    }

    /** Verifies a domain-owned atomic publish already committed the terminal operation row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void acknowledgeDomainCompletion(OperationExecutionContext context) {
        OperationJob job = operationJobRepository.findByIdForUpdate(context.jobId())
                .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + context.jobId()));
        if (job.getStatus() != OperationStatus.COMPLETED
                || context.claimEpoch() != safeClaimEpoch(job)
                || context.retryEpoch() != safeRetryEpoch(job)
                || context.phase() != job.getPhase()) {
            throw new OperationConflictException("Domain workflow did not atomically complete its current claim");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleRetry(OperationExecutionContext context, String error) {
        OperationJob job = claimed(context);
        if (safePhaseAttemptCount(job) >= maxAttempts()) {
            job.setStatus(OperationStatus.FAILED);
            job.setCompletedAt(Instant.now());
            job.setLastError(limit(error));
            job.setNextAttemptAt(null);
        } else {
            job.setStatus(job.getPhase() == OperationPhase.COMPENSATION
                    ? OperationStatus.COMPENSATING : OperationStatus.RETRY_WAIT);
            job.setLastError(limit(error));
            job.setNextAttemptAt(Instant.now().plus(backoff(safePhaseAttemptCount(job))));
        }
        clearLease(job);
        operationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(OperationExecutionContext context, String error) {
        OperationJob job = claimed(context);
        job.setStatus(OperationStatus.FAILED);
        job.setCompletedAt(Instant.now());
        job.setLastError(limit(error));
        job.setNextAttemptAt(null);
        clearLease(job);
        operationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void beginCompensation(OperationExecutionContext context, String error) {
        OperationJob job = claimed(context);
        job.setPhase(OperationPhase.COMPENSATION);
        job.setPhaseAttemptCount(0);
        job.setStatus(OperationStatus.COMPENSATING);
        job.setLastError(limit(error));
        job.setNextAttemptAt(Instant.now());
        clearLease(job);
        operationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationExecutionContext renewLease(OperationExecutionContext context) {
        OperationJob job = claimed(context);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(leaseSeconds()));
        operationJobRepository.save(job);
        return context(job);
    }

    private OperationJob claimed(OperationExecutionContext context) {
        OperationJob job = operationJobRepository.findByIdForUpdate(context.jobId())
                .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + context.jobId()));
        if (job.getStatus() != OperationStatus.RUNNING
                || context.claimToken() == null
                || !context.claimToken().equals(job.getClaimToken())
                || context.claimEpoch() != safeClaimEpoch(job)
                || context.retryEpoch() != safeRetryEpoch(job)
                || context.phase() != job.getPhase()
                || job.getLeaseExpiresAt() == null
                || !job.getLeaseExpiresAt().isAfter(Instant.now())) {
            throw new OperationConflictException("Operation claim is no longer current");
        }
        return job;
    }

    private OperationExecutionContext context(OperationJob job) {
        return new OperationExecutionContext(job.getId(), job.getClaimToken(), safeClaimEpoch(job),
                safeRetryEpoch(job), job.getPhase(), job.getTenantId(), job.getCreatedBy());
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

    private int safePhaseAttemptCount(OperationJob job) {
        return job.getPhaseAttemptCount() == null ? 0 : job.getPhaseAttemptCount();
    }

    private long safeClaimEpoch(OperationJob job) {
        return job.getClaimEpoch() == null ? 0L : job.getClaimEpoch();
    }

    private long safeRetryEpoch(OperationJob job) {
        return job.getRetryEpoch() == null ? 0L : job.getRetryEpoch();
    }

    private int leaseSeconds() {
        return configuredLeaseSeconds > 0 ? configuredLeaseSeconds : 60;
    }

    private int maxAttempts() {
        return configuredMaxAttempts > 0 ? configuredMaxAttempts : 5;
    }

    private Duration backoff(int phaseAttempts) {
        int exponent = Math.min(Math.max(phaseAttempts - 1, 0), 7);
        return Duration.ofSeconds(Math.min(3_600, 30L * (1L << exponent)));
    }

    private String limit(String error) {
        if (error == null || error.isBlank()) {
            return "operation_failed";
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
