package com.dupi.rag.dto.recovery;

import com.dupi.rag.domain.entity.RecoveryArchive;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;

import java.time.Instant;
import java.util.UUID;

public record RecoveryArchiveResponse(
        UUID id, UUID sourceKnowledgeBaseId, RecoveryArchiveStatus status,
        int schemaVersion, long itemCount, long totalBytes, String manifestChecksum,
        String errorCode, String errorMessage, String createdBy, Instant createdAt, Instant updatedAt) {
    public static RecoveryArchiveResponse from(RecoveryArchive value) {
        return new RecoveryArchiveResponse(value.getId(), value.getSourceKnowledgeBaseId(), value.getStatus(),
                value.getSchemaVersion(), value.getItemCount(), value.getTotalBytes(), value.getManifestChecksum(),
                value.getErrorCode(), value.getErrorMessage(), value.getCreatedBy(), value.getCreatedAt(), value.getUpdatedAt());
    }
}
