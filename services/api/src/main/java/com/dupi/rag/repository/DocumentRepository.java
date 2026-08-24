package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.enums.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    boolean existsByKbIdAndStatus(UUID kbId, DocumentStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> findByIdForUpdate(@Param("id") UUID id);

    long countByKbId(UUID kbId);

    long countByKbIdAndIndexSchemaVersionLessThan(UUID kbId, int version);

    long countByKbIdAndStatusNot(UUID kbId, DocumentStatus status);

    @Query("select d from Document d where d.kbId = :kbId and d.status <> com.dupi.rag.domain.enums.DocumentStatus.IMPORTING order by d.createdAt desc")
    List<Document> findByKbIdOrderByCreatedAtDesc(@Param("kbId") UUID kbId);

    List<Document> findByKbIdAndStatusOrderByCreatedAtDesc(UUID kbId, DocumentStatus status);

    @Query(value = "select * from documents where kb_id = :kbId and status <> 'IMPORTING' order by created_at desc limit 1001", nativeQuery = true)
    List<Document> findTop1001ByKbIdOrderByCreatedAtDesc(@Param("kbId") UUID kbId);

    List<Document> findByImportJobIdOrderByCreatedAtAsc(UUID importJobId);

    List<Document> findByKbIdOrderByIdAsc(UUID kbId);
}
