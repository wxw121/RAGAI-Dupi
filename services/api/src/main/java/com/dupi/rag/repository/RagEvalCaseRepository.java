package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.RagEvalCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RagEvalCaseRepository extends JpaRepository<RagEvalCase, UUID> {
    List<RagEvalCase> findByKbIdOrderByCreatedAtAsc(UUID kbId);

    Optional<RagEvalCase> findByIdAndKbId(UUID id, UUID kbId);

    long countByKbId(UUID kbId);
}
