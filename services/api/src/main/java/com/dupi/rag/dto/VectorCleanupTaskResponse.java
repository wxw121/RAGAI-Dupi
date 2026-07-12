package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.VectorCleanupStatus;
import com.dupi.rag.domain.enums.VectorCleanupTargetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorCleanupTaskResponse {
    private UUID id;
    private VectorCleanupTargetType targetType;
    private UUID targetId;
    private VectorCleanupStatus status;
    private Integer attemptCount;
    private String lastError;
    private Instant nextAttemptAt;
    private Instant createdAt;
    private Instant updatedAt;
}
