package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.RecoveryRestoreStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recovery_restore_jobs")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RecoveryRestoreJob {
    @Id private UUID id;
    @Version @Column(nullable = false) @Builder.Default private Long version = 0L;
    @Column(name = "archive_id", nullable = false) private UUID archiveId;
    @Column(name = "tenant_id", nullable = false) private String tenantId;
    @Column(name = "target_kb_id", unique = true) private UUID targetKnowledgeBaseId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) @Builder.Default
    private RecoveryRestoreStatus status = RecoveryRestoreStatus.VALIDATING;
    @Column(name = "completed_items", nullable = false) @Builder.Default private Long completedItems = 0L;
    @Column(name = "total_items", nullable = false) @Builder.Default private Long totalItems = 0L;
    @Column(name = "error_code") private String errorCode;
    @Column(name = "error_message", columnDefinition = "TEXT") private String errorMessage;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void create() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        createdAt = updatedAt = now;
    }
    @PreUpdate void update() { updatedAt = Instant.now(); }
}
