package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.SparseMigrationState;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sparse_migrations")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class SparseMigration {
    @Id private UUID id;
    @Version @Column(nullable = false) @Builder.Default private Long version = 0L;
    @Column(name = "kb_id", nullable = false) private UUID kbId;
    @Column(name = "profile_id", nullable = false) private UUID profileId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private SparseMigrationState state;
    @Column(name = "source_chunk_count", nullable = false) @Builder.Default private Long sourceChunkCount = 0L;
    @Column(name = "indexed_chunk_count", nullable = false) @Builder.Default private Long indexedChunkCount = 0L;
    @Column(name = "expected_dimension") private Integer expectedDimension;
    @Column(name = "actual_dimension") private Integer actualDimension;
    @Column(name = "baseline_p95_ms") private Double baselineP95Ms;
    @Column(name = "candidate_p95_ms") private Double candidateP95Ms;
    @Column(name = "baseline_fallback_rate") private Double baselineFallbackRate;
    @Column(name = "candidate_fallback_rate") private Double candidateFallbackRate;
    @Column(name = "legacy_bm25_enabled", nullable = false) @Builder.Default private Boolean legacyBm25Enabled = false;
    @Column(name = "error_message", columnDefinition = "TEXT") private String errorMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void create() {
        if (id == null) id = UUID.randomUUID();
        createdAt = updatedAt = Instant.now();
        if (state == null) state = SparseMigrationState.PREPARING;
    }
    @PreUpdate void update() { updatedAt = Instant.now(); }
}
