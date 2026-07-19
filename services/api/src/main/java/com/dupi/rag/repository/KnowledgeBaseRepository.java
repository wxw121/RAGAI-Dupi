package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {
    List<KnowledgeBase> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<KnowledgeBase> findByIdAndTenantId(UUID id, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select kb from KnowledgeBase kb where kb.id = :id")
    Optional<KnowledgeBase> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select kb from KnowledgeBase kb where kb.id = :id and kb.tenantId = :tenantId")
    Optional<KnowledgeBase> findByIdAndTenantIdForUpdate(
            @Param("id") UUID id,
            @Param("tenantId") String tenantId
    );
}
