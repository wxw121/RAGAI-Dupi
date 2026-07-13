package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.ChunkStrategy;
import com.dupi.rag.domain.enums.RetrievalMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class KnowledgeBaseImportRequest {

    @NotNull
    @Min(1)
    @Max(1)
    private Integer schemaVersion = 1;

    @Valid
    @NotNull
    private KnowledgeBaseSnapshot knowledgeBase;

    @Valid
    @Size(max = 100)
    private List<RagEvalCaseRequest> evalCases = List.of();

    @Data
    public static class KnowledgeBaseSnapshot {

        @NotBlank
        @Size(max = 255)
        private String name;

        @Size(max = 4_000)
        private String description;

        @Min(128)
        @Max(4096)
        private Integer chunkSize = 512;

        @Min(0)
        @Max(1024)
        private Integer chunkOverlap = 64;

        @Min(1)
        @Max(50)
        private Integer topK = 5;

        @Size(max = 255)
        private String embeddingModel;

        @Min(1)
        @Max(32_768)
        private Integer embeddingDimension;

        @NotNull
        private ChunkStrategy chunkStrategy = ChunkStrategy.RECURSIVE;

        @NotNull
        private RetrievalMode retrievalMode = RetrievalMode.VECTOR;

        @AssertTrue(message = "chunkOverlap must be smaller than chunkSize")
        public boolean isValidChunkOverlap() {
            return chunkSize == null || chunkOverlap == null || chunkOverlap < chunkSize;
        }
    }
}
