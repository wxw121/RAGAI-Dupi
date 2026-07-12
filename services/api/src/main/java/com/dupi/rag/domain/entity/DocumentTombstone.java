package com.dupi.rag.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_tombstones")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentTombstone {

    @Id
    @Column(name = "doc_id")
    private UUID docId;

    @Column(name = "kb_id", nullable = false)
    private UUID kbId;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "file_name")
    private String fileName;

    @Column(nullable = false)
    @Builder.Default
    private String reason = "DOCUMENT_DELETE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
