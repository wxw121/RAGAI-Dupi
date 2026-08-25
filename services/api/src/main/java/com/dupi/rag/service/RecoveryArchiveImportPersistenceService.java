package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.domain.entity.RecoveryArchive;
import com.dupi.rag.domain.entity.RecoveryArchiveItem;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.domain.enums.RecoveryItemStatus;
import com.dupi.rag.dto.recovery.RecoveryManifest;
import com.dupi.rag.dto.recovery.RecoveryManifestItem;
import com.dupi.rag.repository.RecoveryArchiveItemRepository;
import com.dupi.rag.repository.RecoveryArchiveRepository;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Fenced metadata commit and cleanup for one deterministic Recovery import job. */
@Service
@RequiredArgsConstructor
class RecoveryArchiveImportPersistenceService {
    private final RecoveryArchiveRepository archives;
    private final RecoveryArchiveItemRepository items;
    private final RecoveryProperties properties;
    private final OperationDomainGuard guard;
    private final KnowledgeBaseRepository knowledgeBases;

    @Transactional
    public void persist(OperationExecutionContext context, RecoveryArchiveImportPlan plan,
                        RecoveryManifest manifest, StoredRecoveryObject storedManifest) {
        guard.assertActive(context);
        UUID archiveId = context.jobId();
        var existing = archives.findById(archiveId);
        if (existing.isPresent()) {
            requireArchiveMatch(existing.get(), archiveId, plan, manifest);
            requireItemsMatch(archiveId, manifest, storedManifest);
            return;
        }
        var knowledgeBase = knowledgeBases.findByIdForUpdate(plan.knowledgeBaseId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Knowledge base is not available for Recovery import"));
        if (!Objects.equals(knowledgeBase.getTenantId(), plan.tenantId())
                || knowledgeBase.getLifecycleStatus() != KnowledgeBaseLifecycleStatus.READY) {
            throw new IllegalArgumentException("Knowledge base is not available for Recovery import");
        }
        RecoveryArchive archive = RecoveryArchive.builder().id(archiveId).tenantId(plan.tenantId())
                .sourceKnowledgeBaseId(plan.knowledgeBaseId()).status(RecoveryArchiveStatus.COMPLETED)
                .schemaVersion(RecoveryManifestService.SCHEMA_VERSION).bucket(properties.getBucket())
                .objectPrefix(prefix(plan, archiveId)).sourceRevision(plan.sourceRevision())
                .itemCount(manifest.itemCount()).totalBytes(manifest.totalBytes())
                .manifestChecksum(manifest.manifestChecksum()).createdBy(plan.createdBy()).build();
        archives.saveAndFlush(archive);
        ArrayList<RecoveryArchiveItem> rows = new ArrayList<>();
        for (RecoveryManifestItem item : manifest.items()) rows.add(row(archiveId, item));
        rows.add(RecoveryArchiveItem.builder().archiveId(archiveId).itemKey("manifest")
                .itemType("MANIFEST").objectKey(storedManifest.objectKey())
                .byteSize(storedManifest.byteSize()).sha256(storedManifest.sha256())
                .status(RecoveryItemStatus.VERIFIED).attemptCount(1).build());
        items.saveAllAndFlush(rows);
    }

    @Transactional
    public void delete(OperationExecutionContext context, RecoveryArchiveImportPlan plan) {
        guard.assertActive(context);
        UUID archiveId = context.jobId();
        var existing = archives.findById(archiveId);
        if (existing.isEmpty()) return;
        RecoveryArchive archive = existing.get();
        requireArchiveOwnership(archive, archiveId, plan);
        List<RecoveryArchiveItem> existingItems = items.findByArchiveIdOrderByItemKey(archiveId);
        requireCleanupItemsMatch(archiveId, plan, existingItems);
        items.deleteAllInBatch(existingItems);
        archives.delete(archive);
        archives.flush();
    }

    private void requireArchiveMatch(RecoveryArchive archive, UUID archiveId,
                                     RecoveryArchiveImportPlan plan, RecoveryManifest manifest) {
        requireArchiveOwnership(archive, archiveId, plan);
        if (archive.getStatus() != RecoveryArchiveStatus.COMPLETED
                || !Objects.equals(archive.getSchemaVersion(), RecoveryManifestService.SCHEMA_VERSION)
                || !Objects.equals(archive.getBucket(), properties.getBucket())
                || !Objects.equals(archive.getObjectPrefix(), prefix(plan, archiveId))
                || !Objects.equals(archive.getSourceRevision(), plan.sourceRevision())
                || !Objects.equals(archive.getItemCount(), manifest.itemCount())
                || !Objects.equals(archive.getTotalBytes(), manifest.totalBytes())
                || !Objects.equals(archive.getManifestChecksum(), manifest.manifestChecksum())
                || !Objects.equals(archive.getCreatedBy(), plan.createdBy())) mismatch();
    }

    private void requireArchiveOwnership(RecoveryArchive archive, UUID archiveId, RecoveryArchiveImportPlan plan) {
        if (!Objects.equals(archive.getId(), archiveId)
                || !Objects.equals(archive.getTenantId(), plan.tenantId())
                || !Objects.equals(archive.getSourceKnowledgeBaseId(), plan.knowledgeBaseId())) mismatch();
    }

    private void requireItemsMatch(UUID archiveId, RecoveryManifest manifest, StoredRecoveryObject storedManifest) {
        Map<String, ExpectedItem> expected = new HashMap<>();
        for (RecoveryManifestItem item : manifest.items()) expected.put(item.itemKey(), new ExpectedItem(
                item.itemType(), item.objectKey(), item.byteSize(), item.sha256()));
        expected.put("manifest", new ExpectedItem("MANIFEST", storedManifest.objectKey(),
                storedManifest.byteSize(), storedManifest.sha256()));
        List<RecoveryArchiveItem> actual = items.findByArchiveIdOrderByItemKey(archiveId);
        if (actual.size() != expected.size()) mismatch();
        for (RecoveryArchiveItem row : actual) requireItem(row, archiveId, expected.remove(row.getItemKey()));
        if (!expected.isEmpty()) mismatch();
    }

    private void requireCleanupItemsMatch(UUID archiveId, RecoveryArchiveImportPlan plan,
                                          List<RecoveryArchiveItem> actual) {
        Map<String, ExpectedItem> expected = new HashMap<>();
        for (RecoveryArchiveImportPlan.Entry entry : plan.entries()) expected.put(entry.itemKey(), new ExpectedItem(
                entry.itemType(), prefix(plan, archiveId) + entry.relativePath(), entry.byteSize(), entry.sha256()));
        for (RecoveryArchiveItem row : actual) {
            if ("manifest".equals(row.getItemKey())) {
                if (!Objects.equals(row.getArchiveId(), archiveId) || !"MANIFEST".equals(row.getItemType())
                        || !Objects.equals(row.getObjectKey(), prefix(plan, archiveId) + "manifest.json")) mismatch();
            } else {
                requireItem(row, archiveId, expected.remove(row.getItemKey()));
            }
        }
        if (!expected.isEmpty()) mismatch();
    }

    private void requireItem(RecoveryArchiveItem row, UUID archiveId, ExpectedItem expected) {
        if (expected == null || !Objects.equals(row.getArchiveId(), archiveId)
                || !Objects.equals(row.getItemType(), expected.type)
                || !Objects.equals(row.getObjectKey(), expected.objectKey)
                || !Objects.equals(row.getByteSize(), expected.size)
                || !Objects.equals(row.getSha256(), expected.sha256)
                || row.getStatus() != RecoveryItemStatus.VERIFIED) mismatch();
    }

    private RecoveryArchiveItem row(UUID archiveId, RecoveryManifestItem item) {
        return RecoveryArchiveItem.builder().archiveId(archiveId).itemKey(item.itemKey())
                .itemType(item.itemType()).objectKey(item.objectKey()).byteSize(item.byteSize())
                .sha256(item.sha256()).status(RecoveryItemStatus.VERIFIED).attemptCount(1).build();
    }

    private String prefix(RecoveryArchiveImportPlan plan, UUID archiveId) {
        return "archives/" + plan.tenantId() + "/" + archiveId + "/";
    }

    private void mismatch() {
        throw new IllegalArgumentException("Existing Recovery import metadata does not match the immutable plan");
    }

    private record ExpectedItem(String type, String objectKey, Long size, String sha256) { }
}
