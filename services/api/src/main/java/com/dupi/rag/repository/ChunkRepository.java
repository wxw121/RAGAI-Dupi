package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ChunkRepository extends JpaRepository<Chunk, UUID> {
    List<Chunk> findByDocIdOrderByChunkIndexAsc(UUID docId);

    List<Chunk> findTop20ByDocIdOrderByChunkIndexAsc(UUID docId);

    long countByDocId(UUID docId);

    List<Chunk> findByKbIdOrderByChunkIndexAsc(UUID kbId);
    Page<Chunk> findByKbIdOrderByIdAsc(UUID kbId, Pageable pageable);
    long countByKbId(UUID kbId);

    List<Chunk> findTop10001ByKbIdOrderByChunkIndexAsc(UUID kbId);

    void deleteByDocId(UUID docId);

    void deleteByKbId(UUID kbId);
}
