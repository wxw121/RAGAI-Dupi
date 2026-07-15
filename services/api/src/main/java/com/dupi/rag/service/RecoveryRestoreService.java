package com.dupi.rag.service;

import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RecoveryArchive;
import com.dupi.rag.domain.entity.RecoveryArchiveItem;
import com.dupi.rag.domain.entity.RecoveryRestoreJob;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.domain.enums.RecoveryRestoreStatus;
import com.dupi.rag.domain.enums.RetrievalMode;
import com.dupi.rag.dto.recovery.RecoveryManifest;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import com.dupi.rag.repository.RecoveryArchiveItemRepository;
import com.dupi.rag.repository.RecoveryArchiveRepository;
import com.dupi.rag.repository.RecoveryRestoreJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RecoveryRestoreService {
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private final RecoveryArchiveRepository archives;
    private final RecoveryArchiveItemRepository archiveItems;
    private final RecoveryRestoreJobRepository jobs;
    private final KnowledgeBaseRepository knowledgeBases;
    private final RecoveryStorageService storage;
    private final RecoveryManifestService manifests;
    private final RecoveryRestoreWriter writer;
    private final AuditLogService auditLogService;

    @Transactional
    public RecoveryRestoreJob create(UUID archiveId, String actor) {
        String tenantId = TenantContext.getTenantId();
        RecoveryArchive archive = archives.findByIdAndTenantId(archiveId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Recovery archive not found: " + archiveId));
        RecoveryManifest manifest = validate(archive);
        UUID jobId = UUID.randomUUID();
        UUID targetId = deterministicId("restore-target", jobId);
        RetrievalMode retrievalMode = retrievalMode(manifest.header().collectionSettings());
        KnowledgeBase target = KnowledgeBase.builder()
                .id(targetId)
                .tenantId(tenantId)
                .name("Restored " + archive.getSourceKnowledgeBaseId().toString().substring(0, 8))
                .description("Restored from recovery archive " + archive.getId())
                .embeddingModel(manifest.header().embeddingModel())
                .embeddingDimension(manifest.header().embeddingDimension())
                .retrievalMode(retrievalMode)
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.RESTORING)
                .build();
        knowledgeBases.save(target);
        RecoveryRestoreJob job = RecoveryRestoreJob.builder()
                .id(jobId).archiveId(archiveId).tenantId(tenantId).targetKnowledgeBaseId(targetId)
                .status(RecoveryRestoreStatus.VALIDATING).totalItems(manifest.itemCount())
                .createdBy(actor == null || actor.isBlank() ? "system" : actor).build();
        RecoveryRestoreJob saved = jobs.save(job);
        auditLogService.recordSuccess("RECOVERY_RESTORE_CREATE", "RECOVERY_RESTORE", saved.getId(),
                "Created recovery restore " + saved.getId() + " targeting " + targetId);
        return saved;
    }

    @Transactional
    public RecoveryRestoreJob retry(UUID jobId) {
        RecoveryRestoreJob job = find(jobId);
        if (job.getStatus() != RecoveryRestoreStatus.FAILED) {
            throw new IllegalArgumentException("Only failed recovery restore jobs can be retried");
        }
        job.setStatus(RecoveryRestoreStatus.VALIDATING);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        jobs.save(job);
        return run(job);
    }

    @Transactional
    public RecoveryRestoreJob execute(UUID jobId) {
        RecoveryRestoreJob job = find(jobId);
        if (job.getStatus() != RecoveryRestoreStatus.VALIDATING) {
            throw new IllegalArgumentException("Only validated recovery restore jobs can execute");
        }
        return run(job);
    }

    private RecoveryRestoreJob run(RecoveryRestoreJob job) {
        try {
            writer.restore(job);
            job.setStatus(RecoveryRestoreStatus.COMPLETED);
            job.setCompletedItems(job.getTotalItems());
            RecoveryRestoreJob saved = jobs.save(job);
            auditLogService.recordSuccess("RECOVERY_RESTORE_COMPLETE", "RECOVERY_RESTORE", job.getId(),
                    "Completed recovery restore " + job.getId());
            return saved;
        } catch (Exception e) {
            job.setStatus(RecoveryRestoreStatus.FAILED);
            job.setErrorCode("RECOVERY_RESTORE_FAILED");
            job.setErrorMessage("Recovery restore failed; inspect item evidence and retry");
            jobs.save(job);
            auditLogService.recordFailure("RECOVERY_RESTORE_FAILED", "RECOVERY_RESTORE", job.getId(),
                    new IllegalStateException("Recovery restore failed"));
            throw new IllegalStateException("Recovery restore failed", e);
        }
    }

    @Transactional
    public void abandon(UUID jobId) {
        RecoveryRestoreJob job = find(jobId);
        if (job.getStatus() == RecoveryRestoreStatus.COMPLETED) {
            throw new IllegalArgumentException("Completed restore jobs cannot be abandoned");
        }
        writer.abandon(job);
        jobs.delete(job);
        auditLogService.recordSuccess("RECOVERY_RESTORE_ABANDON", "RECOVERY_RESTORE", jobId,
                "Abandoned recovery restore " + jobId);
    }

    public RecoveryRestoreJob find(UUID jobId) {
        return jobs.findByIdAndTenantId(jobId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Recovery restore job not found: " + jobId));
    }

    public List<RecoveryRestoreJob> list() {
        return jobs.findByTenantIdOrderByCreatedAtDesc(TenantContext.getTenantId());
    }

    @Transactional
    public RecoveryRestoreJob prepareRetry(UUID jobId) {
        RecoveryRestoreJob job = find(jobId);
        if (job.getStatus() != RecoveryRestoreStatus.FAILED) {
            throw new IllegalArgumentException("Only failed recovery restore jobs can be retried");
        }
        job.setStatus(RecoveryRestoreStatus.VALIDATING);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        RecoveryRestoreJob saved = jobs.save(job);
        auditLogService.recordSuccess("RECOVERY_RESTORE_RETRY", "RECOVERY_RESTORE", jobId,
                "Queued recovery restore retry " + jobId);
        return saved;
    }

    @Transactional
    public void markFailed(UUID jobId, String tenantId, String errorCode) {
        RecoveryRestoreJob job = jobs.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Recovery restore job not found: " + jobId));
        job.setStatus(RecoveryRestoreStatus.FAILED);
        job.setErrorCode(errorCode);
        job.setErrorMessage("Recovery job could not be scheduled; retry later");
        jobs.save(job);
        auditLogService.recordFailure("RECOVERY_RESTORE_SCHEDULE_FAILED", "RECOVERY_RESTORE", jobId,
                new IllegalStateException(errorCode));
    }

    private RecoveryManifest validate(RecoveryArchive archive) {
        if (archive.getStatus() != RecoveryArchiveStatus.COMPLETED) {
            throw new IllegalArgumentException("Recovery archive is not completed");
        }
        List<RecoveryArchiveItem> itemRows = archiveItems.findByArchiveIdOrderByItemKey(archive.getId());
        for (RecoveryArchiveItem item : itemRows) {
            StoredRecoveryObject expected = new StoredRecoveryObject(
                    archive.getBucket(), item.getObjectKey(), item.getByteSize(), item.getSha256());
            if (!storage.verify(expected)) {
                throw new IllegalArgumentException("Recovery archive item verification failed: " + item.getItemKey());
            }
        }
        RecoveryArchiveItem manifestItem = itemRows.stream()
                .filter(item -> "manifest".equals(item.getItemKey()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Recovery manifest item is missing"));
        RecoveryManifest manifest = manifests.parseAndValidate(storage.readSmall(
                archive.getBucket(), manifestItem.getObjectKey(), MAX_MANIFEST_BYTES));
        if (!archive.getId().equals(manifest.header().archiveId())
                || !archive.getTenantId().equals(manifest.header().tenantId())
                || !archive.getSourceKnowledgeBaseId().equals(manifest.header().sourceKnowledgeBaseId())
                || !manifest.manifestChecksum().equals(archive.getManifestChecksum())) {
            throw new IllegalArgumentException("Recovery manifest identity or checksum mismatch");
        }
        return manifest;
    }

    private RetrievalMode retrievalMode(Map<String, Object> settings) {
        Object value = settings == null ? null : settings.get("retrievalMode");
        try {
            return value == null ? RetrievalMode.VECTOR : RetrievalMode.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Recovery manifest retrieval mode is invalid");
        }
    }

    static UUID deterministicId(String namespace, UUID source) {
        return UUID.nameUUIDFromBytes((namespace + ":" + source).getBytes(StandardCharsets.UTF_8));
    }
}
