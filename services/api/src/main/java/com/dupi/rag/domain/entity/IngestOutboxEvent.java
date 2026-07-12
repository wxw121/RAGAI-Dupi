package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.IngestOutboxStatus;
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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ingest_outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestOutboxEvent {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "kb_id", nullable = false)
    private UUID kbId;

    @Column(name = "doc_id", nullable = false)
    private UUID docId;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private IngestOutboxStatus status = IngestOutboxStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

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
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
        if (attemptCount == null) {
            attemptCount = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
