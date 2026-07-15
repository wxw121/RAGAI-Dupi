package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.domain.entity.*;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.domain.enums.RecoveryItemStatus;
import com.dupi.rag.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryArchiveServiceTest {
    @Mock RecoveryArchiveRepository archives;
    @Mock RecoveryArchiveItemRepository items;
    @Mock KnowledgeBaseService knowledgeBases;
    @Mock DocumentRepository documents;
    @Mock ChunkRepository chunks;
    @Mock RagEvalCaseRepository evalCases;
    @Mock RagQualityPolicyRepository qualityPolicies;
    @Mock RetrievalProfileRepository profiles;
    @Mock MinioStorageService documentStorage;
    @Mock RecoveryStorageService recoveryStorage;
    @Mock KnowledgeBaseMaintenanceService maintenance;

    private RecoveryArchiveService service;
    private RecoveryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RecoveryProperties();
        properties.setBucket("dupi-recovery");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        service = new RecoveryArchiveService(
                archives, items, knowledgeBases, documents, chunks, evalCases, qualityPolicies, profiles,
                documentStorage, recoveryStorage, maintenance, properties,
                new RecoveryManifestService(mapper), mapper);
    }

    @Test
    void createBindsTenantSourceRevisionAndStablePrefix() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = knowledgeBase(kbId, Instant.parse("2026-07-15T12:00:00Z"));
        when(knowledgeBases.findOrThrow(kbId)).thenReturn(kb);
        when(archives.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RecoveryArchive archive = service.create(kbId, "admin");

        assertThat(archive.getTenantId()).isEqualTo("tenant-a");
        assertThat(archive.getSourceRevision()).isEqualTo(kb.getUpdatedAt());
        assertThat(archive.getStatus()).isEqualTo(RecoveryArchiveStatus.PREPARING);
        assertThat(archive.getObjectPrefix()).isEqualTo(
                "archives/tenant-a/" + archive.getId() + "/");
    }

    @Test
    void captureSealsVerifiedRecordsObjectsAndManifest() {
        UUID kbId = UUID.randomUUID();
        UUID archiveId = UUID.randomUUID();
        KnowledgeBase kb = knowledgeBase(kbId, Instant.parse("2026-07-15T12:00:00Z"));
        RecoveryArchive archive = archive(archiveId, kb);
        Document document = Document.builder().id(UUID.randomUUID()).kbId(kbId).fileName("guide.md")
                .objectKey("tenant-a/kb/guide.md").mimeType("text/markdown").fileSize(5L).build();
        when(archives.findById(archiveId)).thenReturn(Optional.of(archive));
        when(knowledgeBases.findSystemOrThrow(kbId)).thenReturn(kb);
        when(documents.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(document));
        when(documentStorage.download(document.getObjectKey()))
                .thenReturn(new ByteArrayInputStream("guide".getBytes()));
        when(items.findByArchiveIdAndItemKey(any(), anyString())).thenReturn(Optional.empty());
        when(items.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(archives.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(recoveryStorage.put(anyString(), eq(archiveId), anyString(), any()))
                .thenAnswer(invocation -> {
                    String relative = invocation.getArgument(2);
                    return new StoredRecoveryObject("dupi-recovery",
                            "archives/tenant-a/" + archiveId + "/" + relative, 5, "abc123");
                });
        when(recoveryStorage.verify(any())).thenReturn(true);

        service.capture(archiveId);

        assertThat(archive.getStatus()).isEqualTo(RecoveryArchiveStatus.COMPLETED);
        assertThat(archive.getManifestChecksum()).isNotBlank();
        verify(maintenance).acquire(kbId, archiveId);
        verify(recoveryStorage).put(eq("tenant-a"), eq(archiveId), eq("objects/" + document.getId() + "/guide.md"), any());
        verify(maintenance).release(archiveId, RecoveryArchiveStatus.COMPLETED);
    }

    @Test
    void captureFailsAndRedactsExternalError() {
        UUID kbId = UUID.randomUUID();
        UUID archiveId = UUID.randomUUID();
        KnowledgeBase kb = knowledgeBase(kbId, Instant.now());
        RecoveryArchive archive = archive(archiveId, kb);
        when(archives.findById(archiveId)).thenReturn(Optional.of(archive));
        when(knowledgeBases.findSystemOrThrow(kbId)).thenReturn(kb);
        when(documents.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of());
        when(items.findByArchiveIdAndItemKey(any(), anyString())).thenReturn(Optional.empty());
        when(items.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(recoveryStorage.put(anyString(), any(), anyString(), any()))
                .thenThrow(new IllegalStateException("secret-key=do-not-expose"));

        assertThatThrownBy(() -> service.capture(archiveId)).isInstanceOf(IllegalStateException.class);

        assertThat(archive.getStatus()).isEqualTo(RecoveryArchiveStatus.FAILED);
        assertThat(archive.getErrorMessage()).doesNotContain("do-not-expose");
        verify(maintenance).release(archiveId, RecoveryArchiveStatus.FAILED);
    }

    @Test
    void captureRejectsSourceRevisionRaceBeforeSealingManifest() {
        UUID kbId = UUID.randomUUID();
        UUID archiveId = UUID.randomUUID();
        KnowledgeBase initial = knowledgeBase(kbId, Instant.parse("2026-07-15T12:00:00Z"));
        KnowledgeBase changed = knowledgeBase(kbId, Instant.parse("2026-07-15T12:01:00Z"));
        RecoveryArchive archive = archive(archiveId, initial);
        when(archives.findById(archiveId)).thenReturn(Optional.of(archive));
        when(knowledgeBases.findSystemOrThrow(kbId)).thenReturn(initial, changed);
        when(documents.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of());
        when(items.findByArchiveIdAndItemKey(any(), anyString())).thenReturn(Optional.empty());
        when(items.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(recoveryStorage.put(anyString(), any(), anyString(), any()))
                .thenAnswer(invocation -> new StoredRecoveryObject(
                        "dupi-recovery", "archives/tenant-a/" + archiveId + "/" + invocation.getArgument(2),
                        2, "aa"));
        when(recoveryStorage.verify(any())).thenReturn(true);

        assertThatThrownBy(() -> service.capture(archiveId)).isInstanceOf(IllegalStateException.class);

        assertThat(archive.getStatus()).isEqualTo(RecoveryArchiveStatus.FAILED);
        assertThat(archive.getManifestChecksum()).isNull();
        verify(recoveryStorage, never()).put(eq("tenant-a"), eq(archiveId), eq("manifest.json"), any());
    }

    private KnowledgeBase knowledgeBase(UUID id, Instant revision) {
        return KnowledgeBase.builder().id(id).tenantId("tenant-a").name("KB")
                .embeddingModel("embedding-2").embeddingDimension(1024).updatedAt(revision).build();
    }

    private RecoveryArchive archive(UUID archiveId, KnowledgeBase kb) {
        return RecoveryArchive.builder().id(archiveId).tenantId(kb.getTenantId())
                .sourceKnowledgeBaseId(kb.getId()).sourceRevision(kb.getUpdatedAt())
                .status(RecoveryArchiveStatus.PREPARING).bucket("dupi-recovery")
                .objectPrefix("archives/tenant-a/" + archiveId + "/").createdBy("admin").build();
    }
}
