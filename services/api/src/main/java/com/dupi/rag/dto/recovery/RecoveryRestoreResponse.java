package com.dupi.rag.dto.recovery;

import com.dupi.rag.domain.entity.RecoveryRestoreJob;
import com.dupi.rag.domain.enums.RecoveryRestoreStatus;

import java.time.Instant;
import java.util.UUID;

public record RecoveryRestoreResponse(
        UUID id, UUID archiveId, UUID targetKnowledgeBaseId, RecoveryRestoreStatus status,
        long completedItems, long totalItems, String errorCode, String errorMessage,
        String createdBy, Instant createdAt, Instant updatedAt) {
    public static RecoveryRestoreResponse from(RecoveryRestoreJob value) {
        return new RecoveryRestoreResponse(value.getId(), value.getArchiveId(), value.getTargetKnowledgeBaseId(),
                value.getStatus(), value.getCompletedItems(), value.getTotalItems(), value.getErrorCode(),
                value.getErrorMessage(), value.getCreatedBy(), value.getCreatedAt(), value.getUpdatedAt());
    }
}
