package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.domain.entity.*;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.domain.enums.RecoveryItemStatus;
import com.dupi.rag.dto.recovery.RecoveryManifest;
import com.dupi.rag.dto.recovery.RecoveryManifestHeader;
import com.dupi.rag.dto.recovery.RecoveryManifestItem;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RecoveryArchiveService {
    private final RecoveryArchiveRepository archives;
    private final RecoveryArchiveItemRepository items;
    private final KnowledgeBaseService knowledgeBases;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final RagEvalCaseRepository evalCases;
    private final RagQualityPolicyRepository qualityPolicies;
    private final RetrievalProfileRepository profiles;
    private final MinioStorageService documentStorage;
    private final RecoveryStorageService recoveryStorage;
    private final KnowledgeBaseMaintenanceService maintenance;
    private final RecoveryProperties properties;
    private final RecoveryManifestService manifests;
    private final ObjectMapper mapper;

    @Transactional
    public RecoveryArchive create(UUID knowledgeBaseId, String actor) {
        KnowledgeBase kb = knowledgeBases.findOrThrow(knowledgeBaseId);
        UUID archiveId = UUID.randomUUID();
        RecoveryArchive archive = RecoveryArchive.builder()
                .id(archiveId)
                .tenantId(kb.getTenantId())
                .sourceKnowledgeBaseId(kb.getId())
                .status(RecoveryArchiveStatus.PREPARING)
                .schemaVersion(RecoveryManifestService.SCHEMA_VERSION)
                .bucket(properties.getBucket())
                .objectPrefix("archives/" + kb.getTenantId() + "/" + archiveId + "/")
                .sourceRevision(kb.getUpdatedAt())
                .createdBy(actor == null || actor.isBlank() ? "system" : actor)
                .build();
        return archives.save(archive);
    }

    public void capture(UUID archiveId) {
        RecoveryArchive archive = archive(archiveId);
        try {
            maintenance.acquire(archive.getSourceKnowledgeBaseId(), archiveId);
            archive.setStatus(RecoveryArchiveStatus.CAPTURING);
            archives.save(archive);
            KnowledgeBase kb = knowledgeBases.findSystemOrThrow(archive.getSourceKnowledgeBaseId());
            List<Document> documentRows = sorted(documents.findByKbIdOrderByCreatedAtDesc(kb.getId()));

            List<RecoveryManifestItem> evidence = new ArrayList<>();
            evidence.add(writeBytes(archive, "record:knowledge-base", "RECORD",
                    "records/knowledge-base.json", json(kb)));
            evidence.add(writeBytes(archive, "record:documents", "RECORD",
                    "records/documents.ndjson", ndjson(documentRows)));
            evidence.add(writeBytes(archive, "record:chunks", "RECORD",
                    "records/chunks.ndjson", ndjson(sorted(chunks.findByKbIdOrderByChunkIndexAsc(kb.getId())))));
            evidence.add(writeBytes(archive, "record:evaluation-cases", "RECORD",
                    "records/evaluation-cases.ndjson", ndjson(sorted(evalCases.findByKbIdOrderByCreatedAtAsc(kb.getId())))));
            evidence.add(writeBytes(archive, "record:quality-policy", "RECORD",
                    "records/quality-policy.json", json(qualityPolicies.findByKbId(kb.getId()).orElse(null))));
            evidence.add(writeBytes(archive, "record:retrieval-profiles", "RECORD",
                    "records/retrieval-profiles.ndjson", ndjson(sorted(profiles.findByKbIdOrderByVersionDesc(kb.getId())))));

            for (Document document : documentRows) {
                try (InputStream input = documentStorage.download(document.getObjectKey())) {
                    String relativeKey = "objects/" + document.getId() + "/" + safeFilename(document.getFileName());
                    evidence.add(write(archive, "object:" + document.getId(), "OBJECT",
                            document.getId().toString(), relativeKey, input));
                }
            }

            KnowledgeBase current = knowledgeBases.findSystemOrThrow(kb.getId());
            if (!Objects.equals(archive.getSourceRevision(), current.getUpdatedAt())) {
                throw new IllegalStateException("Knowledge base changed during recovery capture");
            }

            archive.setStatus(RecoveryArchiveStatus.VERIFYING);
            archives.save(archive);
            RecoveryManifest manifest = manifests.seal(new RecoveryManifestHeader(
                    RecoveryManifestService.SCHEMA_VERSION,
                    archive.getId(), archive.getTenantId(), kb.getId(), archive.getSourceRevision(),
                    kb.getEmbeddingModel(), kb.getEmbeddingDimension(),
                    Map.of("retrievalMode", kb.getRetrievalMode().name())), evidence);
            writeBytes(archive, "manifest", "MANIFEST", "manifest.json", manifests.serialize(manifest));

            archive.setItemCount(manifest.itemCount());
            archive.setTotalBytes(manifest.totalBytes());
            archive.setManifestChecksum(manifest.manifestChecksum());
            archive.setErrorCode(null);
            archive.setErrorMessage(null);
            archive.setStatus(RecoveryArchiveStatus.COMPLETED);
            archives.save(archive);
            maintenance.release(archiveId, RecoveryArchiveStatus.COMPLETED);
        } catch (Exception e) {
            archive.setStatus(RecoveryArchiveStatus.FAILED);
            archive.setErrorCode("RECOVERY_CAPTURE_FAILED");
            archive.setErrorMessage("Recovery capture failed; inspect item evidence and retry");
            archives.save(archive);
            maintenance.release(archiveId, RecoveryArchiveStatus.FAILED);
            throw new IllegalStateException("Recovery capture failed", e);
        }
    }

    private RecoveryManifestItem writeBytes(RecoveryArchive archive, String itemKey, String itemType,
                                            String relativeKey, byte[] bytes) {
        return write(archive, itemKey, itemType, null, relativeKey, new ByteArrayInputStream(bytes));
    }

    private RecoveryManifestItem write(RecoveryArchive archive, String itemKey, String itemType,
                                       String sourceId, String relativeKey, InputStream input) {
        Optional<RecoveryArchiveItem> existing = items.findByArchiveIdAndItemKey(archive.getId(), itemKey);
        if (existing.isPresent() && existing.get().getStatus() == RecoveryItemStatus.VERIFIED) {
            RecoveryArchiveItem item = existing.get();
            StoredRecoveryObject stored = stored(item, archive.getBucket());
            if (recoveryStorage.verify(stored)) {
                return manifestItem(item);
            }
        }

        RecoveryArchiveItem item = existing.orElseGet(() -> RecoveryArchiveItem.builder()
                .id(UUID.randomUUID())
                .archiveId(archive.getId())
                .itemKey(itemKey)
                .itemType(itemType)
                .sourceId(sourceId)
                .objectKey(archive.getObjectPrefix() + relativeKey)
                .status(RecoveryItemStatus.PENDING)
                .build());
        item.setAttemptCount(item.getAttemptCount() + 1);
        item.setStatus(RecoveryItemStatus.PENDING);
        item.setLastError(null);
        items.save(item);
        try {
            StoredRecoveryObject stored = recoveryStorage.put(
                    archive.getTenantId(), archive.getId(), relativeKey, input);
            item.setObjectKey(stored.objectKey());
            item.setByteSize(stored.byteSize());
            item.setSha256(stored.sha256());
            item.setStatus(RecoveryItemStatus.WRITTEN);
            items.save(item);
            if (!recoveryStorage.verify(stored)) {
                throw new IllegalStateException("Recovery object verification failed");
            }
            item.setStatus(RecoveryItemStatus.VERIFIED);
            items.save(item);
            return manifestItem(item);
        } catch (Exception e) {
            item.setStatus(RecoveryItemStatus.FAILED);
            item.setLastError("Recovery item write or verification failed");
            items.save(item);
            throw e;
        }
    }

    private RecoveryArchive archive(UUID archiveId) {
        return archives.findById(archiveId)
                .orElseThrow(() -> new ResourceNotFoundException("Recovery archive not found: " + archiveId));
    }

    private RecoveryManifestItem manifestItem(RecoveryArchiveItem item) {
        return new RecoveryManifestItem(item.getItemKey(), item.getItemType(), item.getObjectKey(),
                item.getByteSize(), item.getSha256());
    }

    private StoredRecoveryObject stored(RecoveryArchiveItem item, String bucket) {
        return new StoredRecoveryObject(bucket, item.getObjectKey(), item.getByteSize(), item.getSha256());
    }

    private byte[] json(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize recovery record", e);
        }
    }

    private byte[] ndjson(List<?> values) {
        StringBuilder output = new StringBuilder();
        for (Object value : values) {
            try {
                output.append(mapper.writeValueAsString(value)).append('\n');
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize recovery records", e);
            }
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private <T> List<T> sorted(List<T> values) {
        return values.stream().sorted(Comparator.comparing(this::entityId)).toList();
    }

    private UUID entityId(Object value) {
        if (value instanceof Document row) return row.getId();
        if (value instanceof Chunk row) return row.getId();
        if (value instanceof RagEvalCase row) return row.getId();
        if (value instanceof RetrievalProfile row) return row.getId();
        throw new IllegalArgumentException("Unsupported recovery record type: " + value.getClass().getName());
    }

    private String safeFilename(String filename) {
        String value = filename == null ? "document" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        return value.isBlank() ? "document" : value;
    }
}
