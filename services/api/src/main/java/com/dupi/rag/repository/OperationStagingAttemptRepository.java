package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.OperationStagingAttempt;
import com.dupi.rag.domain.enums.OperationStagingAttemptState;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface OperationStagingAttemptRepository extends JpaRepository<OperationStagingAttempt, UUID> {
    List<OperationStagingAttempt> findByJobIdAndOwnerEpoch(UUID jobId, Long ownerEpoch);
    List<OperationStagingAttempt> findByJobIdAndState(UUID jobId, OperationStagingAttemptState state);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select attempt from OperationStagingAttempt attempt where attempt.id = :id")
    Optional<OperationStagingAttempt> findByIdForUpdate(@Param("id") UUID id);

    @Query("select attempt.id from OperationStagingAttempt attempt "
            + "where attempt.state = com.dupi.rag.domain.enums.OperationStagingAttemptState.CLEANUP_PENDING "
            + "order by attempt.updatedAt asc, attempt.id asc")
    List<UUID> findCleanupPending(Pageable pageable);
}
