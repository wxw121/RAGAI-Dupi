package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.domain.entity.RecoveryArchive;
import com.dupi.rag.domain.entity.RecoveryArchiveItem;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.domain.enums.RecoveryItemStatus;
import com.dupi.rag.dto.recovery.RecoveryManifest;
import com.dupi.rag.repository.RecoveryArchiveItemRepository;
import com.dupi.rag.repository.RecoveryArchiveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Commits recovered metadata atomically only after every deterministic object has verified. */
@Service
@RequiredArgsConstructor
class RecoveryArchiveImportPersistenceService {
    private final RecoveryArchiveRepository archives;
    private final RecoveryArchiveItemRepository items;
    private final RecoveryProperties properties;

    @Transactional
    public void persist(UUID archiveId, RecoveryArchiveImportPlan plan, RecoveryManifest manifest, StoredRecoveryObject storedManifest) {
        if (archives.existsById(archiveId)) return;
        RecoveryArchive archive = RecoveryArchive.builder().id(archiveId).tenantId(plan.tenantId())
                .sourceKnowledgeBaseId(plan.knowledgeBaseId()).status(RecoveryArchiveStatus.COMPLETED)
                .schemaVersion(RecoveryManifestService.SCHEMA_VERSION).bucket(properties.getBucket())
                .objectPrefix("archives/" + plan.tenantId() + "/" + archiveId + "/")
                .sourceRevision(plan.sourceRevision()).itemCount(manifest.itemCount()).totalBytes(manifest.totalBytes())
                .manifestChecksum(manifest.manifestChecksum()).createdBy(plan.createdBy()).build();
        archives.saveAndFlush(archive);
        List<RecoveryArchiveItem> rows = manifest.items().stream().map(item -> RecoveryArchiveItem.builder()
                .archiveId(archiveId).itemKey(item.itemKey()).itemType(item.itemType()).objectKey(item.objectKey())
                .byteSize(item.byteSize()).sha256(item.sha256()).status(RecoveryItemStatus.VERIFIED).attemptCount(1).build()).toList();
        RecoveryArchiveItem manifestRow = RecoveryArchiveItem.builder().archiveId(archiveId).itemKey("manifest")
                .itemType("MANIFEST").objectKey(storedManifest.objectKey())
                .byteSize(storedManifest.byteSize()).sha256(storedManifest.sha256()).status(RecoveryItemStatus.VERIFIED).attemptCount(1).build();
        java.util.ArrayList<RecoveryArchiveItem> all = new java.util.ArrayList<>(rows); all.add(manifestRow);
        items.saveAllAndFlush(all);
    }
}
