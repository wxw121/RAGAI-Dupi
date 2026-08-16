package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.OperationStepStatus;
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
public class OperationStepResponse {
    private UUID id;
    private Integer sequenceNumber;
    private String stepKey;
    private String stepType;
    private OperationStepStatus status;
    private String resourceRef;
    private Integer attemptCount;
    private String lastError;
    private Instant startedAt;
    private Instant completedAt;
    private Instant nextAttemptAt;
}
