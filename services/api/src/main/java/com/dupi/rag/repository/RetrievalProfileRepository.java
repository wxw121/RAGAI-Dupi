package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.RetrievalProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RetrievalProfileRepository extends JpaRepository<RetrievalProfile, UUID> {
    Optional<RetrievalProfile> findByIdAndKbId(UUID id, UUID kbId);
    Optional<RetrievalProfile> findTopByKbIdOrderByVersionDesc(UUID kbId);
    List<RetrievalProfile> findByKbIdOrderByVersionDesc(UUID kbId);
}
