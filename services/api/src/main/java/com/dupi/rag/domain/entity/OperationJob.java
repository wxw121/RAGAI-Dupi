package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.domain.enums.OperationPhase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "operation_jobs", uniqueConstraints = @UniqueConstraint(
        name = "uq_operation_job_idempotency",
        columnNames = {"tenant_id", "operation_type", "idempotency_key"}
))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@lombok.Builder
public class OperationJob {

    @Id
    private UUID id;

    @Version
    @Column(nullable = false)
    @lombok.Builder.Default
    private Long version = 0L;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 64)
    private OperationType operationType;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @lombok.Builder.Default
    private OperationStatus status = OperationStatus.PREPARED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @lombok.Builder.Default
    private OperationPhase phase = OperationPhase.FORWARD;

    @Column(name = "idempotency_key", nullable = false, length = 256)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @lombok.Builder.Default
    private Map<String, Object> input = Map.of();

    @Column(name = "attempt_count", nullable = false)
    @lombok.Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "phase_attempt_count", nullable = false)
    @lombok.Builder.Default
    private Integer phaseAttemptCount = 0;

    @Column(nullable = false)
    @lombok.Builder.Default
    private Boolean runnable = false;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "claim_epoch", nullable = false)
    @lombok.Builder.Default
    private Long claimEpoch = 0L;

    @Column(name = "retry_epoch", nullable = false)
    @lombok.Builder.Default
    private Long retryEpoch = 0L;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (version == null) {
            version = 0L;
        }
        if (attemptCount == null) {
            attemptCount = 0;
        }
        if (phaseAttemptCount == null) {
            phaseAttemptCount = 0;
        }
        if (runnable == null) {
            runnable = false;
        }
        if (claimEpoch == null) {
            claimEpoch = 0L;
        }
        if (retryEpoch == null) {
            retryEpoch = 0L;
        }
        if (input == null) {
            input = Map.of();
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
