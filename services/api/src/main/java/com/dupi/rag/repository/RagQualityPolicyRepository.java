package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.RagQualityPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface RagQualityPolicyRepository extends JpaRepository<RagQualityPolicy, UUID> {
    Optional<RagQualityPolicy> findByKbId(UUID kbId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select policy from RagQualityPolicy policy where policy.kbId = :kbId")
    Optional<RagQualityPolicy> findByKbIdForUpdate(@Param("kbId") UUID kbId);
}
