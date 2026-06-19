package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class IngestJobResponse {
    private UUID id;
    private UUID kbId;
    private UUID docId;
    private IngestJobStatus status;
    private IngestStage stage;
    private Integer retryCount;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
