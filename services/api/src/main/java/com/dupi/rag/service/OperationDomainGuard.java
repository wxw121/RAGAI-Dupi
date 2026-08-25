package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.OperationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Joins the caller's domain transaction and holds the parent-row fence until that transaction ends.
 * External side effects cannot share this database lock: invoke the guard immediately before/after
 * them and use a deterministic idempotency key derived from the operation and step identity.
 */
@Service
@RequiredArgsConstructor
public class OperationDomainGuard {
    private final OperationJobRepository jobs;

    @Transactional(propagation = Propagation.MANDATORY)
    public OperationJob assertActive(OperationExecutionContext context) {
        OperationJob job = jobs.findByIdForUpdate(context.jobId())
                .orElseThrow(() -> new ResourceNotFoundException("Operation job not found: " + context.jobId()));
        long claimEpoch = job.getClaimEpoch() == null ? 0L : job.getClaimEpoch();
        long retryEpoch = job.getRetryEpoch() == null ? 0L : job.getRetryEpoch();
        if (job.getStatus() != OperationStatus.RUNNING
                || context.claimToken() == null
                || !context.claimToken().equals(job.getClaimToken())
                || context.claimEpoch() != claimEpoch
                || context.retryEpoch() != retryEpoch
                || context.phase() != job.getPhase()
                || job.getLeaseExpiresAt() == null
                || !job.getLeaseExpiresAt().isAfter(Instant.now())) {
            throw new OperationConflictException("Operation claim is no longer current");
        }
        return job;
    }
}
