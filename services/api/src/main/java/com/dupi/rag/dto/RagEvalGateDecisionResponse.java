package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RagEvalGateStatus;
import com.dupi.rag.domain.enums.RetrievalProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalGateDecisionResponse {
    private RetrievalProfile candidate;
    private RetrievalProfile baseline;
    private RagEvalGateStatus status;
    private String reason;
    private RagEvalProfileMetricsResponse metrics;
    private RagEvalProfileMetricsResponse classicMetrics;
    private double hitRateDelta;
    private double citationPassRateDelta;
    private Long runRevision;
    private Long currentRevision;
    private boolean indexReady;
}
