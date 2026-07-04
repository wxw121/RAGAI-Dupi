package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.ChatSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {
    List<ChatSession> findByKbIdAndTenantIdOrderByUpdatedAtDesc(UUID kbId, String tenantId);

    Optional<ChatSession> findByIdAndKbIdAndTenantId(UUID id, UUID kbId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select session from ChatSession session
            where session.id = :id
              and session.kbId = :kbId
              and session.tenantId = :tenantId
            """)
    Optional<ChatSession> findByIdAndKbIdAndTenantIdForUpdate(
            @Param("id") UUID id,
            @Param("kbId") UUID kbId,
            @Param("tenantId") String tenantId
    );
}
