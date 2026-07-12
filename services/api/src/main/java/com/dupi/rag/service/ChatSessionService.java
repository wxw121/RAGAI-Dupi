package com.dupi.rag.service;

import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.ChatMessage;
import com.dupi.rag.domain.entity.ChatSession;
import com.dupi.rag.domain.enums.ChatMessageRole;
import com.dupi.rag.dto.ChatMessageResponse;
import com.dupi.rag.dto.ChatSessionDetailResponse;
import com.dupi.rag.dto.ChatSessionResponse;
import com.dupi.rag.dto.Citation;
import com.dupi.rag.dto.CreateChatSessionRequest;
import com.dupi.rag.dto.UpdateChatSessionRequest;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.ChatMessageRepository;
import com.dupi.rag.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private static final String DEFAULT_TENANT_ID = "default";
    private static final String DEFAULT_TITLE = "新对话";
    private static final int MAX_TITLE_LENGTH = 120;

    private final KnowledgeBaseService knowledgeBaseService;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<ChatSessionResponse> list(UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        return sessionRepository.findByKbIdAndTenantIdOrderByUpdatedAtDesc(kbId, TenantContext.getTenantId()).stream()
                .filter(session -> messageRepository.existsBySessionId(session.getId()))
                .map(this::toSessionResponse)
                .toList();
    }

    @Transactional
    public ChatSessionResponse create(UUID kbId, CreateChatSessionRequest request) {
        knowledgeBaseService.findOrThrow(kbId);
        String title = normalizeTitle(request == null ? null : request.getTitle(), DEFAULT_TITLE, false);
        ChatSession session = ChatSession.builder()
                .kbId(kbId)
                .tenantId(TenantContext.getTenantId())
                .title(title)
                .build();
        return toSessionResponse(sessionRepository.save(session));
    }

    @Transactional
    public ChatSessionResponse createForFirstQuestion(UUID kbId, String firstQuestion) {
        knowledgeBaseService.findOrThrow(kbId);
        String title = normalizeTitle(firstQuestion, DEFAULT_TITLE, false);
        ChatSession session = ChatSession.builder()
                .kbId(kbId)
                .tenantId(TenantContext.getTenantId())
                .title(title)
                .build();
        return toSessionResponse(sessionRepository.save(session));
    }

    @Transactional(readOnly = true)
    public ChatSessionDetailResponse getDetail(UUID kbId, UUID sessionId) {
        return getDetail(kbId, sessionId, TenantContext.getTenantId());
    }

    @Transactional(readOnly = true)
    public ChatSessionDetailResponse getDetail(UUID kbId, UUID sessionId, String tenantId) {
        ChatSession session = findOrThrow(kbId, sessionId, tenantId);
        List<ChatMessageResponse> messages = messageRepository
                .findBySessionIdAndKbIdAndTenantIdOrderBySequenceNumberAsc(sessionId, kbId, tenantId)
                .stream()
                .map(this::toMessageResponse)
                .toList();
        return ChatSessionDetailResponse.builder()
                .session(toSessionResponse(session))
                .messages(messages)
                .build();
    }

    @Transactional
    public ChatSessionResponse rename(UUID kbId, UUID sessionId, UpdateChatSessionRequest request) {
        ChatSession session = findOrThrow(kbId, sessionId);
        session.setTitle(normalizeTitle(request == null ? null : request.getTitle(), null, true));
        return toSessionResponse(sessionRepository.save(session));
    }

    @Transactional
    public void delete(UUID kbId, UUID sessionId) {
        sessionRepository.delete(findOrThrow(kbId, sessionId));
    }

    @Transactional
    public void batchDelete(UUID kbId, List<UUID> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            throw new IllegalArgumentException("sessionIds must not be empty");
        }
        List<ChatSession> sessions = sessionIds.stream()
                .map(sessionId -> findOrThrow(kbId, sessionId))
                .toList();
        sessionRepository.deleteAll(sessions);
        auditLogService.recordSuccess(
                "CHAT_SESSION_BATCH_DELETE",
                "KNOWLEDGE_BASE",
                kbId,
                "Deleted " + sessions.size() + " chat session(s)"
        );
    }

    @Transactional(readOnly = true)
    public ChatSession findOrThrow(UUID kbId, UUID sessionId) {
        return findOrThrow(kbId, sessionId, TenantContext.getTenantId());
    }

    @Transactional(readOnly = true)
    public ChatSession findOrThrow(UUID kbId, UUID sessionId, String tenantId) {
        return sessionRepository.findByIdAndKbIdAndTenantId(sessionId, kbId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat session not found: " + sessionId));
    }

    @Transactional
    public ChatMessage saveUserMessage(UUID kbId, UUID sessionId, String content) {
        return saveUserMessage(findForUpdateOrThrow(kbId, sessionId, TenantContext.getTenantId()), content);
    }

    private ChatMessage saveUserMessage(ChatSession session, String content) {
        return saveMessage(session, ChatMessageRole.USER, content, null);
    }

    @Transactional
    public ChatMessage saveAssistantMessage(UUID kbId, UUID sessionId, String content, List<Citation> citations) {
        return saveAssistantMessage(findForUpdateOrThrow(kbId, sessionId, TenantContext.getTenantId()), content, citations);
    }

    private ChatMessage saveAssistantMessage(ChatSession session, String content, List<Citation> citations) {
        return saveMessage(session, ChatMessageRole.ASSISTANT, content, toCitationSnapshot(citations));
    }

    private ChatSession findForUpdateOrThrow(UUID kbId, UUID sessionId, String tenantId) {
        return sessionRepository.findByIdAndKbIdAndTenantIdForUpdate(sessionId, kbId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat session not found: " + sessionId));
    }

    public ChatSessionResponse toSessionResponse(ChatSession session) {
        return ChatSessionResponse.builder()
                .id(session.getId())
                .kbId(session.getKbId())
                .title(session.getTitle())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    public ChatMessageResponse toMessageResponse(ChatMessage message) {
        return ChatMessageResponse.builder()
                .id(message.getId())
                .sessionId(message.getSessionId())
                .sequenceNumber(message.getSequenceNumber())
                .role(message.getRole().name())
                .content(message.getContent())
                .citations(fromCitationSnapshot(message.getCitations()))
                .createdAt(message.getCreatedAt())
                .build();
    }

    private ChatMessage saveMessage(
            ChatSession session,
            ChatMessageRole role,
            String content,
            Map<String, Object> citations
    ) {
        int sequenceNumber = messageRepository.findMaxSequenceNumberBySessionId(session.getId()).orElse(0) + 1;
        ChatMessage message = ChatMessage.builder()
                .sessionId(session.getId())
                .sequenceNumber(sequenceNumber)
                .role(role)
                .content(content)
                .citations(citations)
                .build();
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
        return messageRepository.save(message);
    }

    private String normalizeTitle(String title, String fallback, boolean rejectBlank) {
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            if (rejectBlank) {
                throw new IllegalArgumentException("title must not be blank");
            }
            trimmed = fallback;
        }
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            return trimmed.substring(0, MAX_TITLE_LENGTH);
        }
        return trimmed;
    }

    private Map<String, Object> toCitationSnapshot(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return Map.of("items", List.of());
        }
        List<Map<String, Object>> items = citations.stream()
                .map(citation -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chunkId", citation.getChunkId() == null ? null : citation.getChunkId().toString());
                    item.put("docId", citation.getDocId() == null ? null : citation.getDocId().toString());
                    item.put("fileName", citation.getFileName());
                    item.put("snippet", citation.getSnippet());
                    item.put("score", citation.getScore());
                    return item;
                })
                .toList();
        return Map.of("items", items);
    }

    private List<Citation> fromCitationSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.get("items") == null) {
            return List.of();
        }
        Object rawItems = snapshot.get("items");
        if (!(rawItems instanceof List<?> items)) {
            throw new IllegalArgumentException("Invalid citation snapshot: items must be a list");
        }
        List<Citation> citations = new ArrayList<>();
        for (Object rawItem : items) {
            if (!(rawItem instanceof Map<?, ?> item)) {
                throw new IllegalArgumentException("Invalid citation snapshot: item must be an object");
            }
            citations.add(Citation.builder()
                    .chunkId(toUuid(item.get("chunkId")))
                    .docId(toUuid(item.get("docId")))
                    .fileName(toStringValue(item.get("fileName")))
                    .snippet(toStringValue(item.get("snippet")))
                    .score(toDouble(item.get("score")))
                    .build());
        }
        return citations;
    }

    private UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }

    private String toStringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private double toDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }
}
