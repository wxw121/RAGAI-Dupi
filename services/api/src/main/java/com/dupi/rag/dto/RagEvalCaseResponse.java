package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RagEvalCaseCategory;
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
    private RagEvalCaseCategory category;
    private String expectedFileName;
    private UUID expectedDocumentId;
    private List<String> expectedFileNames;
    private List<UUID> expectedDocumentIds;
    private List<String> mustContainAny;
    private boolean sourceValid;
    private List<String> missingExpectedFileNames;
    private Instant createdAt;
    private Instant updatedAt;
}
