package com.dupi.rag.service;

import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RecoveryArchive;
import com.dupi.rag.domain.entity.RecoveryArchiveItem;
import com.dupi.rag.domain.entity.RecoveryRestoreJob;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.domain.enums.RecoveryRestoreStatus;
import com.dupi.rag.dto.recovery.RecoveryManifest;
import com.dupi.rag.dto.recovery.RecoveryManifestHeader;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import com.dupi.rag.repository.RecoveryArchiveItemRepository;
import com.dupi.rag.repository.RecoveryArchiveRepository;
import com.dupi.rag.repository.RecoveryRestoreJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryRestoreServiceTest {
    @Mock RecoveryArchiveRepository archives;
    @Mock RecoveryArchiveItemRepository archiveItems;
    @Mock RecoveryRestoreJobRepository jobs;
    @Mock KnowledgeBaseRepository knowledgeBases;
    @Mock RecoveryStorageService storage;
    @Mock RecoveryRestoreWriter writer;

    private RecoveryManifestService manifests;
    private RecoveryRestoreService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("tenant-a");
        manifests = new RecoveryManifestService(new ObjectMapper().findAndRegisterModules());
        service = new RecoveryRestoreService(
                archives, archiveItems, jobs, knowledgeBases, storage, manifests, writer);
    }

    @AfterEach
    void tearDown() { TenantContext.clear(); }

    @Test
    void corruptItemBlocksBeforeTargetAllocation() {
        RecoveryArchive archive = archive();
        RecoveryArchiveItem manifestItem = manifestItem(archive, manifest(archive));
        when(archives.findByIdAndTenantId(archive.getId(), "tenant-a")).thenReturn(Optional.of(archive));
        when(archiveItems.findByArchiveIdOrderByItemKey(archive.getId())).thenReturn(List.of(manifestItem));
        when(storage.verify(any())).thenReturn(false);

        assertThatThrownBy(() -> service.create(archive.getId(), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verification");

        verify(knowledgeBases, never()).save(any());
        verify(jobs, never()).save(any());
    }

    @Test
    void validArchiveAllocatesOneHiddenTarget() {
        RecoveryArchive archive = archive();
        RecoveryManifest manifest = manifest(archive);
        RecoveryArchiveItem manifestItem = manifestItem(archive, manifest);
        when(archives.findByIdAndTenantId(archive.getId(), "tenant-a")).thenReturn(Optional.of(archive));
        when(archiveItems.findByArchiveIdOrderByItemKey(archive.getId())).thenReturn(List.of(manifestItem));
        when(storage.verify(any())).thenReturn(true);
        when(storage.readSmall(any(), anyString(), anyInt())).thenReturn(manifests.serialize(manifest));
        when(knowledgeBases.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RecoveryRestoreJob job = service.create(archive.getId(), "admin");

        assertThat(job.getTargetKnowledgeBaseId()).isNotNull();
        assertThat(job.getStatus()).isEqualTo(RecoveryRestoreStatus.VALIDATING);
        verify(knowledgeBases).save(argThat(kb ->
                kb.getLifecycleStatus() == KnowledgeBaseLifecycleStatus.RESTORING
                        && kb.getId().equals(job.getTargetKnowledgeBaseId())));
    }

    @Test
    void retryReusesTargetAndCompletesSameJob() {
        UUID targetId = UUID.randomUUID();
        RecoveryRestoreJob job = RecoveryRestoreJob.builder().id(UUID.randomUUID())
                .archiveId(UUID.randomUUID()).tenantId("tenant-a").targetKnowledgeBaseId(targetId)
                .status(RecoveryRestoreStatus.FAILED).createdBy("admin").build();
        when(jobs.findByIdAndTenantId(job.getId(), "tenant-a")).thenReturn(Optional.of(job));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.retry(job.getId());

        assertThat(job.getTargetKnowledgeBaseId()).isEqualTo(targetId);
        assertThat(job.getStatus()).isEqualTo(RecoveryRestoreStatus.COMPLETED);
        verify(writer).restore(job);
    }

    @Test
    void executeRunsValidatedJobForFirstTime() {
        RecoveryRestoreJob job = RecoveryRestoreJob.builder().id(UUID.randomUUID())
                .archiveId(UUID.randomUUID()).tenantId("tenant-a").targetKnowledgeBaseId(UUID.randomUUID())
                .status(RecoveryRestoreStatus.VALIDATING).createdBy("admin").build();
        when(jobs.findByIdAndTenantId(job.getId(), "tenant-a")).thenReturn(Optional.of(job));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.execute(job.getId());

        assertThat(job.getStatus()).isEqualTo(RecoveryRestoreStatus.COMPLETED);
        verify(writer).restore(job);
    }

    private RecoveryArchive archive() {
        return RecoveryArchive.builder().id(UUID.randomUUID()).tenantId("tenant-a")
                .sourceKnowledgeBaseId(UUID.randomUUID()).status(RecoveryArchiveStatus.COMPLETED)
                .schemaVersion(1).bucket("dupi-recovery").objectPrefix("archives/tenant-a/a/")
                .sourceRevision(Instant.parse("2026-07-15T12:00:00Z")).manifestChecksum("checksum")
                .createdBy("admin").build();
    }

    private RecoveryManifest manifest(RecoveryArchive archive) {
        return manifests.seal(new RecoveryManifestHeader(1, archive.getId(), archive.getTenantId(),
                archive.getSourceKnowledgeBaseId(), archive.getSourceRevision(), "embedding-2", 1024,
                Map.of("retrievalMode", "VECTOR")), List.of());
    }

    private RecoveryArchiveItem manifestItem(RecoveryArchive archive, RecoveryManifest manifest) {
        archive.setManifestChecksum(manifest.manifestChecksum());
        return RecoveryArchiveItem.builder().id(UUID.randomUUID()).archiveId(archive.getId())
                .itemKey("manifest").itemType("MANIFEST").objectKey(archive.getObjectPrefix() + "manifest.json")
                .byteSize((long) manifests.serialize(manifest).length).sha256("sha").build();
    }
}
