package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.SparseMigration;
import com.dupi.rag.domain.enums.SparseMigrationState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SparseMigrationRepository extends JpaRepository<SparseMigration, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SparseMigration> findByIdAndKbId(UUID id, UUID kbId);
    List<SparseMigration> findByKbIdOrderByCreatedAtDesc(UUID kbId);
    Optional<SparseMigration> findTopByKbIdAndStateInOrderByCreatedAtDesc(
            UUID kbId, List<SparseMigrationState> states);
    boolean existsByKbIdAndStateIn(UUID kbId, List<SparseMigrationState> states);
}
