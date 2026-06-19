package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RetrieveResponse {
    private String query;
    private String retrievalMode;
    private List<RetrievalHit> hits;
}
