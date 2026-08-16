package com.dupi.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalGenerationDocumentPreview {
    private UUID documentId;
    private String fileName;
    private int existingSingleSourceCount;
    private int deficit;
    private boolean covered;
    @Builder.Default
    private List<RagEvalGenerationDraft> proposals = List.of();
    private String error;
}
