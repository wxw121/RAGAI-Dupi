package com.dupi.rag.service;

import com.dupi.rag.repository.OperationStagingAttemptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.UUID;

@Service @Slf4j
class OperationStagingCleanupService {
    private final OperationStagingAttemptRepository attempts;
    private final OperationStagingCleanupPersistence persistence;
    private final MinioStorageService minio;
    private final RecoveryStorageService recovery;
    private final int limit;
    private final Duration claimLease;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    OperationStagingCleanupService(OperationStagingAttemptRepository attempts,
            OperationStagingCleanupPersistence persistence, MinioStorageService minio,
            RecoveryStorageService recovery,
            @Value("${dupi.operations.staging-cleanup-limit:10}") int limit) {
        this(attempts, persistence, minio, recovery, limit, Duration.ofMinutes(5), Clock.systemUTC());
    }
    OperationStagingCleanupService(OperationStagingAttemptRepository attempts,
            OperationStagingCleanupPersistence persistence, MinioStorageService minio,
            RecoveryStorageService recovery, int limit, Duration claimLease, Clock clock) {
        this.attempts = attempts; this.persistence = persistence; this.minio = minio;
        this.recovery = recovery; this.limit = Math.max(1, limit); this.claimLease = claimLease; this.clock = clock;
    }

    @Scheduled(cron = "${dupi.operations.staging-cleanup-cron:0 0 * * * *}")
    int cleanupPending() {
        int completed = 0;
        for (UUID id : attempts.findCleanupPending(PageRequest.of(0, limit))) {
            Instant now = clock.instant();
            OperationStagingCleanupClaim claim = persistence.claim(id, now, claimLease);
            if (claim == null) continue;
            try {
                boolean present = "MINIO".equals(claim.storageType())
                        ? minio.existsChecked(claim.objectKey()) : recovery.exists(claim.objectKey());
                if (!present) { persistence.releaseAbsent(claim, clock.instant()); continue; }
                if ("MINIO".equals(claim.storageType())) minio.deleteChecked(claim.objectKey());
                else recovery.delete(claim.objectKey());
                persistence.complete(claim, clock.instant()); completed++;
            } catch (RuntimeException failure) {
                persistence.releaseAbsent(claim, clock.instant());
                log.warn("Failed to clean operation staging attempt {}", id, failure);
            }
        }
        return completed;
    }
}
