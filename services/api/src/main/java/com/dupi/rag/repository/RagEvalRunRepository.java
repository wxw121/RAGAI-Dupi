package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.RagQualityGateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RagEvalRunRepository extends JpaRepository<RagEvalRun, UUID> {
    List<RagEvalRun> findTop10ByKbIdOrderByCreatedAtDesc(UUID kbId);

    @Query(value = """
            SELECT *
            FROM rag_eval_runs
            WHERE kb_id = :kbId
              AND status = 'COMPLETED'
              AND jsonb_exists(profile_set, 'CLASSIC')
              AND jsonb_exists(profile_set, :profile)
            ORDER BY created_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<RagEvalRun> findLatestCompletedContainingClassicAndProfile(
            @Param("kbId") UUID kbId,
            @Param("profile") String profile
    );

    List<RagEvalRun> findByKbIdAndStatusAndGateStatus(
            UUID kbId, RagEvalRunStatus status, RagQualityGateStatus gateStatus);

    boolean existsByKbIdAndStatus(UUID kbId, RagEvalRunStatus status);
}
