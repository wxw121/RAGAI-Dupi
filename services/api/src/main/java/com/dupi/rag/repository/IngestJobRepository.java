package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IngestJobRepository extends JpaRepository<IngestJob, UUID> {
    Optional<IngestJob> findTopByDocIdOrderByCreatedAtDesc(UUID docId);

    List<IngestJob> findByKbIdOrderByCreatedAtDesc(UUID kbId);

    List<IngestJob> findTop20ByStatusAndStageOrderByCreatedAtAsc(IngestJobStatus status, IngestStage stage);
}
