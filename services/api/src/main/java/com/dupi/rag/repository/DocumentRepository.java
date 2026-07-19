package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.enums.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    long countByKbId(UUID kbId);

    long countByKbIdAndIndexSchemaVersionLessThan(UUID kbId, int version);

    long countByKbIdAndStatusNot(UUID kbId, DocumentStatus status);

    List<Document> findByKbIdOrderByCreatedAtDesc(UUID kbId);

    List<Document> findTop1001ByKbIdOrderByCreatedAtDesc(UUID kbId);
}
