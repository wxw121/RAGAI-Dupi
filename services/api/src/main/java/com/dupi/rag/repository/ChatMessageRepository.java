package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    List<ChatMessage> findBySessionIdOrderBySequenceNumberAsc(UUID sessionId);

    @Query("""
            select message from ChatMessage message
            join ChatSession session on session.id = message.sessionId
            where message.sessionId = :sessionId
              and session.kbId = :kbId
            order by message.sequenceNumber asc
            """)
    List<ChatMessage> findBySessionIdAndKbIdOrderBySequenceNumberAsc(UUID sessionId, UUID kbId);
}
