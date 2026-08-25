package com.dupi.rag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class RagEvalAddGenerationPreviewRequest {
    @NotEmpty
    @Size(max = 20)
    private List<@NotNull UUID> documentIds = List.of();

    @NotNull
    @Min(1)
    @Max(5)
    private Integer casesPerDocument = 2;
}
