package com.dupi.rag.service;

import com.dupi.rag.domain.enums.OperationStagingAttemptState;
import com.dupi.rag.repository.OperationStagingAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
class OperationStagingCleanupPersistence {
    private final OperationStagingAttemptRepository attempts;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    OperationStagingCleanupClaim claim(UUID id, Instant now, Duration lease) {
        var attempt = attempts.findByIdForUpdate(id).orElse(null);
        if (attempt == null || attempt.getState() != OperationStagingAttemptState.CLEANUP_PENDING
                || attempt.getLeaseExpiresAt() != null && attempt.getLeaseExpiresAt().isAfter(now)) return null;
        UUID token = UUID.randomUUID();
        attempt.setOwnerToken(token); attempt.setLeaseExpiresAt(now.plus(lease));
        attempt.setLastActivityAt(now); attempts.saveAndFlush(attempt);
        return new OperationStagingCleanupClaim(id, token, attempt.getStorageType(), attempt.getObjectKey());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void releaseAbsent(OperationStagingCleanupClaim claim, Instant now) {
        var attempt = attempts.findByIdForUpdate(claim.attemptId()).orElse(null);
        if (!owned(attempt, claim)) return;
        attempt.setLeaseExpiresAt(now); attempt.setLastActivityAt(now); attempts.saveAndFlush(attempt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void complete(OperationStagingCleanupClaim claim, Instant now) {
        var attempt = attempts.findByIdForUpdate(claim.attemptId()).orElse(null);
        if (!owned(attempt, claim)) return;
        attempt.setState(OperationStagingAttemptState.CLEANED);
        attempt.setLeaseExpiresAt(now); attempt.setLastActivityAt(now); attempts.saveAndFlush(attempt);
    }
    private boolean owned(com.dupi.rag.domain.entity.OperationStagingAttempt attempt,
                          OperationStagingCleanupClaim claim) {
        return attempt != null && attempt.getState() == OperationStagingAttemptState.CLEANUP_PENDING
                && claim.token().equals(attempt.getOwnerToken());
    }
}
