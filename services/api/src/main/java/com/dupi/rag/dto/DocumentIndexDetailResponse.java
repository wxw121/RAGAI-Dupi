package com.dupi.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIndexDetailResponse {
    private DocumentResponse document;
    private IngestJobResponse latestJob;
    private String objectKey;
    private boolean objectAvailable;
    private boolean indexReady;
    private int chunkCount;
    private List<ChunkPreview> chunks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkPreview {
        private UUID id;
        private Integer chunkIndex;
        private String contentPreview;
        private Integer tokenCount;
        private Map<String, Object> metadata;
        private String milvusId;
    }
}
