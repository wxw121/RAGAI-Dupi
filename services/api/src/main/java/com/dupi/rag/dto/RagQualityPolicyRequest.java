package com.dupi.rag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RagQualityPolicyRequest {
    @NotNull
    @Min(0)
    @Max(100)
    private Integer minimumPassRate;

    @NotNull
    @Min(0)
    @Max(100)
    private Integer maximumPassRateDrop;

    @NotNull
    @Min(0)
    private Integer maximumNewFailures;

    @NotNull
    private Boolean blockWhenUnbaselined;
}
