package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class RetrieveResponse {
    private String query;
    private String retrievalMode;
    private List<RetrievalHit> hits;
    private Map<String, Object> diagnostics;
}
