package com.dupi.rag.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "document_assets",
        uniqueConstraints = @UniqueConstraint(name = "uk_document_assets_doc_path", columnNames = {"doc_id", "relative_path"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentAsset {

    @Id
    private UUID id;

    @Column(name = "kb_id", nullable = false)
    private UUID kbId;

    @Column(name = "doc_id", nullable = false)
    private UUID docId;

    @Column(name = "relative_path", nullable = false, length = 2048)
    private String relativePath;

    @Column(name = "object_key", nullable = false, length = 2048)
    private String objectKey;

    @Column(name = "mime_type", nullable = false, length = 128)
    private String mimeType;

    @Column(name = "file_name", nullable = false, length = 512)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
