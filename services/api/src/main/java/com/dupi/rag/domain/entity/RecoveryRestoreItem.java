package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.RecoveryItemStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recovery_restore_items", uniqueConstraints =
        @UniqueConstraint(name = "uq_recovery_restore_archive_item", columnNames = {"restore_job_id", "archive_item_id"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RecoveryRestoreItem {
    @Id private UUID id;
    @Column(name = "restore_job_id", nullable = false) private UUID restoreJobId;
    @Column(name = "archive_item_id", nullable = false) private UUID archiveItemId;
    @Column(name = "target_id") private String targetId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) @Builder.Default
    private RecoveryItemStatus status = RecoveryItemStatus.PENDING;
    @Column(name = "attempt_count", nullable = false) @Builder.Default private Integer attemptCount = 0;
    @Column(name = "last_error", columnDefinition = "TEXT") private String lastError;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void create() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        createdAt = updatedAt = now;
    }
    @PreUpdate void update() { updatedAt = Instant.now(); }
}
