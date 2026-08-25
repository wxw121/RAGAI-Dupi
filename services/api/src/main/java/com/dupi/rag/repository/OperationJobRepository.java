package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationPhase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

public interface OperationJobRepository extends JpaRepository<OperationJob, UUID> {

    interface TypeCount {
        OperationType getType();
        long getCount();
    }

    interface StatusCount {
        OperationStatus getStatus();
        long getCount();
    }

    @Query("select job.operationType as type, count(job) as count from OperationJob job group by job.operationType")
    List<TypeCount> countGroupedByType();

    @Query("select job.status as status, count(job) as count from OperationJob job group by job.status")
    List<StatusCount> countGroupedByStatus();

    @Query("""
            select count(job) from OperationJob job
            where job.runnable = true
              and job.status in (
                com.dupi.rag.domain.enums.OperationStatus.PREPARED,
                com.dupi.rag.domain.enums.OperationStatus.RETRY_WAIT,
                com.dupi.rag.domain.enums.OperationStatus.COMPENSATING
              )
              and job.nextAttemptAt <= :now
            """)
    long countDueBefore(@Param("now") Instant now);

    @Query("""
            select min(job.nextAttemptAt) from OperationJob job
            where job.runnable = true
              and job.status in (
                com.dupi.rag.domain.enums.OperationStatus.PREPARED,
                com.dupi.rag.domain.enums.OperationStatus.RETRY_WAIT,
                com.dupi.rag.domain.enums.OperationStatus.COMPENSATING
              )
              and job.nextAttemptAt <= :now
            """)
    Optional<Instant> findOldestDueAt(@Param("now") Instant now);

    @Query("select coalesce(sum(case when job.attemptCount > 1 then job.attemptCount - 1 else 0 end), 0) from OperationJob job")
    long sumRetryCount();

    long countByPhaseAndStatus(OperationPhase phase, OperationStatus status);

    Optional<OperationJob> findByTenantIdAndOperationTypeAndIdempotencyKey(
            String tenantId,
            OperationType operationType,
            String idempotencyKey
    );

    boolean existsByTenantIdAndAggregateTypeAndAggregateIdAndStatusIn(
            String tenantId,
            String aggregateType,
            UUID aggregateId,
            List<OperationStatus> statuses
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

    @Query(value = """
            select * from operation_jobs
            where runnable = true
              and ((status in ('PREPARED', 'RETRY_WAIT', 'COMPENSATING') and next_attempt_at <= :now)
                   or (status = 'RUNNING' and lease_expires_at <= :now))
            order by created_at asc, id asc
            for update skip locked
            limit 1
            """, nativeQuery = true)
    Optional<OperationJob> claimNextForUpdate(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from OperationJob job where job.id = :jobId")
    Optional<OperationJob> findByIdForUpdate(@Param("jobId") UUID jobId);
}
