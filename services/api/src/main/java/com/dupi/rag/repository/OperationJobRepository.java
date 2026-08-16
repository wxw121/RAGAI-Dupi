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

    List<OperationStatus> RUNNABLE_STATUSES = List.of(
            OperationStatus.PREPARED,
            OperationStatus.RETRY_WAIT,
            OperationStatus.COMPENSATING
    );

    Optional<OperationJob> findByTenantIdAndOperationTypeAndIdempotencyKey(
            String tenantId,
            OperationType operationType,
            String idempotencyKey
    );

    @Query("""
            select job from OperationJob job
            where job.status in :statuses
              and job.nextAttemptAt <= :now
            order by job.createdAt asc, job.id asc
            """)
    List<OperationJob> findDueByStatusInOrderByCreatedAtAsc(
            @Param("statuses") List<OperationStatus> statuses,
            @Param("now") Instant now
    );
}
