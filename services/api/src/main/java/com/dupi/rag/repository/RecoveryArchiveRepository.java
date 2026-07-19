package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.RecoveryArchive;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecoveryArchiveRepository extends JpaRepository<RecoveryArchive, UUID> {
    Optional<RecoveryArchive> findByIdAndTenantId(UUID id, String tenantId);
    List<RecoveryArchive> findByTenantIdAndSourceKnowledgeBaseIdOrderByCreatedAtDesc(String tenantId, UUID sourceKnowledgeBaseId);
    boolean existsBySourceKnowledgeBaseIdAndStatusIn(UUID sourceKnowledgeBaseId, Collection<RecoveryArchiveStatus> statuses);

    @Query("select (count(a) > 0) from RecoveryArchive a where a.sourceKnowledgeBaseId = :kbId and a.id <> :archiveId and a.status in (com.dupi.rag.domain.enums.RecoveryArchiveStatus.PREPARING, com.dupi.rag.domain.enums.RecoveryArchiveStatus.CAPTURING, com.dupi.rag.domain.enums.RecoveryArchiveStatus.VERIFYING)")
    boolean existsActiveBySourceKnowledgeBaseIdExcluding(
            @Param("kbId") UUID knowledgeBaseId, @Param("archiveId") UUID archiveId);
}
