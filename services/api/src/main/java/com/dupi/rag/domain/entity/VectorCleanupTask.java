package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.VectorCleanupStatus;
import com.dupi.rag.domain.enums.VectorCleanupTargetType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vector_cleanup_tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorCleanupTask {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private VectorCleanupTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private VectorCleanupStatus status = VectorCleanupStatus.PENDING;

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
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
