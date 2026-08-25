package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStagingAttemptRepository;
import com.dupi.rag.domain.enums.OperationStagingAttemptState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Fences one expired intake before handing its existing staging steps to compensation. */
@Service
@RequiredArgsConstructor
class OperationStagingRetentionPersistenceService {
    static final String EXPIRED = "operation_staging_retention_expired";
    private static final Set<OperationType> STAGED_IMPORTS = Set.of(
            OperationType.RECOVERY_ARCHIVE_IMPORT, OperationType.MARKDOWN_PACKAGE_IMPORT);

    private final OperationJobRepository jobs;
    private final OperationStagingAttemptRepository stagingAttempts;
    private final AuditLogService audit;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean scheduleCleanup(UUID jobId, Instant cutoff, Instant now) {
        OperationJob job = jobs.findByIdForUpdate(jobId).orElse(null);
        if (!eligible(job, cutoff, now)) {
            return false;
        }
        job.setPhase(OperationPhase.COMPENSATION);
        job.setPhaseAttemptCount(0);
        job.setStatus(OperationStatus.COMPENSATING);
        job.setRunnable(true);
        job.setLastError(EXPIRED);
        job.setNextAttemptAt(now);
        job.setClaimToken(null);
        job.setLeaseExpiresAt(null);
        for (var attempt : stagingAttempts.findByJobIdAndState(
                jobId, OperationStagingAttemptState.ACTIVE)) {
            attempt.setState(OperationStagingAttemptState.CLEANUP_PENDING);
            attempt.setLeaseExpiresAt(now);
            attempt.setLastActivityAt(now);
            stagingAttempts.save(attempt);
        }
        jobs.saveAndFlush(job);
        audit.recordOperationInCurrentTransaction(
                job.getTenantId(), AuditLogService.OPERATION_COMPENSATE, job.getId(), EXPIRED);
        return true;
    }

    private boolean eligible(OperationJob job, Instant cutoff, Instant now) {
        return job != null
                && STAGED_IMPORTS.contains(job.getOperationType())
                && job.getStatus() == OperationStatus.PREPARED
                && job.getPhase() == OperationPhase.FORWARD
                && !Boolean.TRUE.equals(job.getRunnable())
                && ((job.getClaimToken() == null
                        && job.getUpdatedAt() != null
                        && !job.getUpdatedAt().isAfter(cutoff))
                    || (job.getClaimToken() != null
                        && job.getLeaseExpiresAt() != null
                        && !job.getLeaseExpiresAt().isAfter(now)));
    }
}
