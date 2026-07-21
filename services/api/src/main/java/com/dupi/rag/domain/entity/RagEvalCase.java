package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.RagEvalCaseCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
@Table(name = "rag_eval_cases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagEvalCase {
    @Id
    private UUID id;

    @Column(name = "kb_id", nullable = false)
    private UUID kbId;

    @Column(name = "case_key", nullable = false)
    private String caseKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String query;

    @Column(name = "min_hits", nullable = false)
    @Builder.Default
    private Integer minHits = 1;

    @Column(name = "top_k", nullable = false)
    @Builder.Default
    private Integer topK = 5;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RagEvalCaseCategory category = RagEvalCaseCategory.REAL_QUERY;

    @Column(name = "expected_file_name")
    private String expectedFileName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected_file_names", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<String> expectedFileNames = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "must_contain_any", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<String> mustContainAny = List.of();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
