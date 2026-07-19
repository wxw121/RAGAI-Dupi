package com.dupi.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestStatusUpdate {
    private String jobId;
    private String docId;
    private String status;
    private String stage;
    private String errorMessage;
    private List<ChunkPayload> chunks;
    private List<String> milvusIds;
    private Integer indexSchemaVersion;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkPayload {
        private String id;
        private int chunkIndex;
        private String content;
        private int tokenCount;
        private Map<String, Object> metadata;
        private String milvusId;
    }
}
