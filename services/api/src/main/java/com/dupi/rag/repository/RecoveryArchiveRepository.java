package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.RecoveryArchive;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecoveryArchiveRepository extends JpaRepository<RecoveryArchive, UUID> {
    Optional<RecoveryArchive> findByIdAndTenantId(UUID id, String tenantId);
    List<RecoveryArchive> findByTenantIdAndSourceKnowledgeBaseIdOrderByCreatedAtDesc(String tenantId, UUID sourceKnowledgeBaseId);
}
