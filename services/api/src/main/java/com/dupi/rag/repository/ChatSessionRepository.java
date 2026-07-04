package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {
    List<ChatSession> findByKbIdAndTenantIdOrderByUpdatedAtDesc(UUID kbId, String tenantId);

    Optional<ChatSession> findByIdAndKbIdAndTenantId(UUID id, UUID kbId, String tenantId);
}
