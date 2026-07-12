package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.VectorCleanupTask;
import com.dupi.rag.domain.enums.VectorCleanupStatus;
import com.dupi.rag.domain.enums.VectorCleanupTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VectorCleanupTaskRepository extends JpaRepository<VectorCleanupTask, UUID> {
    Optional<VectorCleanupTask> findByTargetTypeAndTargetIdAndStatus(
            VectorCleanupTargetType targetType,
            UUID targetId,
            VectorCleanupStatus status
    );

    List<VectorCleanupTask> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            VectorCleanupStatus status,
            Instant nextAttemptAt
    );

    List<VectorCleanupTask> findTop50ByStatusInOrderByUpdatedAtDesc(List<VectorCleanupStatus> statuses);

    @Query("""
            select d.kbId
            from Document d
            where d.id = :docId
            """)
    Optional<UUID> resolveKnowledgeBaseIdForDocumentTarget(UUID docId);
}
