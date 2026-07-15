package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.ChunkStrategy;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.RetrievalMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseExportResponse {
    @Builder.Default
    private Integer schemaVersion = 1;
    private KnowledgeBaseSnapshot knowledgeBase;
    private List<DocumentSnapshot> documents;
    private List<ChunkSnapshot> chunks;
    private List<RagEvalCaseResponse> evalCases;
    private Instant exportedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KnowledgeBaseSnapshot {
        private UUID originalId;
        private String tenantId;
        private String name;
        private String description;
        private Integer chunkSize;
        private Integer chunkOverlap;
        private Integer topK;
        private String embeddingModel;
        private Integer embeddingDimension;
        private ChunkStrategy chunkStrategy;
        private RetrievalMode retrievalMode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentSnapshot {
        private UUID originalId;
        private String fileName;
        private String objectKey;
        private String mimeType;
        private Long fileSize;
        private DocumentStatus status;
        private String errorMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkSnapshot {
        private UUID originalId;
        private UUID originalDocId;
        private Integer chunkIndex;
        private String content;
        private Integer tokenCount;
        private Map<String, Object> metadata;
        private String milvusId;
    }
}
