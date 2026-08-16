package com.dupi.rag.service;

import com.dupi.rag.dto.recovery.RecoveryManifestHeader;
import com.dupi.rag.dto.recovery.RecoveryManifestItem;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable, validated import intent persisted on the durable operation before staging is uploaded. */
public record RecoveryArchiveImportPlan(
        UUID sourceArchiveId,
        String tenantId,
        UUID knowledgeBaseId,
        Instant sourceRevision,
        String embeddingModel,
        int embeddingDimension,
        Map<String, Object> collectionSettings,
        String sourceManifestChecksum,
        String zipSha256,
        String createdBy,
        List<Entry> entries
) {
    public record Entry(String itemKey, String itemType, String relativePath, long byteSize, String sha256) { }

    public Map<String, Object> toInput() {
        List<Map<String, Object>> encoded = entries.stream().map(entry -> Map.<String, Object>of(
                "itemKey", entry.itemKey(), "itemType", entry.itemType(), "relativePath", entry.relativePath(),
                "byteSize", entry.byteSize(), "sha256", entry.sha256())).toList();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sourceArchiveId", sourceArchiveId.toString());
        input.put("tenantId", tenantId); input.put("knowledgeBaseId", knowledgeBaseId.toString());
        if (sourceRevision != null) input.put("sourceRevision", sourceRevision.toString());
        input.put("embeddingModel", embeddingModel); input.put("embeddingDimension", embeddingDimension);
        input.put("collectionSettings", collectionSettings == null ? Map.of() : collectionSettings);
        input.put("sourceManifestChecksum", sourceManifestChecksum); input.put("zipSha256", zipSha256);
        input.put("createdBy", createdBy); input.put("entries", encoded);
        return input;
    }

    @SuppressWarnings("unchecked")
    public static RecoveryArchiveImportPlan fromInput(Map<String, Object> input) {
        try {
            List<Entry> entries = ((List<Map<String, Object>>) input.get("entries")).stream().map(value -> new Entry(
                    String.valueOf(value.get("itemKey")), String.valueOf(value.get("itemType")),
                    String.valueOf(value.get("relativePath")), ((Number) value.get("byteSize")).longValue(),
                    String.valueOf(value.get("sha256")))).toList();
            Object revision = input.get("sourceRevision");
            return new RecoveryArchiveImportPlan(UUID.fromString(String.valueOf(input.get("sourceArchiveId"))),
                    String.valueOf(input.get("tenantId")), UUID.fromString(String.valueOf(input.get("knowledgeBaseId"))),
                    revision == null ? null : Instant.parse(String.valueOf(revision)), String.valueOf(input.get("embeddingModel")),
                    ((Number) input.get("embeddingDimension")).intValue(),
                    (Map<String, Object>) input.getOrDefault("collectionSettings", Map.of()),
                    String.valueOf(input.get("sourceManifestChecksum")), String.valueOf(input.get("zipSha256")),
                    String.valueOf(input.get("createdBy")), entries);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Recovery import operation has an invalid persisted plan", exception);
        }
    }

    public RecoveryManifestHeader finalHeader(UUID archiveId) {
        return new RecoveryManifestHeader(RecoveryManifestService.SCHEMA_VERSION, archiveId, tenantId,
                knowledgeBaseId, sourceRevision, embeddingModel, embeddingDimension, collectionSettings);
    }

    public List<RecoveryManifestItem> finalItems(UUID archiveId) {
        return entries.stream().map(entry -> new RecoveryManifestItem(entry.itemKey(), entry.itemType(),
                "archives/" + tenantId + "/" + archiveId + "/" + entry.relativePath(), entry.byteSize(), entry.sha256())).toList();
    }
}
