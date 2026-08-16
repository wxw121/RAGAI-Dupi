package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.OperationJob;
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

    @Query("""
            select job from OperationJob job
            where job.status in (
                com.dupi.rag.domain.enums.OperationStatus.PREPARED,
                com.dupi.rag.domain.enums.OperationStatus.RETRY_WAIT,
                com.dupi.rag.domain.enums.OperationStatus.COMPENSATING
            )
              and job.nextAttemptAt <= :now
            order by job.createdAt asc, job.id asc
            """)
    List<OperationJob> findDueByStatusInOrderByCreatedAtAsc(
            @Param("now") Instant now
    );
}
