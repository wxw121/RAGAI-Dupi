package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.domain.entity.RecoveryArchive;
import com.dupi.rag.domain.entity.RecoveryArchiveItem;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.domain.enums.RecoveryItemStatus;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.dto.recovery.RecoveryManifest;
import com.dupi.rag.repository.RecoveryArchiveItemRepository;
import com.dupi.rag.repository.RecoveryArchiveRepository;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecoveryArchiveImportPersistenceServiceTest {
    private final RecoveryArchiveRepository archives = mock(RecoveryArchiveRepository.class);
    private final RecoveryArchiveItemRepository items = mock(RecoveryArchiveItemRepository.class);
    private final OperationDomainGuard guard = mock(OperationDomainGuard.class);
    private final KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
    private final RecoveryProperties properties = properties();
    private final RecoveryArchiveImportPersistenceService persistence =
            new RecoveryArchiveImportPersistenceService(archives, items, properties, guard, knowledgeBases);

    @Test
    void persistFencesBeforeWritingAndRejectsStaleContextWithoutMetadataWrites() {
        OperationExecutionContext context = context();
        RecoveryArchiveImportPlan plan = plan();
        RecoveryManifest manifest = manifest(plan, context.jobId());
        StoredRecoveryObject storedManifest = storedManifest(context.jobId(), manifest);
        doThrow(new com.dupi.rag.exception.OperationConflictException("stale"))
                .when(guard).assertActive(context);

        assertThatThrownBy(() -> persistence.persist(context, plan, manifest, storedManifest))
                .isInstanceOf(com.dupi.rag.exception.OperationConflictException.class);
        verifyNoInteractions(archives, items);
    }

    @Test
    void persistHoldsFenceThenFlushesArchiveAndItems() {
        OperationExecutionContext context = context();
        RecoveryArchiveImportPlan plan = plan();
        RecoveryManifest manifest = manifest(plan, context.jobId());
        when(archives.findById(context.jobId())).thenReturn(Optional.empty());
        when(knowledgeBases.findByIdForUpdate(plan.knowledgeBaseId())).thenReturn(Optional.of(
                KnowledgeBase.builder().id(plan.knowledgeBaseId()).tenantId(plan.tenantId())
                        .lifecycleStatus(KnowledgeBaseLifecycleStatus.READY).build()));

        persistence.persist(context, plan, manifest, storedManifest(context.jobId(), manifest));

        InOrder order = inOrder(guard, archives, items);
        order.verify(guard).assertActive(context);
        order.verify(archives).saveAndFlush(any(RecoveryArchive.class));
        order.verify(items).saveAllAndFlush(any());
    }

    @Test
    void newArchiveMetadataCannotRacePastKnowledgeBaseDeletion() {
        OperationExecutionContext context = context();
        RecoveryArchiveImportPlan plan = plan();
        RecoveryManifest manifest = manifest(plan, context.jobId());
        when(archives.findById(context.jobId())).thenReturn(Optional.empty());
        when(knowledgeBases.findByIdForUpdate(plan.knowledgeBaseId())).thenReturn(Optional.of(
                KnowledgeBase.builder().id(plan.knowledgeBaseId()).tenantId(plan.tenantId())
                        .lifecycleStatus(KnowledgeBaseLifecycleStatus.DELETING).build()));

        assertThatThrownBy(() -> persistence.persist(context, plan, manifest,
                storedManifest(context.jobId(), manifest)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available for Recovery import");

        verify(archives, never()).saveAndFlush(any());
        verifyNoInteractions(items);
    }

    @Test
    void existingDeterministicMetadataMustMatchEveryPlannedItem() {
        OperationExecutionContext context = context();
        RecoveryArchiveImportPlan plan = plan();
        RecoveryManifest manifest = manifest(plan, context.jobId());
        RecoveryArchive existing = matchingArchive(context.jobId(), plan, manifest);
        RecoveryArchiveItem wrong = RecoveryArchiveItem.builder().archiveId(context.jobId())
                .itemKey("record:a").itemType("RECORD").objectKey("wrong")
                .byteSize(3L).sha256("d".repeat(64)).status(RecoveryItemStatus.VERIFIED).build();
        when(archives.findById(context.jobId())).thenReturn(Optional.of(existing));
        when(items.findByArchiveIdOrderByItemKey(context.jobId())).thenReturn(List.of(wrong));

        assertThatThrownBy(() -> persistence.persist(context, plan, manifest, storedManifest(context.jobId(), manifest)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");
        verify(archives, never()).saveAndFlush(any());
    }

    @Test
    void cleanupDeletesOnlyMetadataOwnedByThisJobAndMatchingItsPlan() {
        OperationExecutionContext context = context();
        RecoveryArchiveImportPlan plan = plan();
        RecoveryManifest manifest = manifest(plan, context.jobId());
        RecoveryArchive existing = matchingArchive(context.jobId(), plan, manifest);
        RecoveryArchiveItem planned = RecoveryArchiveItem.builder().archiveId(context.jobId())
                .itemKey("record:a").itemType("RECORD")
                .objectKey("archives/tenant-a/" + context.jobId() + "/records/a.json")
                .byteSize(3L).sha256("d".repeat(64)).status(RecoveryItemStatus.VERIFIED).build();
        when(archives.findById(context.jobId())).thenReturn(Optional.of(existing));
        when(items.findByArchiveIdOrderByItemKey(context.jobId())).thenReturn(List.of(planned));

        persistence.delete(context, plan);

        InOrder order = inOrder(guard, items, archives);
        order.verify(guard).assertActive(context);
        order.verify(items).deleteAllInBatch(List.of(planned));
        order.verify(archives).delete(existing);

        existing.setSourceKnowledgeBaseId(UUID.randomUUID());
        assertThatThrownBy(() -> persistence.delete(context, plan))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");
    }

    private RecoveryManifest manifest(RecoveryArchiveImportPlan plan, UUID archiveId) {
        return new RecoveryManifestService(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules())
                .seal(plan.finalHeader(archiveId), plan.finalItems(archiveId));
    }
    private StoredRecoveryObject storedManifest(UUID archiveId, RecoveryManifest manifest) {
        byte[] bytes = new RecoveryManifestService(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules())
                .serialize(manifest);
        try {
            String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            return new StoredRecoveryObject("dupi-recovery", "archives/tenant-a/" + archiveId + "/manifest.json", bytes.length, sha);
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
    private RecoveryArchive matchingArchive(UUID id, RecoveryArchiveImportPlan plan, RecoveryManifest manifest) {
        return RecoveryArchive.builder().id(id).tenantId(plan.tenantId()).sourceKnowledgeBaseId(plan.knowledgeBaseId())
                .status(RecoveryArchiveStatus.COMPLETED).schemaVersion(1).bucket("dupi-recovery")
                .objectPrefix("archives/tenant-a/" + id + "/").sourceRevision(plan.sourceRevision())
                .itemCount(manifest.itemCount()).totalBytes(manifest.totalBytes())
                .manifestChecksum(manifest.manifestChecksum()).createdBy(plan.createdBy()).build();
    }
    private RecoveryArchiveImportPlan plan() {
        return new RecoveryArchiveImportPlan(UUID.randomUUID(), "tenant-a", UUID.randomUUID(),
                Instant.parse("2026-08-17T00:00:00Z"), "embedding", 3, Map.of(),
                "c".repeat(64), "a".repeat(64), "admin", List.of(
                new RecoveryArchiveImportPlan.Entry("record:a", "RECORD", "records/a.json", 3, "d".repeat(64))));
    }
    private OperationExecutionContext context() {
        return new OperationExecutionContext(UUID.randomUUID(), UUID.randomUUID(), 2, 1, OperationPhase.FORWARD);
    }
    private static RecoveryProperties properties() { RecoveryProperties value = new RecoveryProperties(); value.setBucket("dupi-recovery"); return value; }
}
