package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RagEvalCaseCategory;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

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

    @NotNull
    private RagEvalCaseCategory category = RagEvalCaseCategory.REAL_QUERY;

    @Size(max = 512)
    private String expectedFileName;

    private UUID expectedDocumentId;

    @Size(max = 20)
    private List<@NotBlank @Size(max = 512) String> expectedFileNames = List.of();

    @Size(max = 20)
    private List<@NotNull UUID> expectedDocumentIds = List.of();

    @Size(max = 20)
    private List<@NotBlank @Size(max = 512) String> mustContainAny = List.of();

    @AssertTrue(message = "category assertions are inconsistent")
    public boolean isValidCategoryAssertions() {
        if (category == null) {
            return true;
        }
        long expectedFileCount = expectedDocumentCount();
        boolean hasTokens = mustContainAny != null && mustContainAny.stream()
                .anyMatch(value -> value != null && !value.isBlank());
        return switch (category) {
            case HARD_NEGATIVE -> Integer.valueOf(0).equals(minHits)
                    && expectedFileCount == 0
                    && !hasTokens;
            case MULTI_DOCUMENT -> minHits != null && minHits >= 2 && expectedFileCount >= 2;
            case AMBIGUOUS -> expectedFileCount >= 1 && hasTokens;
            case REAL_QUERY -> minHits != null && minHits >= 1;
        };
    }

    private long expectedDocumentCount() {
        List<UUID> additional = expectedDocumentIds == null ? List.of() : expectedDocumentIds;
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(expectedDocumentId), additional.stream())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
    }
}
