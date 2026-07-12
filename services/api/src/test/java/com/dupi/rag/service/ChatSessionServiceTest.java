package com.dupi.rag.service;

import com.dupi.rag.domain.entity.ChatMessage;
import com.dupi.rag.domain.entity.ChatSession;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.ChatMessageRole;
import com.dupi.rag.dto.Citation;
import com.dupi.rag.dto.CreateChatSessionRequest;
import com.dupi.rag.dto.UpdateChatSessionRequest;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.ChatMessageRepository;
import com.dupi.rag.repository.ChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    @Mock
    KnowledgeBaseService knowledgeBaseService;
    @Mock
    ChatSessionRepository sessionRepository;
    @Mock
    ChatMessageRepository messageRepository;
    @Mock
    AuditLogService auditLogService;

    ChatSessionService service;

    @BeforeEach
    void setUp() {
        service = new ChatSessionService(knowledgeBaseService, sessionRepository, messageRepository, auditLogService);
    }

    @Test
    void createTrimsTitleAndVerifiesKnowledgeBase() {
        UUID kbId = UUID.randomUUID();
        CreateChatSessionRequest request = new CreateChatSessionRequest();
        request.setTitle("  产品问答  ");
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> persistedSession(invocation.getArgument(0)));

        var response = service.create(kbId, request);

        verify(knowledgeBaseService).findOrThrow(kbId);
        assertThat(response.getKbId()).isEqualTo(kbId);
        assertThat(response.getTitle()).isEqualTo("产品问答");
        verify(sessionRepository).save(argThat(session -> session.getKbId().equals(kbId)
                && session.getTenantId().equals("default")
                && session.getTitle().equals("产品问答")));
    }

    @Test
    void createUsesNonblankDefaultTitleAndTruncatesLongFallbackTitle() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> persistedSession(invocation.getArgument(0)));

        CreateChatSessionRequest blankRequest = new CreateChatSessionRequest();
        blankRequest.setTitle("   ");
        var blankResponse = service.create(kbId, blankRequest);

        assertThat(blankResponse.getTitle()).isNotBlank();
        assertThat(blankResponse.getTitle()).hasSizeLessThanOrEqualTo(120);

        String longQuestion = "问题".repeat(80);
        var longResponse = service.createForFirstQuestion(kbId, longQuestion);

        assertThat(longResponse.getTitle()).startsWith("问题问题");
        assertThat(longResponse.getTitle()).hasSize(120);
    }

    @Test
    void listVerifiesKnowledgeBaseAndMapsRepositoryOrdering() {
        UUID kbId = UUID.randomUUID();
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        Instant recent = Instant.parse("2026-01-02T00:00:00Z");
        ChatSession recentSession = session(kbId, "Recent", recent);
        ChatSession oldSession = session(kbId, "Old", old);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(sessionRepository.findByKbIdAndTenantIdOrderByUpdatedAtDesc(kbId, "default")).thenReturn(List.of(
                recentSession,
                oldSession
        ));
        when(messageRepository.existsBySessionId(recentSession.getId())).thenReturn(true);
        when(messageRepository.existsBySessionId(oldSession.getId())).thenReturn(true);

        var responses = service.list(kbId);

        verify(knowledgeBaseService).findOrThrow(kbId);
        verify(sessionRepository).findByKbIdAndTenantIdOrderByUpdatedAtDesc(kbId, "default");
        assertThat(responses).extracting("title").containsExactly("Recent", "Old");
        assertThat(responses).extracting("updatedAt").containsExactly(recent, old);
    }

    @Test
    void listHidesSessionsWithoutPersistedMessages() {
        UUID kbId = UUID.randomUUID();
        ChatSession withMessages = session(kbId, "With messages", Instant.parse("2026-01-02T00:00:00Z"));
        ChatSession empty = session(kbId, "Empty", Instant.parse("2026-01-03T00:00:00Z"));
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(sessionRepository.findByKbIdAndTenantIdOrderByUpdatedAtDesc(kbId, "default"))
                .thenReturn(List.of(empty, withMessages));
        when(messageRepository.existsBySessionId(empty.getId())).thenReturn(false);
        when(messageRepository.existsBySessionId(withMessages.getId())).thenReturn(true);

        var responses = service.list(kbId);

        assertThat(responses).extracting("title").containsExactly("With messages");
    }

    @Test
    void detailUsesScopedSequenceOrderingAndMapsCitationSnapshot() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        ChatSession session = session(kbId, sessionId, "Session");
        when(sessionRepository.findByIdAndKbIdAndTenantId(sessionId, kbId, "default")).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdAndKbIdAndTenantIdOrderBySequenceNumberAsc(sessionId, kbId, "default"))
                .thenReturn(List.of(
                        message(sessionId, 1, ChatMessageRole.USER, "你好", null),
                        message(sessionId, 2, ChatMessageRole.ASSISTANT, "答案", Map.of("items", List.of(Map.of(
                                "chunkId", chunkId.toString(),
                                "docId", docId.toString(),
                                "fileName", "doc.md",
                                "snippet", "片段",
                                "score", 0.73
                        ))))
                ));

        var detail = service.getDetail(kbId, sessionId);

        assertThat(detail.getSession().getId()).isEqualTo(sessionId);
        assertThat(detail.getMessages()).extracting("sequenceNumber").containsExactly(1, 2);
        assertThat(detail.getMessages()).extracting("role").containsExactly("USER", "ASSISTANT");
        assertThat(detail.getMessages().get(0).getCitations()).isEmpty();
        assertThat(detail.getMessages().get(1).getCitations())
                .singleElement()
                .satisfies(citation -> {
                    assertThat(citation.getChunkId()).isEqualTo(chunkId);
                    assertThat(citation.getDocId()).isEqualTo(docId);
                    assertThat(citation.getFileName()).isEqualTo("doc.md");
                    assertThat(citation.getSnippet()).isEqualTo("片段");
                    assertThat(citation.getScore()).isEqualTo(0.73);
                });
    }

    @Test
    void messageResponseMapsUuidObjectsStringScoresAndNullCitationFields() {
        UUID sessionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        ChatMessage message = message(sessionId, 1, ChatMessageRole.ASSISTANT, "answer", Map.of(
                "items", List.of(Map.of(
                        "chunkId", chunkId,
                        "docId", docId,
                        "score", "0.42"
                ))
        ));

        var response = service.toMessageResponse(message);

        assertThat(response.getCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.getChunkId()).isEqualTo(chunkId);
            assertThat(citation.getDocId()).isEqualTo(docId);
            assertThat(citation.getFileName()).isNull();
            assertThat(citation.getSnippet()).isNull();
            assertThat(citation.getScore()).isEqualTo(0.42);
        });
    }

    @Test
    void messageResponseRejectsMalformedCitationSnapshots() {
        UUID sessionId = UUID.randomUUID();

        ChatMessage nonListItems = message(sessionId, 1, ChatMessageRole.ASSISTANT, "answer", Map.of("items", "bad"));
        assertThatThrownBy(() -> service.toMessageResponse(nonListItems))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items must be a list");

        ChatMessage nonObjectItem = message(sessionId, 2, ChatMessageRole.ASSISTANT, "answer", Map.of("items", List.of("bad")));
        assertThatThrownBy(() -> service.toMessageResponse(nonObjectItem))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("item must be an object");
    }

    @Test
    void renameRejectsBlankTitleAndSavesTrimmedValidTitle() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ChatSession session = session(kbId, sessionId, "Old");
        when(sessionRepository.findByIdAndKbIdAndTenantId(sessionId, kbId, "default")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateChatSessionRequest blank = new UpdateChatSessionRequest();
        blank.setTitle("   ");
        assertThatThrownBy(() -> service.rename(kbId, sessionId, blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");

        UpdateChatSessionRequest valid = new UpdateChatSessionRequest();
        valid.setTitle("  New title  ");
        var response = service.rename(kbId, sessionId, valid);

        assertThat(response.getTitle()).isEqualTo("New title");
        verify(sessionRepository).save(argThat(saved -> saved.getTitle().equals("New title")));
    }

    @Test
    void deleteAndBatchDeleteResolveSessionsThroughScopedLookup() {
        UUID kbId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        ChatSession first = session(kbId, firstId, "First");
        ChatSession second = session(kbId, secondId, "Second");
        when(sessionRepository.findByIdAndKbIdAndTenantId(firstId, kbId, "default")).thenReturn(Optional.of(first));
        when(sessionRepository.findByIdAndKbIdAndTenantId(secondId, kbId, "default")).thenReturn(Optional.of(second));

        service.delete(kbId, firstId);
        service.batchDelete(kbId, List.of(firstId, secondId));

        verify(sessionRepository).delete(first);
        verify(sessionRepository).deleteAll(List.of(first, second));
        verify(auditLogService).recordSuccess(
                eq("CHAT_SESSION_BATCH_DELETE"),
                eq("KNOWLEDGE_BASE"),
                eq(kbId),
                contains("2 chat session")
        );
    }

    @Test
    void missingSessionThrowsResourceNotFoundException() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findByIdAndKbIdAndTenantId(sessionId, kbId, "default")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findOrThrow(kbId, sessionId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Chat session not found");
    }

    @Test
    void saveUserAndAssistantMessagesAssignSequenceAndPersistSnapshots() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        ChatSession session = session(kbId, sessionId, "Chat");
        when(sessionRepository.findByIdAndKbIdAndTenantIdForUpdate(sessionId, kbId, "default")).thenReturn(Optional.of(session));
        when(messageRepository.findMaxSequenceNumberBySessionId(sessionId)).thenReturn(Optional.empty(), Optional.of(1));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> persistedMessage(invocation.getArgument(0)));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Citation citation = Citation.builder()
                .chunkId(chunkId)
                .docId(docId)
                .fileName("doc.md")
                .snippet("片段")
                .score(0.88)
                .build();

        var userMessage = service.saveUserMessage(kbId, sessionId, "问题");
        var assistantMessage = service.saveAssistantMessage(kbId, sessionId, "答案", List.of(citation));

        assertThat(userMessage.getSequenceNumber()).isEqualTo(1);
        assertThat(userMessage.getRole()).isEqualTo(ChatMessageRole.USER);
        assertThat(assistantMessage.getSequenceNumber()).isEqualTo(2);
        assertThat(assistantMessage.getRole()).isEqualTo(ChatMessageRole.ASSISTANT);
        assertThat(assistantMessage.getCitations()).containsKey("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) assistantMessage.getCitations().get("items");
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("chunkId", chunkId.toString());
            assertThat(item).containsEntry("docId", docId.toString());
            assertThat(item).containsEntry("fileName", "doc.md");
            assertThat(item).containsEntry("snippet", "片段");
            assertThat(item).containsEntry("score", 0.88);
        });
        verify(sessionRepository, times(2)).findByIdAndKbIdAndTenantIdForUpdate(sessionId, kbId, "default");
        verify(sessionRepository, times(2)).save(session);
    }

    @Test
    void saveAssistantMessagePersistsEmptySnapshotWhenCitationsAreMissing() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ChatSession session = session(kbId, sessionId, "Chat");
        when(sessionRepository.findByIdAndKbIdAndTenantIdForUpdate(sessionId, kbId, "default")).thenReturn(Optional.of(session));
        when(messageRepository.findMaxSequenceNumberBySessionId(sessionId)).thenReturn(Optional.of(2));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> persistedMessage(invocation.getArgument(0)));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var assistantMessage = service.saveAssistantMessage(kbId, sessionId, "answer", List.of());

        assertThat(assistantMessage.getSequenceNumber()).isEqualTo(3);
        assertThat(assistantMessage.getCitations()).containsEntry("items", List.of());
    }

    @Test
    void batchDeleteRejectsEmptyInputWithoutDeletingAnything() {
        UUID kbId = UUID.randomUUID();

        assertThatThrownBy(() -> service.batchDelete(kbId, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionIds");

        verify(sessionRepository, never()).deleteAll(any());
    }

    private static ChatSession persistedSession(ChatSession session) {
        if (session.getId() == null) {
            session.setId(UUID.randomUUID());
        }
        if (session.getCreatedAt() == null) {
            session.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        }
        if (session.getUpdatedAt() == null) {
            session.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        }
        return session;
    }

    private static ChatMessage persistedMessage(ChatMessage message) {
        if (message.getId() == null) {
            message.setId(UUID.randomUUID());
        }
        if (message.getCreatedAt() == null) {
            message.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        }
        return message;
    }

    private static ChatSession session(UUID kbId, String title, Instant updatedAt) {
        return ChatSession.builder()
                .id(UUID.randomUUID())
                .kbId(kbId)
                .tenantId("default")
                .title(title)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(updatedAt)
                .build();
    }

    private static ChatSession session(UUID kbId, UUID sessionId, String title) {
        return ChatSession.builder()
                .id(sessionId)
                .kbId(kbId)
                .tenantId("default")
                .title(title)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-02T00:00:00Z"))
                .build();
    }

    private static ChatMessage message(
            UUID sessionId,
            int sequenceNumber,
            ChatMessageRole role,
            String content,
            Map<String, Object> citations
    ) {
        return ChatMessage.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .sequenceNumber(sequenceNumber)
                .role(role)
                .content(content)
                .citations(citations == null ? null : new HashMap<>(citations))
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
