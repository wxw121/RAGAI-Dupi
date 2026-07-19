package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.RetrievalProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "rag_eval_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagEvalRun {
    @Id
    private UUID id;

    @Column(name = "kb_id", nullable = false)
    private UUID kbId;

    @Column(name = "use_rerank", nullable = false)
    @Builder.Default
    private Boolean useRerank = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_set", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<RetrievalProfile> profileSet = List.of(RetrievalProfile.CLASSIC);

    @Column(name = "index_revision")
    private Long indexRevision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gate_summary", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> gateSummary = Map.of();

    @Column(name = "passed_count", nullable = false)
    @Builder.Default
    private Integer passedCount = 0;

    @Column(name = "total_count", nullable = false)
    @Builder.Default
    private Integer totalCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RagEvalRunStatus status = RagEvalRunStatus.RUNNING;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

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
