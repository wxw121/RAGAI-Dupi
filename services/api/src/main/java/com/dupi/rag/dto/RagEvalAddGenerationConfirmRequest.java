package com.dupi.rag.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class RagEvalAddGenerationConfirmRequest {
    @NotBlank
    private String documentFingerprint;

    @NotBlank
    private String caseFingerprint;

    @NotEmpty
    @Size(max = 20)
    private List<@NotNull UUID> documentIds = List.of();

    @NotNull
    @Min(1)
    @Max(5)
    private Integer casesPerDocument = 2;

    @Valid
    @NotEmpty
    @Size(max = 100)
    private List<RagEvalGenerationDraft> generatedCases = List.of();
}
