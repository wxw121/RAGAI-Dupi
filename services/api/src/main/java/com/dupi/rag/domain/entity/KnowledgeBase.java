package com.dupi.rag.domain.entity;

import com.dupi.rag.domain.enums.ChunkStrategy;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.domain.enums.RetrievalMode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_bases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeBase {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "chunk_size", nullable = false)
    @Builder.Default
    private Integer chunkSize = 512;

    @Column(name = "chunk_overlap", nullable = false)
    @Builder.Default
    private Integer chunkOverlap = 64;

    @Column(name = "top_k", nullable = false)
    @Builder.Default
    private Integer topK = 5;

    @Column(name = "embedding_model", nullable = false)
    @Builder.Default
    private String embeddingModel = "text-embedding-3-small";

    @Column(name = "embedding_dimension", nullable = false)
    @Builder.Default
    private Integer embeddingDimension = 1536;

    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_strategy", nullable = false)
    @Builder.Default
    private ChunkStrategy chunkStrategy = ChunkStrategy.RECURSIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "retrieval_mode", nullable = false)
    @Builder.Default
    private RetrievalMode retrievalMode = RetrievalMode.VECTOR;

    @Column(name = "active_retrieval_profile_id")
    private UUID activeRetrievalProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false)
    @Builder.Default
    private KnowledgeBaseLifecycleStatus lifecycleStatus = KnowledgeBaseLifecycleStatus.READY;

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
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
