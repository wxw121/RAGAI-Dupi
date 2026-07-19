package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RetrievalProfile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalProfileMetricsResponse {
    private RetrievalProfile profile;
    private int totalCases;
    private int passedCount;
    private int hitPassedCount;
    private int citationEligibleCount;
    private int citationPassedCount;
    private double passRate;
    private double hitRate;
    private double citationPassRate;
}
