package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.IngestOutboxEvent;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IngestOutboxEventRepository extends JpaRepository<IngestOutboxEvent, UUID> {
    long countByStatus(IngestOutboxStatus status);

    @Query("""
            select new com.dupi.rag.repository.IngestOutboxDispatchCandidate(
                event.id, event.jobId, event.docId)
            from IngestOutboxEvent event
            where event.status in :statuses and event.nextAttemptAt <= :now
            order by event.createdAt asc, event.id asc
            """)
    List<IngestOutboxDispatchCandidate> findDispatchCandidates(
            @Param("statuses") List<IngestOutboxStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from IngestOutboxEvent event where event.id = :id")
    java.util.Optional<IngestOutboxEvent> findByIdForUpdate(@Param("id") UUID id);

    List<IngestOutboxEvent> findByJobIdAndStatusIn(UUID jobId, List<IngestOutboxStatus> statuses);

    @Query("select event.id from IngestOutboxEvent event where event.jobId = :jobId and event.status in :statuses")
    List<UUID> findIdsByJobIdAndStatusIn(@Param("jobId") UUID jobId,
                                         @Param("statuses") List<IngestOutboxStatus> statuses);

    List<IngestOutboxEvent> findByJobId(UUID jobId);

    void deleteByJobId(UUID jobId);
}
