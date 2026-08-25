package com.dupi.rag.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class RagEvalGenerationConfirmRequest {
    @NotBlank
    private String documentFingerprint;

    @NotBlank
    private String caseFingerprint;

    @Size(max = 100)
    private List<UUID> replaceCaseIds = List.of();

    @Valid
    @Size(max = 100)
    private List<RagEvalGenerationDraft> generatedCases = List.of();
}
