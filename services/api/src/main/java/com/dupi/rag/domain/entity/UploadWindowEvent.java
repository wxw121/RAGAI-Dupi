package com.dupi.rag.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upload_window_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadWindowEvent {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    @Builder.Default
    private Long bytes = 0L;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (acceptedAt == null) {
            acceptedAt = Instant.now();
        }
    }
}
