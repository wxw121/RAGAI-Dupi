package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RagEvalComparisonStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalRunResultResponse {
    private UUID id;
    private UUID caseId;
    private String caseKey;
    private String query;
    private boolean passed;
    private List<String> failureReasons;
    private Integer hitCount;
    private String expectedFileName;
    private String matchedFileName;
    private String matchedToken;
    private String retrievalMode;
    private String fallbackReason;
    private String embeddingModel;
    private Integer embeddingDimension;
    private Integer topK;
    private Integer matchedRank;
    private Integer vectorRank;
    private Integer sparseRank;
    private Integer fusionRank;
    private Integer rerankRank;
    private String caseFingerprint;
    private RagEvalComparisonStatus comparisonStatus;
    private Long latencyMs;
}
