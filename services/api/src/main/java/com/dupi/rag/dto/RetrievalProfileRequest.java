package com.dupi.rag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

@Data
public class RetrievalProfileRequest {
    @NotBlank @Size(max = 128)
    private String name;
    @NotNull @Min(1) @Max(500)
    private Integer vectorCandidateCount;
    @NotNull @Min(1) @Max(500)
    private Integer sparseCandidateCount;
    @NotNull @Min(1) @Max(1_000)
    private Integer rrfConstant;
    private Map<String, Object> sparseIndexParams = Map.of();
    private Map<String, Object> sparseSearchParams = Map.of();
    @NotNull
    private Boolean rerankEnabled;
    @NotNull @Min(1) @Max(500)
    private Integer rerankCandidateLimit;
    @NotNull @Min(1) @Max(50)
    private Integer finalTopK;
}
