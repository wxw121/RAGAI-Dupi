package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.OperationStepStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "operation_steps", uniqueConstraints = @UniqueConstraint(
        name = "uq_operation_step_key",
        columnNames = {"job_id", "step_key"}
))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@lombok.Builder
public class OperationStep {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(name = "step_key", nullable = false, length = 256)
    private String stepKey;

    @Column(name = "step_type", nullable = false, length = 64)
    private String stepType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @lombok.Builder.Default
    private OperationStepStatus status = OperationStepStatus.PENDING;

    @Column(name = "resource_ref", columnDefinition = "TEXT")
    private String resourceRef;

    @Column(name = "attempt_count", nullable = false)
    @lombok.Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (attemptCount == null) {
            attemptCount = 0;
        }
        Instant now = Instant.now();
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
