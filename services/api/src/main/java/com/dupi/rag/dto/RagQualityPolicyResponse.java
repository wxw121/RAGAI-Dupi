package com.dupi.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagQualityPolicyResponse {
    private UUID id;
    private UUID kbId;
    private Integer minimumPassRate;
    private Integer maximumPassRateDrop;
    private Integer maximumNewFailures;
    private Boolean blockWhenUnbaselined;
    private UUID baselineRunId;
    private Instant createdAt;
    private Instant updatedAt;
}
