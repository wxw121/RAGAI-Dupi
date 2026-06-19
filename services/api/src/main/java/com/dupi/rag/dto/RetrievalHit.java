package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class RetrievalHit {
    private UUID chunkId;
    private UUID docId;
    private String fileName;
    private String content;
    private double score;
    private Map<String, Object> metadata;
}
