package com.dupi.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalProfileResponse {
    private UUID id;
    private UUID kbId;
    private String name;
    private Integer version;
    private Integer vectorCandidateCount;
    private Integer sparseCandidateCount;
    private Integer rrfConstant;
    private Map<String, Object> sparseIndexParams;
    private Map<String, Object> sparseSearchParams;
    private Boolean rerankEnabled;
    private Integer rerankCandidateLimit;
    private Integer finalTopK;
    private boolean active;
    private Instant createdAt;
}
