package com.dupi.rag.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rag_quality_policies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagQualityPolicy {
    @Id
    private UUID id;

    @Column(name = "kb_id", nullable = false, unique = true)
    private UUID kbId;

    @Column(name = "minimum_pass_rate", nullable = false)
    @Builder.Default
    private Integer minimumPassRate = 80;

    @Column(name = "maximum_pass_rate_drop", nullable = false)
    @Builder.Default
    private Integer maximumPassRateDrop = 5;

    @Column(name = "maximum_new_failures", nullable = false)
    @Builder.Default
    private Integer maximumNewFailures = 0;

    @Column(name = "block_when_unbaselined", nullable = false)
    @Builder.Default
    private Boolean blockWhenUnbaselined = false;

    @Column(name = "baseline_run_id")
    private UUID baselineRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
