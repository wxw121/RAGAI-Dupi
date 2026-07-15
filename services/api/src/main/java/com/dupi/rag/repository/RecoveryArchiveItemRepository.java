package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.RecoveryArchiveItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecoveryArchiveItemRepository extends JpaRepository<RecoveryArchiveItem, UUID> {
    Optional<RecoveryArchiveItem> findByArchiveIdAndItemKey(UUID archiveId, String itemKey);
    List<RecoveryArchiveItem> findByArchiveIdOrderByItemKey(UUID archiveId);
}
