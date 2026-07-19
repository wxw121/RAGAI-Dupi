package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.domain.entity.*;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.domain.enums.RecoveryItemStatus;
import com.dupi.rag.dto.recovery.VectorSnapshotPage;
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
import java.io.ByteArrayOutputStream;
import com.dupi.rag.config.TenantContext;

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
    @Mock MilvusRecoveryService recoveryVectors;
    @Mock AuditLogService auditLogService;

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
                new RecoveryManifestService(mapper), recoveryVectors, mapper, auditLogService);
        lenient().when(recoveryVectors.readDense(any(), any(), anyInt()))
                .thenReturn(new VectorSnapshotPage(List.of(), null, "empty"));
        lenient().when(recoveryVectors.denseCollection()).thenReturn("chunks");
        lenient().when(recoveryVectors.profileCollection()).thenReturn("chunks_profiles_v2");
        lenient().when(recoveryVectors.describe("chunks"))
                .thenReturn(new MilvusRecoverySchema("COSINE", 1024, java.util.Map.of()));
        lenient().when(recoveryVectors.serializeRows(anyList())).thenReturn(new byte[0]);
        lenient().when(recoveryVectors.readProfile(any(), any(), anyInt()))
                .thenReturn(new VectorSnapshotPage(List.of(), null, "empty"));
    }

    @Test
    void createBindsTenantSourceRevisionAndStablePrefix() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = knowledgeBase(kbId, Instant.parse("2026-07-15T12:00:00Z"));
        when(knowledgeBases.findOrThrow(kbId)).thenReturn(kb);
        when(archives.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RecoveryArchive archive = service.create(kbId, null);

        assertThat(archive.getTenantId()).isEqualTo("tenant-a");
        assertThat(archive.getSourceRevision()).isEqualTo(kb.getUpdatedAt());
        assertThat(archive.getStatus()).isEqualTo(RecoveryArchiveStatus.PREPARING);
        assertThat(archive.getCreatedBy()).isEqualTo("system");
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
        Chunk chunk = Chunk.builder().id(UUID.randomUUID()).kbId(kbId).docId(document.getId())
                .chunkIndex(0).content("guide").build();
        RagEvalCase evalCase = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("case").query("guide").minHits(1).topK(3).build();
        RetrievalProfile profile = RetrievalProfile.builder().id(UUID.randomUUID()).kbId(kbId)
                .name("hybrid").version(2).vectorCandidateCount(10).sparseCandidateCount(10)
                .rrfConstant(60).rerankCandidateLimit(5).finalTopK(3).build();
        kb.setActiveRetrievalProfileId(profile.getId());
        kb.setProfileIndexActivated(true);
        when(archives.findById(archiveId)).thenReturn(Optional.of(archive));
        when(knowledgeBases.findSystemOrThrow(kbId)).thenReturn(kb);
        when(documents.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(document));
        when(chunks.findByKbIdOrderByChunkIndexAsc(kbId)).thenReturn(List.of(chunk));
        when(evalCases.findByKbIdOrderByCreatedAtAsc(kbId)).thenReturn(List.of(evalCase));
        when(profiles.findByKbIdOrderByVersionDesc(kbId)).thenReturn(List.of(profile));
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
        when(recoveryVectors.sparseCollection(kbId, 2)).thenReturn("chunks_sparse_2");
        when(recoveryVectors.describe("chunks_sparse_2"))
                .thenReturn(new MilvusRecoverySchema("IP", 1024, java.util.Map.of()));
        when(recoveryVectors.readSparse(eq(kbId), eq(2), any(), anyInt()))
                .thenReturn(new VectorSnapshotPage(List.of(), null, "empty"));
        when(recoveryVectors.describe("chunks_profiles_v2"))
                .thenReturn(new MilvusRecoverySchema("COSINE", 1024, java.util.Map.of()));

        service.capture(archiveId);

        assertThat(archive.getStatus()).isEqualTo(RecoveryArchiveStatus.COMPLETED);
        assertThat(archive.getManifestChecksum()).isNotBlank();
        verify(maintenance).acquire(kbId, archiveId);
        verify(archives, atLeast(2)).findById(archiveId);
        verify(recoveryStorage).put(eq("tenant-a"), eq(archiveId), eq("objects/" + document.getId() + "/guide.md"), any());
        verify(recoveryStorage).put(eq("tenant-a"), eq(archiveId), eq("vectors/dense.ndjson"), any());
        verify(recoveryStorage).put(eq("tenant-a"), eq(archiveId), eq("vectors/profile.ndjson"), any());
        verify(recoveryStorage).put(eq("tenant-a"), eq(archiveId), eq("vectors/sparse.ndjson"), any());
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

    @Test
    void listGetRetryDownloadDeleteAndCapacityFailureRemainTenantScoped() {
        UUID kbId = UUID.randomUUID();
        RecoveryArchive archive = archive(UUID.randomUUID(), knowledgeBase(kbId, Instant.now()));
        archive.setStatus(RecoveryArchiveStatus.FAILED);
        when(knowledgeBases.findOrThrow(kbId)).thenReturn(knowledgeBase(kbId, Instant.now()));
        TenantContext.setTenantId("tenant-a");
        when(archives.findByTenantIdAndSourceKnowledgeBaseIdOrderByCreatedAtDesc("tenant-a", kbId))
                .thenReturn(List.of(archive));
        when(archives.findByIdAndTenantId(archive.getId(), "tenant-a")).thenReturn(Optional.of(archive));
        when(archives.findById(archive.getId())).thenReturn(Optional.of(archive));
        when(archives.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.list(kbId)).containsExactly(archive);
        assertThat(service.get(kbId, archive.getId())).isSameAs(archive);
        assertThat(service.prepareRetry(kbId, archive.getId()).getStatus())
                .isEqualTo(RecoveryArchiveStatus.PREPARING);
        service.markFailed(archive.getId(), "RECOVERY_CAPACITY_EXCEEDED");
        assertThat(archive.getStatus()).isEqualTo(RecoveryArchiveStatus.FAILED);
        archive.setStatus(RecoveryArchiveStatus.COMPLETED);
        assertThat(service.getSystem(archive.getId())).isSameAs(archive);
        service.download(kbId, archive.getId(), new ByteArrayOutputStream());
        service.delete(kbId, archive.getId());

        verify(recoveryStorage).streamZip(eq("tenant-a"), eq(archive.getId()), any());
        verify(recoveryStorage).deleteArchive("tenant-a", archive.getId());
        verify(archives).delete(archive);
        TenantContext.clear();
    }

    @Test
    void managementCommandsRejectMismatchedKnowledgeBaseAndActiveStates() {
        UUID kbId = UUID.randomUUID();
        RecoveryArchive archive = archive(UUID.randomUUID(), knowledgeBase(UUID.randomUUID(), Instant.now()));
        TenantContext.setTenantId("tenant-a");
        when(knowledgeBases.findOrThrow(kbId)).thenReturn(knowledgeBase(kbId, Instant.now()));
        when(archives.findByIdAndTenantId(archive.getId(), "tenant-a")).thenReturn(Optional.of(archive));

        assertThatThrownBy(() -> service.get(kbId, archive.getId())).hasMessageContaining("not found");

        archive.setSourceKnowledgeBaseId(kbId);
        assertThatThrownBy(() -> service.prepareRetry(kbId, archive.getId()))
                .hasMessageContaining("failed");
        assertThatThrownBy(() -> service.delete(kbId, archive.getId()))
                .hasMessageContaining("Active");
        assertThatThrownBy(() -> service.download(kbId, archive.getId(), new ByteArrayOutputStream()))
                .hasMessageContaining("completed");
        assertThatThrownBy(() -> service.getSystem(UUID.randomUUID()))
                .hasMessageContaining("Recovery archive not found");
        TenantContext.clear();
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
