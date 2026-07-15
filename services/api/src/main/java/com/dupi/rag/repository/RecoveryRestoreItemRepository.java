package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.RecoveryRestoreItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecoveryRestoreItemRepository extends JpaRepository<RecoveryRestoreItem, UUID> {
    Optional<RecoveryRestoreItem> findByRestoreJobIdAndArchiveItemId(UUID restoreJobId, UUID archiveItemId);
    List<RecoveryRestoreItem> findByRestoreJobIdOrderByArchiveItemId(UUID restoreJobId);
}
