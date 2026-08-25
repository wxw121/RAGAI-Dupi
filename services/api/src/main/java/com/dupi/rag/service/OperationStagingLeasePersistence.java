package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.*;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStagingAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class OperationStagingLeasePersistence {
    private final OperationJobRepository jobs;
    private final OperationStagingAttemptRepository attempts;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    OperationStagingLease acquire(UUID jobId, Instant now, Duration duration) {
        OperationJob job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new OperationConflictException("Staging operation no longer exists"));
        requireIntake(job);
        if (job.getClaimToken() != null && job.getLeaseExpiresAt() != null
                && job.getLeaseExpiresAt().isAfter(now)) {
            throw new OperationConflictException("Staging intake is owned by another writer");
        }
        moveReplacedOwnerAttemptsToCleanup(job, now);
        UUID token = UUID.randomUUID();
        long epoch = safe(job.getClaimEpoch()) + 1;
        job.setClaimToken(token); job.setClaimEpoch(epoch);
        job.setLeaseExpiresAt(now.plus(duration)); job.setNextAttemptAt(now);
        jobs.saveAndFlush(job);
        return new OperationStagingLease(jobId, token, epoch);
    }

    private void moveReplacedOwnerAttemptsToCleanup(OperationJob job, Instant now) {
        UUID replacedToken = job.getClaimToken();
        long replacedEpoch = safe(job.getClaimEpoch());
        if (replacedToken == null) return;
        for (var attempt : attempts.findByJobIdAndOwnerEpoch(job.getId(), replacedEpoch)) {
            if (attempt.getState() != OperationStagingAttemptState.ACTIVE
                    || !Objects.equals(replacedToken, attempt.getOwnerToken())) continue;
            attempt.setState(OperationStagingAttemptState.CLEANUP_PENDING);
            attempt.setLeaseExpiresAt(now);
            attempt.setLastActivityAt(now);
            attempts.save(attempt);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean renew(OperationStagingLease lease, Instant now, Duration duration) {
        OperationJob job = jobs.findByIdForUpdate(lease.jobId()).orElse(null);
        if (!owns(job, lease, now)) return false;
        job.setLeaseExpiresAt(now.plus(duration));
        jobs.saveAndFlush(job);
        return true;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    boolean owns(OperationStagingLease lease) {
        Instant now = Instant.now();
        return jobs.findById(lease.jobId()).map(job -> owns(job, lease, now)).orElse(false);
    }

    private boolean owns(OperationJob job, OperationStagingLease lease, Instant now) {
        return job != null && job.getStatus() == OperationStatus.PREPARED
                && job.getPhase() == OperationPhase.FORWARD && !Boolean.TRUE.equals(job.getRunnable())
                && lease.token().equals(job.getClaimToken())
                && lease.epoch() == safe(job.getClaimEpoch())
                && job.getLeaseExpiresAt() != null && job.getLeaseExpiresAt().isAfter(now);
    }
    private void requireIntake(OperationJob job) {
        if (job.getStatus() != OperationStatus.PREPARED || job.getPhase() != OperationPhase.FORWARD
                || Boolean.TRUE.equals(job.getRunnable())) {
            throw new OperationConflictException("Operation intake is no longer writable");
        }
    }
    private long safe(Long value) { return value == null ? 0 : value; }
}
