package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationJobResponse {
    private UUID id;
    private OperationType operationType;
    private String aggregateType;
    private UUID aggregateId;
    private OperationStatus status;
    private Integer attemptCount;
    private Instant nextAttemptAt;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    @Builder.Default
    private List<OperationStepResponse> steps = List.of();
}
