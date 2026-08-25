package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.DocumentTombstone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentTombstoneRepository extends JpaRepository<DocumentTombstone, UUID> {
    void deleteByKbId(UUID kbId);

    void deleteByKbIdAndReasonIn(UUID kbId, List<String> reasons);

    boolean existsByKbIdAndReasonIn(UUID kbId, List<String> reasons);

    List<DocumentTombstone> findByReasonOrderByCreatedAtAsc(String reason);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DocumentTombstone> findByDocId(UUID docId);
}
