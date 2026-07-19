package com.dupi.rag.dto;

import com.dupi.rag.domain.entity.SparseMigration;
import com.dupi.rag.domain.enums.SparseMigrationState;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class SparseMigrationResponse {
    private UUID id;
    private UUID kbId;
    private UUID profileId;
    private SparseMigrationState state;
    private Long sourceChunkCount;
    private Long indexedChunkCount;
    private Integer expectedDimension;
    private Integer actualDimension;
    private Double baselineP95Ms;
    private Double candidateP95Ms;
    private Double baselineFallbackRate;
    private Double candidateFallbackRate;
    private Boolean legacyBm25Enabled;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public static SparseMigrationResponse from(SparseMigration value) {
        return builder().id(value.getId()).kbId(value.getKbId()).profileId(value.getProfileId())
                .state(value.getState()).sourceChunkCount(value.getSourceChunkCount())
                .indexedChunkCount(value.getIndexedChunkCount()).legacyBm25Enabled(value.getLegacyBm25Enabled())
                .expectedDimension(value.getExpectedDimension()).actualDimension(value.getActualDimension())
                .baselineP95Ms(value.getBaselineP95Ms()).candidateP95Ms(value.getCandidateP95Ms())
                .baselineFallbackRate(value.getBaselineFallbackRate()).candidateFallbackRate(value.getCandidateFallbackRate())
                .errorMessage(value.getErrorMessage()).createdAt(value.getCreatedAt()).updatedAt(value.getUpdatedAt()).build();
    }
}
