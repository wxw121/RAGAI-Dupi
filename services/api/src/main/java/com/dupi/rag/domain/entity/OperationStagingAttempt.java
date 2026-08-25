package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.OperationStagingAttemptState;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "operation_staging_attempts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OperationStagingAttempt {
    @Id private UUID id;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "tenant_id", nullable = false, length = 128) private String tenantId;
    @Column(name = "step_key", nullable = false, length = 256) private String stepKey;
    @Column(name = "storage_type", nullable = false, length = 32) private String storageType;
    @Column(name = "object_key", nullable = false, length = 1024) private String objectKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private OperationStagingAttemptState state;
    @Column(name = "owner_token", nullable = false) private UUID ownerToken;
    @Column(name = "owner_epoch", nullable = false) private Long ownerEpoch;
    @Column(name = "lease_expires_at", nullable = false) private Instant leaseExpiresAt;
    @Column(name = "last_activity_at", nullable = false) private Instant lastActivityAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void create() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (state == null) state = OperationStagingAttemptState.ACTIVE;
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (lastActivityAt == null) lastActivityAt = now;
    }
    @PreUpdate void update() { updatedAt = Instant.now(); }
}
