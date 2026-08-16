package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.OperationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the brief database transactions surrounding workflow execution. External
 * calls must run outside these transactions so a stalled provider cannot keep a
 * database connection or row lock open.
 */
@Service
@RequiredArgsConstructor
public class OperationJobClaimService {

    private static final int MAX_ERROR_LENGTH = 2_000;

    private final OperationJobRepository operationJobRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OperationJob> claimNext() {
        Instant now = Instant.now();
        return operationJobRepository.findDueByStatusInOrderByCreatedAtAsc(now).stream()
                .map(this::lockForClaim)
                .filter(this::isClaimable)
                .findFirst()
                .map(job -> {
                    job.setStatus(OperationStatus.RUNNING);
                    job.setAttemptCount(safeAttemptCount(job) + 1);
                    return operationJobRepository.saveAndFlush(job);
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID jobId) {
        OperationJob job = find(jobId);
        if (job.getStatus() == OperationStatus.COMPLETED) {
            return;
        }
        if (job.getStatus() != OperationStatus.RUNNING) {
            throw new IllegalStateException("Operation job is not running: " + jobId);
        }
        job.setStatus(OperationStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        job.setLastError(null);
        operationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleRetry(UUID jobId, String error) {
        OperationJob job = find(jobId);
        if (job.getStatus() == OperationStatus.COMPLETED) {
            return;
        }
        job.setStatus(OperationStatus.RETRY_WAIT);
        job.setLastError(limit(error));
        job.setNextAttemptAt(Instant.now().plus(backoff(safeAttemptCount(job))));
        operationJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, String error) {
        OperationJob job = find(jobId);
        if (job.getStatus() == OperationStatus.COMPLETED) {
            return;
        }
        job.setStatus(OperationStatus.FAILED);
        job.setLastError(limit(error));
        job.setCompletedAt(Instant.now());
        operationJobRepository.save(job);
    }

    private OperationJob find(UUID jobId) {
        return operationJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + jobId));
    }

    private boolean isClaimable(OperationJob job) {
        return job.getStatus() == OperationStatus.PREPARED
                || job.getStatus() == OperationStatus.RETRY_WAIT
                || job.getStatus() == OperationStatus.COMPENSATING;
    }

    private OperationJob lockForClaim(OperationJob job) {
        if (entityManager == null) {
            return job;
        }
        entityManager.refresh(job, LockModeType.PESSIMISTIC_WRITE);
        return job;
    }

    private int safeAttemptCount(OperationJob job) {
        return job.getAttemptCount() == null ? 0 : job.getAttemptCount();
    }

    private Duration backoff(int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 7);
        return Duration.ofSeconds(Math.min(3_600, 30L * (1L << exponent)));
    }

    private String limit(String error) {
        if (error == null || error.isBlank()) {
            return "Operation failed without an error message";
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
