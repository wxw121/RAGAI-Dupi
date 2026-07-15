package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.RecoveryRestoreJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecoveryRestoreJobRepository extends JpaRepository<RecoveryRestoreJob, UUID> {
    Optional<RecoveryRestoreJob> findByIdAndTenantId(UUID id, String tenantId);
    List<RecoveryRestoreJob> findByTenantIdAndArchiveIdOrderByCreatedAtDesc(String tenantId, UUID archiveId);
    List<RecoveryRestoreJob> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
