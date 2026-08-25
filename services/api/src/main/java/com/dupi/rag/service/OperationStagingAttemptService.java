package com.dupi.rag.service;

import com.dupi.rag.domain.entity.*;
import com.dupi.rag.domain.enums.OperationStagingAttemptState;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
class OperationStagingAttemptService {
    private final OperationJobRepository jobs;
    private final OperationStepRepository steps;
    private final OperationStagingAttemptRepository attempts;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    OperationStagingAttempt arm(OperationStagingLease lease, String stepKey,
                                String storageType, String baseKey) {
        OperationJob job = jobs.findByIdForUpdate(lease.jobId()).orElseThrow();
        requireOwner(job, lease);
        String objectKey = attemptKey(baseKey, lease);
        OperationStagingAttempt existing = attempts.findByJobIdAndOwnerEpoch(job.getId(), lease.epoch())
                .stream().filter(value -> stepKey.equals(value.getStepKey())).findFirst().orElse(null);
        if (existing != null) return existing;
        Instant now = Instant.now();
        OperationStagingAttempt armed = attempts.saveAndFlush(OperationStagingAttempt.builder()
                .jobId(job.getId()).tenantId(job.getTenantId()).stepKey(stepKey)
                .storageType(storageType).objectKey(objectKey)
                .state(OperationStagingAttemptState.ACTIVE).ownerToken(lease.token())
                .ownerEpoch(lease.epoch()).leaseExpiresAt(job.getLeaseExpiresAt())
                .lastActivityAt(now).build());
        OperationStep step = steps.findByJobIdAndStepKey(job.getId(), stepKey)
                .orElseThrow(() -> new OperationConflictException("Staging step is missing: " + stepKey));
        step.setResourceRef(objectKey);
        steps.saveAndFlush(step);
        return armed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markCleanupPending(UUID attemptId) {
        OperationStagingAttempt attempt = attempts.findByIdForUpdate(attemptId).orElse(null);
        if (attempt == null || attempt.getState() == OperationStagingAttemptState.CLEANED) return;
        attempt.setState(OperationStagingAttemptState.CLEANUP_PENDING);
        attempt.setLastActivityAt(Instant.now());
        attempts.saveAndFlush(attempt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void transferToPublished(OperationStagingLease lease) {
        OperationJob job = jobs.findByIdForUpdate(lease.jobId()).orElseThrow();
        requireOwner(job, lease);
        for (OperationStagingAttempt attempt : attempts.findByJobIdAndOwnerEpoch(job.getId(), lease.epoch())) {
            if (attempt.getState() != OperationStagingAttemptState.ACTIVE)
                throw new OperationConflictException("Staging attempt is no longer publishable");
            attempt.setState(OperationStagingAttemptState.CLEANED);
            attempt.setLastActivityAt(Instant.now());
            attempts.save(attempt);
        }
    }

    @Transactional(readOnly = true)
    List<OperationStagingAttempt> activeForJob(UUID jobId) {
        return attempts.findByJobIdAndState(jobId, OperationStagingAttemptState.ACTIVE);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void markJobCleanupPending(UUID jobId) {
        for (OperationStagingAttempt attempt : attempts.findByJobIdAndState(
                jobId, OperationStagingAttemptState.ACTIVE)) {
            attempt.setState(OperationStagingAttemptState.CLEANUP_PENDING);
            Instant now = Instant.now();
            attempt.setLeaseExpiresAt(now);
            attempt.setLastActivityAt(now);
            attempts.save(attempt);
        }
    }

    private void requireOwner(OperationJob job, OperationStagingLease lease) {
        if (job.getStatus() != com.dupi.rag.domain.enums.OperationStatus.PREPARED
                || job.getPhase() != com.dupi.rag.domain.enums.OperationPhase.FORWARD
                || Boolean.TRUE.equals(job.getRunnable())
                || !lease.token().equals(job.getClaimToken())
                || lease.epoch() != (job.getClaimEpoch() == null ? 0 : job.getClaimEpoch())
                || job.getLeaseExpiresAt() == null || !job.getLeaseExpiresAt().isAfter(Instant.now()))
            throw new OperationConflictException("Staging ownership was lost");
    }
    static String attemptKey(String base, OperationStagingLease lease) {
        return base + ".attempt-" + lease.epoch() + "-" + lease.token();
    }
}
