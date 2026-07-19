package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.ChunkStrategy;
import com.dupi.rag.domain.enums.RetrievalMode;
import com.dupi.rag.domain.enums.RetrievalProfile;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class KnowledgeBaseResponse {
    private UUID id;
    private String tenantId;
    private String name;
    private String description;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer topK;
    private String embeddingModel;
    private Integer embeddingDimension;
    private boolean embeddingConfigCurrent;
    private String embeddingConfigWarning;
    private ChunkStrategy chunkStrategy;
    private RetrievalMode retrievalMode;
    private RetrievalProfile retrievalProfile;
    private Instant createdAt;
    private Instant updatedAt;
}
