package com.dupi.rag.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
public class QaCandidatesRequest {

    @NotNull
    private UUID docId;

    @NotNull
    @Size(min = 1, max = 16)
    @Valid
    private List<SourceChunk> sources;

    @Data
    public static class SourceChunk {
        @NotNull
        private UUID chunkId;

        @NotBlank
        @Size(max = 12000)
        private String content;

        private Map<String, Object> metadata;
    }
}
