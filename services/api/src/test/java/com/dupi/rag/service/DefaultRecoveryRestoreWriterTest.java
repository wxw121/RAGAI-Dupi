package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.domain.entity.*;
import com.dupi.rag.domain.enums.*;
import com.dupi.rag.dto.recovery.*;
import com.dupi.rag.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultRecoveryRestoreWriterTest {
    @Test
    void remapIsStableWithinJobAndDistinctAcrossJobs() {
        UUID source = UUID.randomUUID();
        UUID firstJob = UUID.randomUUID();
        UUID secondJob = UUID.randomUUID();

        UUID first = DefaultRecoveryRestoreWriter.remap(firstJob, source);

        assertThat(DefaultRecoveryRestoreWriter.remap(firstJob, source)).isEqualTo(first);
        assertThat(DefaultRecoveryRestoreWriter.remap(secondJob, source)).isNotEqualTo(first);
        assertThat(first).isNotEqualTo(source);
    }

    @Test
    void restoreRebuildsObjectsRecordsVectorsAndMakesTargetReady() throws Exception {
        Fixture fixture = fixture();

        fixture.writer.restore(fixture.job);

        assertThat(fixture.target.getLifecycleStatus()).isEqualTo(KnowledgeBaseLifecycleStatus.READY);
        assertThat(fixture.target.getName()).isEqualTo("Source KB (restored)");
        verify(fixture.documentStorage).upload(contains(fixture.target.getId().toString()), any(), eq(5L), eq("text/markdown"));
        verify(fixture.documents).saveAll(argThat(values -> values.iterator().next().getKbId().equals(fixture.target.getId())));
        verify(fixture.chunks).saveAll(argThat(values -> values.iterator().next().getKbId().equals(fixture.target.getId())));
        verify(fixture.recoveryVectors).upsert(eq("chunks"), any(), argThat(rows -> rows.size() == 1
                && rows.get(0).knowledgeBaseId().equals(fixture.target.getId().toString())));
        verify(fixture.provisioner).ensure(fixture.target.getId(), 2, 1, Map.of());
        verify(fixture.recoveryVectors).upsert(eq("sparse-target"), any(), anyList());
        verify(fixture.jobs, atLeast(4)).save(fixture.job);
    }

    @Test
    void abandonRemovesExternalAndRelationalTargetData() {
        Fixture fixture = fixture();
        Document targetDocument = Document.builder().id(UUID.randomUUID()).kbId(fixture.target.getId())
                .fileName("a.md").objectKey("target/a.md").mimeType("text/markdown").build();
        RetrievalProfile targetProfile = RetrievalProfile.builder().id(UUID.randomUUID())
                .kbId(fixture.target.getId()).name("p").version(3).vectorCandidateCount(10)
                .sparseCandidateCount(10).rrfConstant(60).rerankCandidateLimit(5).finalTopK(3).build();
        when(fixture.documents.findByKbIdOrderByCreatedAtDesc(fixture.target.getId()))
                .thenReturn(List.of(targetDocument));
        when(fixture.profiles.findByKbIdOrderByVersionDesc(fixture.target.getId()))
                .thenReturn(List.of(targetProfile));

        fixture.writer.abandon(fixture.job);

        verify(fixture.documentStorage).delete("target/a.md");
        verify(fixture.onlineVectors).deleteByKbId(fixture.target.getId());
        verify(fixture.onlineVectors).deleteSparseByKbId(fixture.target.getId(), List.of(3));
        verify(fixture.knowledgeBases).deleteById(fixture.target.getId());
    }

    @Test
    void restoreRejectsRelationalCountMismatchBeforeReadiness() {
        Fixture fixture = fixture();
        when(fixture.documents.findByKbIdOrderByCreatedAtDesc(fixture.target.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> fixture.writer.restore(fixture.job))
                .hasMessageContaining("relational counts");
        assertThat(fixture.target.getLifecycleStatus()).isEqualTo(KnowledgeBaseLifecycleStatus.RESTORING);
    }

    @Test
    void restoreRejectsDenseVectorCountMismatchBeforeReadiness() {
        Fixture fixture = fixture();
        when(fixture.recoveryVectors.count("chunks", fixture.target.getId())).thenReturn(0L);

        assertThatThrownBy(() -> fixture.writer.restore(fixture.job))
                .hasMessageContaining("vector count");
        assertThat(fixture.target.getLifecycleStatus()).isEqualTo(KnowledgeBaseLifecycleStatus.RESTORING);
    }

    @Test
    void restoreRejectsDenseVectorChecksumMismatchBeforeReadiness() {
        Fixture fixture = fixture();
        when(fixture.recoveryVectors.checksum(anyList())).thenReturn("expected", "actual");

        assertThatThrownBy(() -> fixture.writer.restore(fixture.job))
                .hasMessageContaining("checksum");
        assertThat(fixture.target.getLifecycleStatus()).isEqualTo(KnowledgeBaseLifecycleStatus.RESTORING);
    }

    @Test
    void restoreRecordsItemFailureWhenObjectUploadFails() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("storage unavailable")).when(fixture.documentStorage)
                .upload(anyString(), any(), anyLong(), anyString());

        assertThatThrownBy(() -> fixture.writer.restore(fixture.job))
                .hasMessageContaining("item restore failed");
        assertThat(fixture.target.getLifecycleStatus()).isEqualTo(KnowledgeBaseLifecycleStatus.RESTORING);
    }

    private Fixture fixture() {
        RecoveryArchiveRepository archives = mock(RecoveryArchiveRepository.class);
        RecoveryArchiveItemRepository archiveItems = mock(RecoveryArchiveItemRepository.class);
        RecoveryRestoreItemRepository restoreItems = mock(RecoveryRestoreItemRepository.class);
        RecoveryRestoreJobRepository jobs = mock(RecoveryRestoreJobRepository.class);
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        ChunkRepository chunks = mock(ChunkRepository.class);
        RagEvalCaseRepository evalCases = mock(RagEvalCaseRepository.class);
        RagQualityPolicyRepository policies = mock(RagQualityPolicyRepository.class);
        RetrievalProfileRepository profiles = mock(RetrievalProfileRepository.class);
        RecoveryStorageService storage = mock(RecoveryStorageService.class);
        MinioStorageService documentStorage = mock(MinioStorageService.class);
        MilvusRecoveryService recoveryVectors = mock(MilvusRecoveryService.class);
        MilvusVectorService onlineVectors = mock(MilvusVectorService.class);
        SparseRecoveryProvisioner provisioner = mock(SparseRecoveryProvisioner.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RecoveryManifestService manifests = new RecoveryManifestService(mapper);

        UUID archiveId = UUID.randomUUID();
        UUID sourceKbId = UUID.randomUUID();
        UUID targetKbId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID sourceDocId = UUID.randomUUID();
        UUID sourceChunkId = UUID.randomUUID();
        KnowledgeBase sourceKb = KnowledgeBase.builder().id(sourceKbId).tenantId("tenant-a").name("Source KB")
                .embeddingModel("embedding-2").embeddingDimension(2).retrievalMode(RetrievalMode.VECTOR)
                .updatedAt(Instant.parse("2026-07-15T12:00:00Z")).build();
        KnowledgeBase target = KnowledgeBase.builder().id(targetKbId).tenantId("tenant-a").name("Restoring")
                .embeddingModel("embedding-2").embeddingDimension(2)
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.RESTORING).build();
        Document sourceDocument = Document.builder().id(sourceDocId).kbId(sourceKbId).fileName("guide.md")
                .objectKey("source/guide.md").mimeType("text/markdown").fileSize(5L)
                .status(DocumentStatus.COMPLETED).build();
        Chunk sourceChunk = Chunk.builder().id(sourceChunkId).kbId(sourceKbId).docId(sourceDocId)
                .chunkIndex(0).content("hello").tokenCount(1).metadata(Map.of()).build();
        RagEvalCase sourceCase = RagEvalCase.builder().id(UUID.randomUUID()).kbId(sourceKbId)
                .caseKey("case").query("hello").minHits(1).topK(3).mustContainAny(List.of()).build();
        RagQualityPolicy sourcePolicy = RagQualityPolicy.builder().id(UUID.randomUUID()).kbId(sourceKbId).build();
        RetrievalProfile sourceProfile = RetrievalProfile.builder().id(UUID.randomUUID()).kbId(sourceKbId)
                .name("balanced").version(1).vectorCandidateCount(10).sparseCandidateCount(10)
                .rrfConstant(60).rerankEnabled(false).rerankCandidateLimit(5).finalTopK(3).build();
        sourceKb.setActiveRetrievalProfileId(sourceProfile.getId());
        VectorSnapshotRow sourceVector = new VectorSnapshotRow(sourceChunkId.toString(), sourceKbId.toString(),
                sourceDocId.toString(), "hello", List.of(0.1f, 0.2f), Map.of());

        RecoveryArchive archive = RecoveryArchive.builder().id(archiveId).tenantId("tenant-a")
                .sourceKnowledgeBaseId(sourceKbId).status(RecoveryArchiveStatus.COMPLETED)
                .bucket("dupi-recovery").objectPrefix("archives/tenant-a/a/").createdBy("admin").build();
        RecoveryRestoreJob job = RecoveryRestoreJob.builder().id(jobId).archiveId(archiveId).tenantId("tenant-a")
                .targetKnowledgeBaseId(targetKbId).status(RecoveryRestoreStatus.VALIDATING).createdBy("admin").build();
        MilvusRecoverySchema denseSchema = new MilvusRecoverySchema("COSINE", 2, Map.of());
        RecoveryManifest manifest = manifests.seal(new RecoveryManifestHeader(1, archiveId, "tenant-a", sourceKbId,
                sourceKb.getUpdatedAt(), "embedding-2", 2,
                Map.of("retrievalMode", "HYBRID", "denseSchema", denseSchema,
                        "sparseProfileVersion", 1, "sparseSchema", denseSchema)), List.of());

        Map<String, byte[]> payloads = new HashMap<>();
        List<RecoveryArchiveItem> itemRows = new ArrayList<>();
        add(itemRows, payloads, archiveId, "manifest", "manifest.json", manifests.serialize(manifest), null);
        add(itemRows, payloads, archiveId, "record:knowledge-base", "records/kb.json", json(mapper, sourceKb), null);
        add(itemRows, payloads, archiveId, "record:documents", "records/docs.ndjson", ndjson(mapper, sourceDocument), null);
        add(itemRows, payloads, archiveId, "record:chunks", "records/chunks.ndjson", ndjson(mapper, sourceChunk), null);
        add(itemRows, payloads, archiveId, "record:evaluation-cases", "records/cases.ndjson", ndjson(mapper, sourceCase), null);
        add(itemRows, payloads, archiveId, "record:quality-policy", "records/policy.json", json(mapper, sourcePolicy), null);
        add(itemRows, payloads, archiveId, "record:retrieval-profiles", "records/profiles.ndjson", ndjson(mapper, sourceProfile), null);
        add(itemRows, payloads, archiveId, "object:" + sourceDocId, "objects/guide.md", "guide".getBytes(), sourceDocId.toString());
        add(itemRows, payloads, archiveId, "vector:dense", "vectors/dense.ndjson", ndjson(mapper, sourceVector), null);
        add(itemRows, payloads, archiveId, "vector:sparse", "vectors/sparse.ndjson", ndjson(mapper, sourceVector), null);

        when(archives.findById(archiveId)).thenReturn(Optional.of(archive));
        when(archiveItems.findByArchiveIdOrderByItemKey(archiveId)).thenReturn(itemRows);
        when(knowledgeBases.findById(targetKbId)).thenReturn(Optional.of(target));
        when(storage.readSmall(eq("dupi-recovery"), anyString(), anyInt()))
                .thenAnswer(invocation -> payloads.get(invocation.getArgument(1)));
        when(storage.open(eq("dupi-recovery"), anyString()))
                .thenAnswer(invocation -> new ByteArrayInputStream(payloads.get(invocation.getArgument(1))));
        when(restoreItems.findByRestoreJobIdAndArchiveItemId(any(), any())).thenReturn(Optional.empty());
        when(restoreItems.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(documents.findByKbIdOrderByCreatedAtDesc(targetKbId)).thenReturn(List.of(sourceDocument));
        when(chunks.countByKbId(targetKbId)).thenReturn(1L);
        when(recoveryVectors.denseCollection()).thenReturn("chunks");
        when(recoveryVectors.sparseCollection(targetKbId, 1)).thenReturn("sparse-target");
        when(recoveryVectors.count("chunks", targetKbId)).thenReturn(1L);
        VectorSnapshotRow targetVector = new VectorSnapshotRow(
                DefaultRecoveryRestoreWriter.remap(jobId, sourceChunkId).toString(), targetKbId.toString(),
                DefaultRecoveryRestoreWriter.remap(jobId, sourceDocId).toString(), "hello",
                List.of(0.1f, 0.2f), Map.of());
        when(recoveryVectors.readDense(eq(targetKbId), any(), anyInt()))
                .thenReturn(new VectorSnapshotPage(List.of(targetVector), null, "sum"));
        when(recoveryVectors.checksum(anyList())).thenReturn("same");

        DefaultRecoveryRestoreWriter writer = new DefaultRecoveryRestoreWriter(
                archives, archiveItems, restoreItems, jobs, knowledgeBases, documents, chunks, evalCases,
                policies, profiles, storage, documentStorage, recoveryVectors, onlineVectors, provisioner,
                manifests, mapper);
        return new Fixture(writer, job, target, jobs, knowledgeBases, documents, chunks, profiles,
                documentStorage, recoveryVectors, onlineVectors, provisioner);
    }

    private void add(List<RecoveryArchiveItem> items, Map<String, byte[]> payloads, UUID archiveId,
                     String itemKey, String objectKey, byte[] bytes, String sourceId) {
        RecoveryArchiveItem item = RecoveryArchiveItem.builder().id(UUID.randomUUID()).archiveId(archiveId)
                .itemKey(itemKey).itemType("TEST").sourceId(sourceId).objectKey(objectKey)
                .byteSize((long) bytes.length).sha256("sha").status(RecoveryItemStatus.VERIFIED).build();
        items.add(item);
        payloads.put(objectKey, bytes);
    }

    private byte[] json(ObjectMapper mapper, Object value) {
        try { return mapper.writeValueAsBytes(value); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private byte[] ndjson(ObjectMapper mapper, Object value) {
        return (new String(json(mapper, value), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(DefaultRecoveryRestoreWriter writer, RecoveryRestoreJob job, KnowledgeBase target,
                           RecoveryRestoreJobRepository jobs, KnowledgeBaseRepository knowledgeBases,
                           DocumentRepository documents, ChunkRepository chunks, RetrievalProfileRepository profiles,
                           MinioStorageService documentStorage, MilvusRecoveryService recoveryVectors,
                           MilvusVectorService onlineVectors, SparseRecoveryProvisioner provisioner) { }
}
