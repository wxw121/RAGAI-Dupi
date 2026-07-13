package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.RagEvalRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RagEvalRunRepository extends JpaRepository<RagEvalRun, UUID> {
    List<RagEvalRun> findTop10ByKbIdOrderByCreatedAtDesc(UUID kbId);
}
