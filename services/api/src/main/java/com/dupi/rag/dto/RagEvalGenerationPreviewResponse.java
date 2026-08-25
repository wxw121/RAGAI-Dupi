package com.dupi.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalGenerationPreviewResponse {
    private String documentFingerprint;
    private String caseFingerprint;
    @Builder.Default
    private List<RagEvalCaseResponse> retainedCases = List.of();
    @Builder.Default
    private List<RagEvalCaseResponse> replacedCases = List.of();
    @Builder.Default
    private List<RagEvalGenerationDocumentPreview> documents = List.of();
    private boolean confirmable;
}
