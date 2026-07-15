package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.domain.entity.*;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.domain.enums.RecoveryItemStatus;
import com.dupi.rag.domain.enums.RecoveryRestoreStatus;
import com.dupi.rag.dto.recovery.RecoveryManifest;
import com.dupi.rag.dto.recovery.VectorSnapshotPage;
import com.dupi.rag.dto.recovery.VectorSnapshotRow;
import com.dupi.rag.repository.*;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@RequiredArgsConstructor
public class DefaultRecoveryRestoreWriter implements RecoveryRestoreWriter {
    private static final int MAX_RECORD_BYTES = 256 * 1024 * 1024;
    private final RecoveryArchiveRepository archives;
    private final RecoveryArchiveItemRepository archiveItems;
    private final RecoveryRestoreItemRepository restoreItems;
    private final RecoveryRestoreJobRepository jobs;
    private final KnowledgeBaseRepository knowledgeBases;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final RagEvalCaseRepository evalCases;
    private final RagQualityPolicyRepository qualityPolicies;
    private final RetrievalProfileRepository profiles;
    private final RecoveryStorageService storage;
    private final MinioStorageService documentStorage;
    private final MilvusRecoveryService recoveryVectors;
    private final MilvusVectorService onlineVectors;
    private final SparseRecoveryProvisioner sparseProvisioner;
    private final RecoveryManifestService manifests;
    private final ObjectMapper mapper;

    @Override
    public void restore(RecoveryRestoreJob job) {
        RecoveryArchive archive = archives.findById(job.getArchiveId())
                .orElseThrow(() -> new IllegalArgumentException("Recovery archive no longer exists"));
        Map<String, RecoveryArchiveItem> itemMap = new HashMap<>();
        archiveItems.findByArchiveIdOrderByItemKey(archive.getId())
                .forEach(item -> itemMap.put(item.getItemKey(), item));
        RecoveryManifest manifest = readManifest(archive, required(itemMap, "manifest"));
        KnowledgeBase target = knowledgeBases.findById(job.getTargetKnowledgeBaseId())
                .orElseThrow(() -> new IllegalArgumentException("Recovery target no longer exists"));

        KnowledgeBase sourceKb = readJson(archive, required(itemMap, "record:knowledge-base"), KnowledgeBase.class);
        List<Document> sourceDocuments = readNdjson(archive, required(itemMap, "record:documents"), Document.class);
        List<Chunk> sourceChunks = readNdjson(archive, required(itemMap, "record:chunks"), Chunk.class);
        List<RagEvalCase> sourceCases = readNdjson(archive, required(itemMap, "record:evaluation-cases"), RagEvalCase.class);
        RagQualityPolicy sourcePolicy = readJson(archive, required(itemMap, "record:quality-policy"), RagQualityPolicy.class);
        List<RetrievalProfile> sourceProfiles = readNdjson(
                archive, required(itemMap, "record:retrieval-profiles"), RetrievalProfile.class);

        job.setStatus(RecoveryRestoreStatus.RESTORING_OBJECTS);
        jobs.save(job);
        restoreObjects(job, archive, itemMap, sourceDocuments);

        job.setStatus(RecoveryRestoreStatus.RESTORING_RECORDS);
        jobs.save(job);
        restoreRecords(job, target, sourceKb, sourceDocuments, sourceChunks, sourceCases,
                sourcePolicy, sourceProfiles);

        job.setStatus(RecoveryRestoreStatus.RESTORING_VECTORS);
        jobs.save(job);
        restoreVectors(job, archive, itemMap, manifest, sourceProfiles);

        job.setStatus(RecoveryRestoreStatus.VERIFYING);
        jobs.save(job);
        verify(job, sourceDocuments.size(), sourceChunks.size(), itemMap, manifest);
        target.setLifecycleStatus(KnowledgeBaseLifecycleStatus.READY);
        knowledgeBases.save(target);
    }

    @Override
    public void abandon(RecoveryRestoreJob job) {
        UUID targetId = job.getTargetKnowledgeBaseId();
        if (targetId == null) return;
        List<Document> targetDocuments = documents.findByKbIdOrderByCreatedAtDesc(targetId);
        targetDocuments.forEach(document -> documentStorage.delete(document.getObjectKey()));
        List<RetrievalProfile> targetProfiles = profiles.findByKbIdOrderByVersionDesc(targetId);
        onlineVectors.deleteByKbId(targetId);
        onlineVectors.deleteSparseByKbId(targetId, targetProfiles.stream().map(RetrievalProfile::getVersion).toList());
        chunks.deleteByKbId(targetId);
        evalCases.deleteAll(evalCases.findByKbIdOrderByCreatedAtAsc(targetId));
        qualityPolicies.findByKbId(targetId).ifPresent(qualityPolicies::delete);
        profiles.deleteAll(targetProfiles);
        documents.deleteAll(targetDocuments);
        knowledgeBases.deleteById(targetId);
    }

    static UUID remap(UUID restoreJobId, UUID sourceId) {
        return UUID.nameUUIDFromBytes(("restore:" + restoreJobId + ":" + sourceId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private void restoreObjects(RecoveryRestoreJob job, RecoveryArchive archive,
                                Map<String, RecoveryArchiveItem> itemMap, List<Document> sourceDocuments) {
        for (Document source : sourceDocuments) {
            RecoveryArchiveItem item = required(itemMap, "object:" + source.getId());
            UUID targetDocumentId = remap(job.getId(), source.getId());
            String objectKey = job.getTenantId() + "/" + job.getTargetKnowledgeBaseId()
                    + "/" + targetDocumentId + "/" + safeFilename(source.getFileName());
            runItem(job, item, targetDocumentId.toString(), () -> {
                try (InputStream input = storage.open(archive.getBucket(), item.getObjectKey())) {
                    documentStorage.upload(objectKey, input, item.getByteSize(), source.getMimeType());
                }
            });
        }
    }

    private void restoreRecords(RecoveryRestoreJob job, KnowledgeBase target, KnowledgeBase sourceKb,
                                List<Document> sourceDocuments, List<Chunk> sourceChunks,
                                List<RagEvalCase> sourceCases, RagQualityPolicy sourcePolicy,
                                List<RetrievalProfile> sourceProfiles) {
        target.setName(sourceKb.getName() + " (restored)");
        target.setDescription(sourceKb.getDescription());
        target.setChunkSize(sourceKb.getChunkSize());
        target.setChunkOverlap(sourceKb.getChunkOverlap());
        target.setTopK(sourceKb.getTopK());
        target.setChunkStrategy(sourceKb.getChunkStrategy());
        target.setRetrievalMode(sourceKb.getRetrievalMode());

        for (Document source : sourceDocuments) {
            UUID sourceId = source.getId();
            UUID targetId = remap(job.getId(), sourceId);
            source.setId(targetId);
            source.setKbId(job.getTargetKnowledgeBaseId());
            source.setObjectKey(job.getTenantId() + "/" + job.getTargetKnowledgeBaseId()
                    + "/" + targetId + "/" + safeFilename(source.getFileName()));
        }
        documents.saveAll(sourceDocuments);

        for (Chunk source : sourceChunks) {
            UUID sourceId = source.getId();
            UUID sourceDocumentId = source.getDocId();
            source.setId(remap(job.getId(), sourceId));
            source.setKbId(job.getTargetKnowledgeBaseId());
            source.setDocId(remap(job.getId(), sourceDocumentId));
            source.setMilvusId(source.getId().toString());
        }
        chunks.saveAll(sourceChunks);

        for (RagEvalCase source : sourceCases) {
            source.setId(remap(job.getId(), source.getId()));
            source.setKbId(job.getTargetKnowledgeBaseId());
        }
        evalCases.saveAll(sourceCases);

        if (sourcePolicy != null) {
            sourcePolicy.setId(remap(job.getId(), sourcePolicy.getId()));
            sourcePolicy.setKbId(job.getTargetKnowledgeBaseId());
            sourcePolicy.setBaselineRunId(null);
            qualityPolicies.save(sourcePolicy);
        }

        UUID sourceActiveProfileId = sourceKb.getActiveRetrievalProfileId();
        List<RetrievalProfile> restoredProfiles = sourceProfiles.stream().map(source -> RetrievalProfile.builder()
                .id(remap(job.getId(), source.getId()))
                .kbId(job.getTargetKnowledgeBaseId())
                .name(source.getName())
                .version(source.getVersion())
                .vectorCandidateCount(source.getVectorCandidateCount())
                .sparseCandidateCount(source.getSparseCandidateCount())
                .rrfConstant(source.getRrfConstant())
                .sparseIndexParams(source.getSparseIndexParams())
                .sparseSearchParams(source.getSparseSearchParams())
                .rerankEnabled(source.getRerankEnabled())
                .rerankCandidateLimit(source.getRerankCandidateLimit())
                .finalTopK(source.getFinalTopK())
                .createdAt(source.getCreatedAt())
                .build()).toList();
        profiles.saveAll(restoredProfiles);
        sourceProfiles.clear();
        sourceProfiles.addAll(restoredProfiles);
        target.setActiveRetrievalProfileId(sourceActiveProfileId == null
                ? null : remap(job.getId(), sourceActiveProfileId));
        knowledgeBases.save(target);
    }

    private void restoreVectors(RecoveryRestoreJob job, RecoveryArchive archive,
                                Map<String, RecoveryArchiveItem> itemMap, RecoveryManifest manifest,
                                List<RetrievalProfile> restoredProfiles) {
        RecoveryArchiveItem denseItem = required(itemMap, "vector:dense");
        List<VectorSnapshotRow> denseRows = remapRows(job, readNdjson(
                archive, denseItem, VectorSnapshotRow.class));
        MilvusRecoverySchema denseSchema = mapper.convertValue(
                manifest.header().collectionSettings().get("denseSchema"), MilvusRecoverySchema.class);
        recoveryVectors.upsert(recoveryVectors.denseCollection(), denseSchema, denseRows);

        RecoveryArchiveItem sparseItem = itemMap.get("vector:sparse");
        if (sparseItem != null) {
            int profileVersion = ((Number) manifest.header().collectionSettings()
                    .get("sparseProfileVersion")).intValue();
            RetrievalProfile profile = restoredProfiles.stream()
                    .filter(value -> value.getVersion() == profileVersion).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Restored sparse profile is missing"));
            sparseProvisioner.ensure(job.getTargetKnowledgeBaseId(),
                    manifest.header().embeddingDimension(), profileVersion, profile.getSparseIndexParams());
            String collection = recoveryVectors.sparseCollection(job.getTargetKnowledgeBaseId(), profileVersion);
            MilvusRecoverySchema sparseSchema = mapper.convertValue(
                    manifest.header().collectionSettings().get("sparseSchema"), MilvusRecoverySchema.class);
            recoveryVectors.upsert(collection, sparseSchema,
                    remapRows(job, readNdjson(archive, sparseItem, VectorSnapshotRow.class)));
        }
    }

    private void verify(RecoveryRestoreJob job, int documentCount, int chunkCount,
                        Map<String, RecoveryArchiveItem> itemMap, RecoveryManifest manifest) {
        if (documents.findByKbIdOrderByCreatedAtDesc(job.getTargetKnowledgeBaseId()).size() != documentCount
                || chunks.countByKbId(job.getTargetKnowledgeBaseId()) != chunkCount) {
            throw new IllegalStateException("Restored relational counts do not match archive");
        }
        List<VectorSnapshotRow> expectedDense = remapRows(job, readNdjson(
                archives.findById(job.getArchiveId()).orElseThrow(), required(itemMap, "vector:dense"),
                VectorSnapshotRow.class));
        if (recoveryVectors.count(recoveryVectors.denseCollection(), job.getTargetKnowledgeBaseId())
                != expectedDense.size()) {
            throw new IllegalStateException("Restored dense vector count does not match archive");
        }
        List<VectorSnapshotRow> actualDense = readAllDense(job.getTargetKnowledgeBaseId());
        if (!recoveryVectors.checksum(expectedDense).equals(recoveryVectors.checksum(actualDense))) {
            throw new IllegalStateException("Restored dense vector payload checksum does not match archive");
        }
    }

    private List<VectorSnapshotRow> readAllDense(UUID knowledgeBaseId) {
        List<VectorSnapshotRow> rows = new ArrayList<>();
        String cursor = null;
        do {
            VectorSnapshotPage page = recoveryVectors.readDense(knowledgeBaseId, cursor, 500);
            rows.addAll(page.rows());
            cursor = page.nextCursor();
        } while (cursor != null);
        return rows;
    }

    private List<VectorSnapshotRow> remapRows(RecoveryRestoreJob job, List<VectorSnapshotRow> rows) {
        return rows.stream().map(row -> new VectorSnapshotRow(
                remap(job.getId(), UUID.fromString(row.chunkId())).toString(),
                job.getTargetKnowledgeBaseId().toString(),
                remap(job.getId(), UUID.fromString(row.documentId())).toString(),
                row.content(), row.embedding(), row.scalarPayload())).toList();
    }

    private RecoveryManifest readManifest(RecoveryArchive archive, RecoveryArchiveItem item) {
        return manifests.parseAndValidate(storage.readSmall(
                archive.getBucket(), item.getObjectKey(), MAX_RECORD_BYTES));
    }

    private <T> T readJson(RecoveryArchive archive, RecoveryArchiveItem item, Class<T> type) {
        try {
            byte[] bytes = storage.readSmall(archive.getBucket(), item.getObjectKey(), MAX_RECORD_BYTES);
            if (bytes.length == 0 || "null".equals(new String(bytes, StandardCharsets.UTF_8).trim())) return null;
            return mapper.readValue(bytes, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Recovery record is invalid: " + item.getItemKey(), e);
        }
    }

    private <T> List<T> readNdjson(RecoveryArchive archive, RecoveryArchiveItem item, Class<T> type) {
        byte[] bytes = storage.readSmall(archive.getBucket(), item.getObjectKey(), MAX_RECORD_BYTES);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.isBlank()) return new ArrayList<>();
        List<T> values = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            try {
                values.add(mapper.readValue(line, type));
            } catch (Exception e) {
                throw new IllegalArgumentException("Recovery NDJSON is invalid: " + item.getItemKey(), e);
            }
        }
        return values;
    }

    private void runItem(RecoveryRestoreJob job, RecoveryArchiveItem archiveItem,
                         String targetId, CheckedAction action) {
        RecoveryRestoreItem progress = restoreItems.findByRestoreJobIdAndArchiveItemId(
                        job.getId(), archiveItem.getId())
                .orElseGet(() -> RecoveryRestoreItem.builder().id(UUID.randomUUID())
                        .restoreJobId(job.getId()).archiveItemId(archiveItem.getId())
                        .status(RecoveryItemStatus.PENDING).build());
        if (progress.getStatus() == RecoveryItemStatus.VERIFIED) return;
        progress.setAttemptCount(progress.getAttemptCount() + 1);
        progress.setTargetId(targetId);
        restoreItems.save(progress);
        try {
            action.run();
            progress.setStatus(RecoveryItemStatus.VERIFIED);
            progress.setLastError(null);
            restoreItems.save(progress);
        } catch (Exception e) {
            progress.setStatus(RecoveryItemStatus.FAILED);
            progress.setLastError("Recovery item restore failed");
            restoreItems.save(progress);
            throw new IllegalStateException("Recovery item restore failed", e);
        }
    }

    private RecoveryArchiveItem required(Map<String, RecoveryArchiveItem> items, String key) {
        RecoveryArchiveItem item = items.get(key);
        if (item == null) throw new IllegalArgumentException("Recovery archive item is missing: " + key);
        return item;
    }

    private String safeFilename(String filename) {
        String value = filename == null ? "document" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        return value.isBlank() ? "document" : value;
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }
}
