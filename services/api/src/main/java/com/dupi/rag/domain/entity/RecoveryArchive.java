package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recovery_archives")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RecoveryArchive {
    @Id private UUID id;
    @Version @Column(nullable = false) @Builder.Default private Long version = 0L;
    @Column(name = "tenant_id", nullable = false) private String tenantId;
    @Column(name = "source_kb_id", nullable = false) private UUID sourceKnowledgeBaseId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) @Builder.Default
    private RecoveryArchiveStatus status = RecoveryArchiveStatus.PREPARING;
    @Column(name = "schema_version", nullable = false) @Builder.Default private Integer schemaVersion = 1;
    @Column(nullable = false) private String bucket;
    @Column(name = "object_prefix", nullable = false) private String objectPrefix;
    @Column(name = "source_revision") private Instant sourceRevision;
    @Column(name = "item_count", nullable = false) @Builder.Default private Long itemCount = 0L;
    @Column(name = "total_bytes", nullable = false) @Builder.Default private Long totalBytes = 0L;
    @Column(name = "manifest_checksum") private String manifestChecksum;
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
