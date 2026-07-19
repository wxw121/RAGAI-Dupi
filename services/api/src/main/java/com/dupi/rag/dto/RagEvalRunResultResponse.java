package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RetrievalProfile;
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
    private boolean hitPassed;
    private boolean citationEligible;
    private boolean citationPassed;
    private Integer hitCount;
    private String expectedFileName;
    private String matchedFileName;
    private String matchedToken;
    private String retrievalMode;
    private RetrievalProfile retrievalProfile;
    private String fallbackReason;
    private String embeddingModel;
    private Integer embeddingDimension;
    private Integer topK;
}
