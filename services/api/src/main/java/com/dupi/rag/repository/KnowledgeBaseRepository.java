package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {
    List<KnowledgeBase> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<KnowledgeBase> findByIdAndTenantId(UUID id, String tenantId);
}
