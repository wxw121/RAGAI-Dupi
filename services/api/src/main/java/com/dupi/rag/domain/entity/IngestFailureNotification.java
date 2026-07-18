package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.IngestFailureNotificationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ingest_failure_notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestFailureNotification {

    @Id
    private UUID id;

    @Column(name = "event_key", nullable = false, unique = true)
    private String eventKey;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "kb_id", nullable = false)
    private UUID kbId;

    @Column(name = "doc_id", nullable = false)
    private UUID docId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(nullable = false)
    private String status;

    @Column
    private String stage;

    @Column(name = "error_message")
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false)
    @Builder.Default
    private IngestFailureNotificationStatus deliveryStatus = IngestFailureNotificationStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

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
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
