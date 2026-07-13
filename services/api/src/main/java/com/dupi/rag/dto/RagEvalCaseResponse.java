package com.dupi.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalCaseResponse {
    private UUID id;
    private UUID kbId;
    private String caseKey;
    private String query;
    private Integer minHits;
    private Integer topK;
    private String expectedFileName;
    private List<String> mustContainAny;
    private Instant createdAt;
    private Instant updatedAt;
}
