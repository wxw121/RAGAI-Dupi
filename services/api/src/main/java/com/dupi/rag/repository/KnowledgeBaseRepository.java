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
    @Query("select kb from KnowledgeBase kb where kb.tenantId = :tenantId and kb.lifecycleStatus = com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus.READY order by kb.createdAt desc")
    List<KnowledgeBase> findByTenantIdOrderByCreatedAtDesc(@Param("tenantId") String tenantId);

    @Query("select kb from KnowledgeBase kb where kb.id = :id and kb.tenantId = :tenantId and kb.lifecycleStatus = com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus.READY")
    Optional<KnowledgeBase> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select kb from KnowledgeBase kb where kb.id = :id")
    Optional<KnowledgeBase> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select kb from KnowledgeBase kb where kb.id = :id and kb.tenantId = :tenantId and kb.lifecycleStatus = com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus.READY")
    Optional<KnowledgeBase> findByIdAndTenantIdForUpdate(
            @Param("id") UUID id,
            @Param("tenantId") String tenantId
    );

    Optional<KnowledgeBase> findByIdAndTenantIdAndLifecycleStatus(
            UUID id, String tenantId, com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus lifecycleStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select kb from KnowledgeBase kb where kb.id = :id")
    Optional<KnowledgeBase> findSystemByIdForUpdate(@Param("id") UUID id);
}
