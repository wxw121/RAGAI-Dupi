package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.OperationStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperationStepRepository extends JpaRepository<OperationStep, UUID> {

    Optional<OperationStep> findByJobIdAndStepKey(UUID jobId, String stepKey);

    List<OperationStep> findByJobIdOrderBySequenceNumberAsc(UUID jobId);
}
