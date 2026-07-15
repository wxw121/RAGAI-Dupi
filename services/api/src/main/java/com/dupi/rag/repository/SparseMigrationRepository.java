package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.SparseMigration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.dupi.rag.domain.enums.SparseMigrationState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SparseMigrationRepository extends JpaRepository<SparseMigration, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SparseMigration> findByIdAndKbId(UUID id, UUID kbId);
    @Query("select m from SparseMigration m where m.id = ?1 and m.kbId = ?2")
    Optional<SparseMigration> findUnlockedByIdAndKbId(UUID id, UUID kbId);
    List<SparseMigration> findByKbIdOrderByCreatedAtDesc(UUID kbId);
    Optional<SparseMigration> findTopByKbIdAndStateInOrderByCreatedAtDesc(
            UUID kbId, List<SparseMigrationState> states);
}
