package com.dupi.rag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class RagEvalCaseRequest {
    @NotBlank
    @Size(max = 128)
    private String caseKey;

    @NotBlank
    @Size(max = 4_000)
    private String query;

    @Min(0)
    private Integer minHits = 1;

    @Min(1)
    @Max(50)
    private Integer topK = 5;

    @Size(max = 512)
    private String expectedFileName;

    @Size(max = 20)
    private List<@NotBlank @Size(max = 512) String> mustContainAny = List.of();
}
