package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperationJobRepository extends JpaRepository<OperationJob, UUID> {

    Optional<OperationJob> findByTenantIdAndOperationTypeAndIdempotencyKey(
            String tenantId,
            OperationType operationType,
            String idempotencyKey
    );

    List<OperationJob> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            OperationStatus status,
            Instant nextAttemptAt
    );

    long countByStatus(OperationStatus status);

    @Query("select count(job) from OperationJob job where job.nextAttemptAt < :time")
    long countDueBefore(@Param("time") Instant time);
}
