package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RagEvalCaseCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvalGenerationDraft {
    private String caseKey;
    private String query;
    private String expectedFileName;
    @Builder.Default
    private List<String> mustContainAny = List.of();
    @Builder.Default
    private RagEvalCaseCategory category = RagEvalCaseCategory.REAL_QUERY;
    @Builder.Default
    private Integer minHits = 1;
    @Builder.Default
    private Integer topK = 5;
}
