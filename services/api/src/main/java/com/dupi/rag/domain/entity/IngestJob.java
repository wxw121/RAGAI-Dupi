package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ingest_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestJob {

    @Id
    private UUID id;

    @Column(name = "kb_id", nullable = false)
    private UUID kbId;

    @Column(name = "doc_id", nullable = false)
    private UUID docId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private IngestJobStatus status = IngestJobStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private IngestStage stage = IngestStage.QUEUED;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(name = "callback_sequence", nullable = false)
    @Builder.Default
    private Long callbackSequence = 0L;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (executionId == null) {
            executionId = UUID.randomUUID();
        }
        if (callbackSequence == null) {
            callbackSequence = 0L;
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
