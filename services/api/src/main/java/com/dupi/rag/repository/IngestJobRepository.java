package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IngestJobRepository extends JpaRepository<IngestJob, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from IngestJob job where job.id = :id")
    Optional<IngestJob> findByIdForUpdate(@Param("id") UUID id);

    Optional<IngestJob> findTopByDocIdOrderByCreatedAtDesc(UUID docId);

    List<IngestJob> findByKbIdOrderByCreatedAtDesc(UUID kbId);

    List<IngestJob> findTop20ByStatusAndStageOrderByCreatedAtAsc(IngestJobStatus status, IngestStage stage);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from IngestJob job
            where job.status = :status
              and (job.leaseExpiresAt is null or job.leaseExpiresAt < :now)
            order by job.updatedAt asc
            """)
    List<IngestJob> findTop20ByStatusAndLeaseExpiresAtBeforeOrderByUpdatedAtAsc(
            @Param("status") IngestJobStatus status,
            @Param("now") java.time.Instant now
    );

    long countByStatusIn(List<IngestJobStatus> statuses);

    long countByStatus(IngestJobStatus status);

    @Query("""
            select count(job) from IngestJob job
            where job.status = com.dupi.rag.domain.enums.IngestJobStatus.PROCESSING
              and job.leaseExpiresAt is not null
              and job.leaseExpiresAt < :now
            """)
    long countExpiredProcessingLeases(@Param("now") java.time.Instant now);

    boolean existsByKbIdAndStatusIn(UUID kbId, List<IngestJobStatus> statuses);
}
