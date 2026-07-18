package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upload_quota_reservations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadQuotaReservation {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "kb_id", nullable = false)
    private UUID kbId;

    @Column(name = "doc_id")
    private UUID docId;

    @Column(name = "attempt_id")
    private UUID attemptId;

    @Column(name = "attempt_expires_at")
    private Instant attemptExpiresAt;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "file_fingerprint", nullable = false)
    private String fileFingerprint;

    @Column(name = "reserved_bytes", nullable = false)
    @Builder.Default
    private Long reservedBytes = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UploadQuotaReservationStatus status = UploadQuotaReservationStatus.PENDING;

    @Column(name = "release_reason")
    private String releaseReason;

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
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
