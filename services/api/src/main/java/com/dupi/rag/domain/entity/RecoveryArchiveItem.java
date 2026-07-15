package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.RecoveryItemStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recovery_archive_items", uniqueConstraints =
        @UniqueConstraint(name = "uq_recovery_archive_item_key", columnNames = {"archive_id", "item_key"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RecoveryArchiveItem {
    @Id private UUID id;
    @Column(name = "archive_id", nullable = false) private UUID archiveId;
    @Column(name = "item_key", nullable = false) private String itemKey;
    @Column(name = "item_type", nullable = false) private String itemType;
    @Column(name = "source_id") private String sourceId;
    @Column(name = "object_key", nullable = false) private String objectKey;
    @Column(name = "byte_size") private Long byteSize;
    @Column(name = "sha256") private String sha256;
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
