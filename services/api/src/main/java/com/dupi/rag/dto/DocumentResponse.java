package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.DocumentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class DocumentResponse {
    private UUID id;
    private UUID kbId;
    private String fileName;
    private String mimeType;
    private Long fileSize;
    private DocumentStatus status;
    private String errorMessage;
    private IngestJobResponse currentJob;
    private Instant createdAt;
    private Instant updatedAt;
}
