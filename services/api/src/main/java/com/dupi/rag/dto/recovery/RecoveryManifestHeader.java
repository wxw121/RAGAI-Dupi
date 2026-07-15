package com.dupi.rag.dto.recovery;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RecoveryManifestHeader(
        int schemaVersion,
        UUID archiveId,
        String tenantId,
        UUID sourceKnowledgeBaseId,
        Instant sourceRevision,
        String embeddingModel,
        int embeddingDimension,
        Map<String, Object> collectionSettings) {
}
