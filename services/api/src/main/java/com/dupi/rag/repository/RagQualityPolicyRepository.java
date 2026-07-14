package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.RagQualityPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RagQualityPolicyRepository extends JpaRepository<RagQualityPolicy, UUID> {
    Optional<RagQualityPolicy> findByKbId(UUID kbId);
}
