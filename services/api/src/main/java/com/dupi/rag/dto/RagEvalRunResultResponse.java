package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RagEvalCaseCategory;
import com.dupi.rag.domain.enums.RetrievalProfile;
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
    private List<String> failureCategories;
    private boolean hitPassed;
    private boolean citationEligible;
    private boolean citationPassed;
    private Integer hitCount;
    private RagEvalCaseCategory category;
    private String expectedFileName;
    private List<String> expectedFileNames;
    private String matchedFileName;
    private List<String> matchedFileNames;
    private String matchedToken;
    private String retrievalMode;
    private RetrievalProfile retrievalProfile;
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
