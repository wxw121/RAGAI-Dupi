package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.ChunkStrategy;
import com.dupi.rag.domain.enums.RetrievalMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateKnowledgeBaseRequest {

    @NotBlank
    @Size(max = 255)
    private String name;

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

    private String embeddingModel;

    private Integer embeddingDimension;

    private ChunkStrategy chunkStrategy = ChunkStrategy.RECURSIVE;

    private RetrievalMode retrievalMode = RetrievalMode.VECTOR;
}
