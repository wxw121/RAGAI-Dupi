package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.RetrievalProfile;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "rag_eval_run_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagEvalRunResult {
    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "case_key", nullable = false)
    private String caseKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String query;

    @Column(nullable = false)
    @Builder.Default
    private boolean passed = false;

    @Column(name = "hit_passed", nullable = false)
    @Builder.Default
    private boolean hitPassed = false;

    @Column(name = "citation_eligible", nullable = false)
    @Builder.Default
    private boolean citationEligible = false;

    @Column(name = "citation_passed", nullable = false)
    @Builder.Default
    private boolean citationPassed = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "failure_reasons", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<String> failureReasons = List.of();

    @Column(name = "hit_count", nullable = false)
    @Builder.Default
    private Integer hitCount = 0;

    @Column(name = "expected_file_name")
    private String expectedFileName;

    @Column(name = "matched_file_name")
    private String matchedFileName;

    @Column(name = "matched_token")
    private String matchedToken;

    @Column(name = "retrieval_mode")
    private String retrievalMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "retrieval_profile", nullable = false)
    @Builder.Default
    private RetrievalProfile retrievalProfile = RetrievalProfile.CLASSIC;

    @Column(name = "fallback_reason", columnDefinition = "TEXT")
    private String fallbackReason;

    @Column(name = "embedding_model")
    private String embeddingModel;

    @Column(name = "embedding_dimension")
    private Integer embeddingDimension;

    @Column(name = "top_k")
    private Integer topK;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
