package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.DocumentAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentAssetRepository extends JpaRepository<DocumentAsset, UUID> {
    Optional<DocumentAsset> findByDocIdAndRelativePath(UUID docId, String relativePath);

    List<DocumentAsset> findByDocIdOrderByCreatedAtAsc(UUID docId);

    List<DocumentAsset> findByKbId(UUID kbId);

    void deleteByDocId(UUID docId);
}
