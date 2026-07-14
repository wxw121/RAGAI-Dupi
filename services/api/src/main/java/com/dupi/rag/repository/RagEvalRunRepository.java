package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.RagQualityGateStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RagEvalRunRepository extends JpaRepository<RagEvalRun, UUID> {
    List<RagEvalRun> findTop10ByKbIdOrderByCreatedAtDesc(UUID kbId);
    List<RagEvalRun> findByKbIdAndStatusAndGateStatus(
            UUID kbId, RagEvalRunStatus status, RagQualityGateStatus gateStatus);
}
