package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.RetrievalProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalRunResponse {
    private UUID id;
    private UUID kbId;
    private boolean useRerank;
    private List<RetrievalProfile> profileSet;
    private Long indexRevision;
    private Map<RetrievalProfile, RagEvalGateDecisionResponse> gateSummary;
    private Integer passedCount;
    private Integer totalCount;
    private RagEvalRunStatus status;
    private String failureMessage;
    private Instant createdAt;
    private List<RagEvalRunResultResponse> results;
}
