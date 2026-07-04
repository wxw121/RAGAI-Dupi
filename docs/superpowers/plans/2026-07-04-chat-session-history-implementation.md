# Chat Session History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use $superpower-subagents (recommended) or $superpower-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking via update_plan.

**Goal:** Persist knowledge-base chat sessions so users can reopen a knowledge base, restore previous conversations, rename sessions, and delete one or many sessions.

**Architecture:** Add first-class chat session and chat message persistence in the Spring API, then adapt the React chat UI to load and manage those sessions per knowledge base. The existing `/chat` SSE endpoint remains the live answering path, but now creates or appends to persisted sessions and stores user messages, assistant responses, and citation snapshots.

**Tech Stack:** Spring Boot 3, JPA/Hibernate, Flyway, PostgreSQL JSONB, Reactor SSE, React 18, TypeScript, Vite, Vitest.

---

## File Structure

### Backend

- Create `services/api/src/main/resources/db/migration/V2__chat_sessions.sql`
  - Adds `chat_sessions` and `chat_messages`.
- Create `services/api/src/main/java/com/dupi/rag/domain/entity/ChatSession.java`
  - JPA entity for one conversation inside one knowledge base.
- Create `services/api/src/main/java/com/dupi/rag/domain/entity/ChatMessage.java`
  - JPA entity for user/assistant messages with citations JSON.
- Create `services/api/src/main/java/com/dupi/rag/repository/ChatSessionRepository.java`
  - Query sessions by KB, enforce KB ownership, delete by IDs.
- Create `services/api/src/main/java/com/dupi/rag/repository/ChatMessageRepository.java`
  - Query messages by session in chronological order.
- Create DTOs:
  - `ChatSessionResponse.java`
  - `ChatMessageResponse.java`
  - `ChatSessionDetailResponse.java`
  - `CreateChatSessionRequest.java`
  - `UpdateChatSessionRequest.java`
  - `BatchDeleteChatSessionsRequest.java`
- Create `services/api/src/main/java/com/dupi/rag/service/ChatSessionService.java`
  - Owns session CRUD, title validation, message persistence, and KB ownership checks.
- Modify `services/api/src/main/java/com/dupi/rag/service/ChatService.java`
  - Uses `ChatSessionService` to create/append session messages during SSE.
- Modify `services/api/src/main/java/com/dupi/rag/controller/KnowledgeBaseController.java`
  - Adds chat session management endpoints.
- Add tests:
  - `services/api/src/test/java/com/dupi/rag/service/ChatSessionServiceTest.java`
  - Extend `ChatServiceTest.java`
  - Extend `ControllerLayerTest.java`
  - Extend `DtoCoverageTest.java`

### Frontend

- Modify `services/web/src/types/index.ts`
  - Add chat session/message response types.
- Create `services/web/src/api/chatSessions.ts`
  - API wrapper for list/detail/create/rename/delete/batch-delete.
- Extend `services/web/src/api/chat.test.ts`
  - Covers `streamChat` session creation behavior.
- Extend `services/web/src/api/resources.test.ts`
  - Covers chat session API paths.
- Create `services/web/src/components/ChatHistorySidebar.tsx`
  - Desktop history list, single delete, rename, selection mode.
- Create `services/web/src/components/ChatHistoryDrawer.tsx`
  - Mobile drawer wrapper for the same history controls.
- Refactor `services/web/src/components/ChatPanel.tsx`
  - Coordinates active session, history list, messages, citations, streaming state.
- Optional small UI helpers:
  - Reuse `Dialog`, `Button`, `Input`, `Textarea`.
  - Use built-in `confirm()` for delete confirmation if a dedicated confirm dialog would expand scope too much.

### Verification

- API: `mvn verify`
- Web: bundled Node `node.exe` with Vitest coverage, TypeScript build, Vite build
- E2E: extend or add a script that verifies persisted session list/detail/rename/delete after chat SSE

---

## Task 1: Backend Database and Entities

**Files:**
- Create: `services/api/src/main/resources/db/migration/V2__chat_sessions.sql`
- Create: `services/api/src/main/java/com/dupi/rag/domain/entity/ChatSession.java`
- Create: `services/api/src/main/java/com/dupi/rag/domain/entity/ChatMessage.java`
- Create: `services/api/src/main/java/com/dupi/rag/repository/ChatSessionRepository.java`
- Create: `services/api/src/main/java/com/dupi/rag/repository/ChatMessageRepository.java`
- Test: `services/api/src/test/java/com/dupi/rag/domain/EntityLifecycleTest.java`

- [ ] **Step 1: Add failing entity lifecycle coverage**

Append to `EntityLifecycleTest.java`:

```java
@Test
void chatSessionAndMessageLifecycleDefaultsIdsAndTimestamps() {
    ChatSession session = ChatSession.builder()
            .kbId(UUID.randomUUID())
            .tenantId("default")
            .title("First question")
            .build();
    session.onCreate();

    ChatMessage message = ChatMessage.builder()
            .sessionId(session.getId())
            .role("user")
            .content("What is dupi-RAG?")
            .status("completed")
            .build();
    message.onCreate();

    assertThat(session.getId()).isNotNull();
    assertThat(session.getCreatedAt()).isNotNull();
    assertThat(session.getUpdatedAt()).isNotNull();
    assertThat(message.getId()).isNotNull();
    assertThat(message.getCreatedAt()).isNotNull();
}
```

Imports to add:

```java
import com.dupi.rag.domain.entity.ChatMessage;
import com.dupi.rag.domain.entity.ChatSession;
import java.util.UUID;
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```powershell
cd services/api
mvn -Dtest=EntityLifecycleTest test
```

Expected: compilation fails because `ChatSession` and `ChatMessage` do not exist.

- [ ] **Step 3: Add Flyway migration**

Create `V2__chat_sessions.sql`:

```sql
CREATE TABLE chat_sessions (
    id          UUID PRIMARY KEY,
    kb_id       UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default',
    title       VARCHAR(255) NOT NULL,
    status      VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_sessions_kb_updated ON chat_sessions(kb_id, updated_at DESC);

CREATE TABLE chat_messages (
    id          UUID PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role        VARCHAR(32) NOT NULL,
    content     TEXT NOT NULL,
    citations   JSONB,
    status      VARCHAR(32) NOT NULL DEFAULT 'completed',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_messages_session_created ON chat_messages(session_id, created_at ASC);
```

- [ ] **Step 4: Add entities**

Create `ChatSession.java`:

```java
package com.dupi.rag.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatSession {

    @Id
    private UUID id;

    @Column(name = "kb_id", nullable = false)
    private UUID kbId;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    @Builder.Default
    private String status = "active";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
```

Create `ChatMessage.java`:

```java
package com.dupi.rag.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String content;

    @Column(columnDefinition = "jsonb")
    private String citations;

    @Column(nullable = false)
    @Builder.Default
    private String status = "completed";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }
}
```

- [ ] **Step 5: Add repositories**

Create `ChatSessionRepository.java`:

```java
package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {
    List<ChatSession> findByKbIdAndStatusOrderByUpdatedAtDesc(UUID kbId, String status);

    Optional<ChatSession> findByIdAndKbIdAndStatus(UUID id, UUID kbId, String status);
}
```

Create `ChatMessageRepository.java`:

```java
package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
```

- [ ] **Step 6: Run entity test**

Run:

```powershell
cd services/api
mvn -Dtest=EntityLifecycleTest test
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 7: Commit backend schema and entities**

```powershell
git add services/api/src/main/resources/db/migration/V2__chat_sessions.sql `
  services/api/src/main/java/com/dupi/rag/domain/entity/ChatSession.java `
  services/api/src/main/java/com/dupi/rag/domain/entity/ChatMessage.java `
  services/api/src/main/java/com/dupi/rag/repository/ChatSessionRepository.java `
  services/api/src/main/java/com/dupi/rag/repository/ChatMessageRepository.java `
  services/api/src/test/java/com/dupi/rag/domain/EntityLifecycleTest.java
git commit -m "feat: add chat session persistence schema"
```

---

## Task 2: Backend DTOs and ChatSessionService

**Files:**
- Create: `services/api/src/main/java/com/dupi/rag/dto/ChatSessionResponse.java`
- Create: `services/api/src/main/java/com/dupi/rag/dto/ChatMessageResponse.java`
- Create: `services/api/src/main/java/com/dupi/rag/dto/ChatSessionDetailResponse.java`
- Create: `services/api/src/main/java/com/dupi/rag/dto/CreateChatSessionRequest.java`
- Create: `services/api/src/main/java/com/dupi/rag/dto/UpdateChatSessionRequest.java`
- Create: `services/api/src/main/java/com/dupi/rag/dto/BatchDeleteChatSessionsRequest.java`
- Create: `services/api/src/main/java/com/dupi/rag/service/ChatSessionService.java`
- Test: `services/api/src/test/java/com/dupi/rag/service/ChatSessionServiceTest.java`
- Modify: `services/api/src/test/java/com/dupi/rag/dto/DtoCoverageTest.java`

- [ ] **Step 1: Write failing ChatSessionService tests**

Create `ChatSessionServiceTest.java`:

```java
package com.dupi.rag.service;

import com.dupi.rag.domain.entity.ChatMessage;
import com.dupi.rag.domain.entity.ChatSession;
import com.dupi.rag.dto.Citation;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.ChatMessageRepository;
import com.dupi.rag.repository.ChatSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    @Mock ChatSessionRepository sessionRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock KnowledgeBaseService knowledgeBaseService;

    ChatSessionService service() {
        return new ChatSessionService(sessionRepository, messageRepository, knowledgeBaseService, new ObjectMapper());
    }

    @Test
    void createUsesTrimmedTitleAndKnowledgeBaseOwnership() {
        UUID kbId = UUID.randomUUID();
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> {
            ChatSession session = inv.getArgument(0);
            session.setId(UUID.randomUUID());
            session.setCreatedAt(Instant.now());
            session.setUpdatedAt(Instant.now());
            return session;
        });

        var response = service().create(kbId, "  My title  ");

        verify(knowledgeBaseService).findOrThrow(kbId);
        assertThat(response.getTitle()).isEqualTo("My title");
        verify(sessionRepository).save(argThat(s -> s.getKbId().equals(kbId) && s.getTitle().equals("My title")));
    }

    @Test
    void getDetailReturnsMessagesAndParsedCitations() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ChatSession session = ChatSession.builder().id(sessionId).kbId(kbId).title("T").status("active")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        String citationsJson = "[{\"chunkId\":\"" + UUID.randomUUID() + "\",\"docId\":\"" + UUID.randomUUID()
                + "\",\"fileName\":\"a.md\",\"snippet\":\"s\",\"score\":0.9}]";
        when(sessionRepository.findByIdAndKbIdAndStatus(sessionId, kbId, "active")).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                ChatMessage.builder().id(UUID.randomUUID()).sessionId(sessionId).role("user")
                        .content("Q").status("completed").createdAt(Instant.now()).build(),
                ChatMessage.builder().id(UUID.randomUUID()).sessionId(sessionId).role("assistant")
                        .content("A").citations(citationsJson).status("completed").createdAt(Instant.now()).build()
        ));

        var detail = service().getDetail(kbId, sessionId);

        assertThat(detail.getSession().getTitle()).isEqualTo("T");
        assertThat(detail.getMessages()).hasSize(2);
        assertThat(detail.getMessages().get(1).getCitations()).hasSize(1);
    }

    @Test
    void renameRejectsBlankAndUpdatesExistingSession() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ChatSession session = ChatSession.builder().id(sessionId).kbId(kbId).title("Old").status("active").build();
        when(sessionRepository.findByIdAndKbIdAndStatus(sessionId, kbId, "active")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service().rename(kbId, sessionId, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");

        service().rename(kbId, sessionId, "New");

        assertThat(session.getTitle()).isEqualTo("New");
        verify(sessionRepository).save(session);
    }

    @Test
    void deleteAndBatchDeleteOnlyTouchOwnedSessions() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ChatSession session = ChatSession.builder().id(sessionId).kbId(kbId).title("T").status("active").build();
        when(sessionRepository.findByIdAndKbIdAndStatus(sessionId, kbId, "active")).thenReturn(Optional.of(session));

        service().delete(kbId, sessionId);
        service().batchDelete(kbId, List.of(sessionId));

        verify(sessionRepository, times(2)).delete(session);
    }

    @Test
    void saveUserAndAssistantMessagesPersistExpectedRows() {
        UUID sessionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Citation citation = Citation.builder().chunkId(chunkId).docId(docId).fileName("a.md").snippet("s").score(0.9).build();

        service().saveUserMessage(sessionId, "Question");
        service().saveAssistantMessage(sessionId, "Answer", List.of(citation), "completed");

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ChatMessage::getRole).containsExactly("user", "assistant");
        assertThat(captor.getAllValues().get(1).getCitations()).contains("a.md");
    }

    @Test
    void findOrThrowRejectsMissingSession() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findByIdAndKbIdAndStatus(sessionId, kbId, "active")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().findOrThrow(kbId, sessionId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Chat session not found");
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```powershell
cd services/api
mvn -Dtest=ChatSessionServiceTest test
```

Expected: compilation fails because DTOs and `ChatSessionService` do not exist.

- [ ] **Step 3: Add DTOs**

Create `ChatSessionResponse.java`:

```java
package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ChatSessionResponse {
    private UUID id;
    private UUID kbId;
    private String title;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
```

Create `ChatMessageResponse.java`:

```java
package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class ChatMessageResponse {
    private UUID id;
    private UUID sessionId;
    private String role;
    private String content;
    private List<Citation> citations;
    private String status;
    private Instant createdAt;
}
```

Create `ChatSessionDetailResponse.java`:

```java
package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ChatSessionDetailResponse {
    private ChatSessionResponse session;
    private List<ChatMessageResponse> messages;
}
```

Create `CreateChatSessionRequest.java`:

```java
package com.dupi.rag.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateChatSessionRequest {
    @Size(max = 120)
    private String title;
}
```

Create `UpdateChatSessionRequest.java`:

```java
package com.dupi.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateChatSessionRequest {
    @NotBlank
    @Size(max = 120)
    private String title;
}
```

Create `BatchDeleteChatSessionsRequest.java`:

```java
package com.dupi.rag.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class BatchDeleteChatSessionsRequest {
    @NotEmpty
    private List<UUID> sessionIds;
}
```

- [ ] **Step 4: Add service implementation**

Create `ChatSessionService.java`:

```java
package com.dupi.rag.service;

import com.dupi.rag.domain.entity.ChatMessage;
import com.dupi.rag.domain.entity.ChatSession;
import com.dupi.rag.dto.ChatMessageResponse;
import com.dupi.rag.dto.ChatSessionDetailResponse;
import com.dupi.rag.dto.ChatSessionResponse;
import com.dupi.rag.dto.Citation;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.ChatMessageRepository;
import com.dupi.rag.repository.ChatSessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private static final String ACTIVE = "active";
    private static final int MAX_TITLE_LENGTH = 120;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    public List<ChatSessionResponse> list(UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        return sessionRepository.findByKbIdAndStatusOrderByUpdatedAtDesc(kbId, ACTIVE).stream()
                .map(this::toSessionResponse)
                .toList();
    }

    @Transactional
    public ChatSessionResponse create(UUID kbId, String requestedTitle) {
        knowledgeBaseService.findOrThrow(kbId);
        String title = normalizeTitle(requestedTitle, "新会话");
        ChatSession saved = sessionRepository.save(ChatSession.builder()
                .kbId(kbId)
                .tenantId("default")
                .title(title)
                .status(ACTIVE)
                .build());
        return toSessionResponse(saved);
    }

    public ChatSessionDetailResponse getDetail(UUID kbId, UUID sessionId) {
        ChatSession session = findOrThrow(kbId, sessionId);
        List<ChatMessageResponse> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::toMessageResponse)
                .toList();
        return ChatSessionDetailResponse.builder()
                .session(toSessionResponse(session))
                .messages(messages)
                .build();
    }

    @Transactional
    public ChatSessionResponse rename(UUID kbId, UUID sessionId, String title) {
        ChatSession session = findOrThrow(kbId, sessionId);
        session.setTitle(normalizeTitle(title, null));
        return toSessionResponse(sessionRepository.save(session));
    }

    @Transactional
    public void delete(UUID kbId, UUID sessionId) {
        sessionRepository.delete(findOrThrow(kbId, sessionId));
    }

    @Transactional
    public void batchDelete(UUID kbId, List<UUID> sessionIds) {
        for (UUID sessionId : sessionIds) {
            sessionRepository.delete(findOrThrow(kbId, sessionId));
        }
    }

    public ChatSession findOrThrow(UUID kbId, UUID sessionId) {
        return sessionRepository.findByIdAndKbIdAndStatus(sessionId, kbId, ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Chat session not found: " + sessionId));
    }

    @Transactional
    public ChatSession createForFirstQuestion(UUID kbId, String question) {
        knowledgeBaseService.findOrThrow(kbId);
        return sessionRepository.save(ChatSession.builder()
                .kbId(kbId)
                .tenantId("default")
                .title(normalizeTitle(question, "新会话"))
                .status(ACTIVE)
                .build());
    }

    @Transactional
    public void saveUserMessage(UUID sessionId, String content) {
        messageRepository.save(ChatMessage.builder()
                .sessionId(sessionId)
                .role("user")
                .content(content)
                .status("completed")
                .build());
    }

    @Transactional
    public void saveAssistantMessage(UUID sessionId, String content, List<Citation> citations, String status) {
        messageRepository.save(ChatMessage.builder()
                .sessionId(sessionId)
                .role("assistant")
                .content(content)
                .citations(writeCitations(citations))
                .status(status)
                .build());
    }

    private String normalizeTitle(String value, String fallback) {
        String title = value == null ? fallback : value.trim();
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        return title.length() <= MAX_TITLE_LENGTH ? title : title.substring(0, MAX_TITLE_LENGTH);
    }

    private String writeCitations(List<Citation> citations) {
        try {
            return objectMapper.writeValueAsString(citations == null ? List.of() : citations);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<Citation> readCitations(String citations) {
        if (citations == null || citations.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(citations, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private ChatSessionResponse toSessionResponse(ChatSession session) {
        return ChatSessionResponse.builder()
                .id(session.getId())
                .kbId(session.getKbId())
                .title(session.getTitle())
                .status(session.getStatus())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return ChatMessageResponse.builder()
                .id(message.getId())
                .sessionId(message.getSessionId())
                .role(message.getRole())
                .content(message.getContent())
                .citations(readCitations(message.getCitations()))
                .status(message.getStatus())
                .createdAt(message.getCreatedAt())
                .build();
    }
}
```

- [ ] **Step 5: Extend DTO coverage**

Add to `DtoCoverageTest.responseDtosExposeAllBuilderFields()`:

```java
ChatSessionResponse session = ChatSessionResponse.builder()
        .id(id).kbId(id).title("title").status("active").createdAt(now).updatedAt(now).build();
assertThat(session.getId()).isEqualTo(id);
assertThat(session.getKbId()).isEqualTo(id);
assertThat(session.getTitle()).isEqualTo("title");
assertThat(session.getStatus()).isEqualTo("active");
assertThat(session.getCreatedAt()).isEqualTo(now);
assertThat(session.getUpdatedAt()).isEqualTo(now);

ChatMessageResponse message = ChatMessageResponse.builder()
        .id(id).sessionId(id).role("assistant").content("answer")
        .citations(java.util.List.of(citation)).status("completed").createdAt(now).build();
assertThat(message.getSessionId()).isEqualTo(id);
assertThat(message.getRole()).isEqualTo("assistant");
assertThat(message.getContent()).isEqualTo("answer");
assertThat(message.getCitations()).containsExactly(citation);
assertThat(message.getStatus()).isEqualTo("completed");

ChatSessionDetailResponse detail = ChatSessionDetailResponse.builder()
        .session(session)
        .messages(java.util.List.of(message))
        .build();
assertThat(detail.getSession()).isSameAs(session);
assertThat(detail.getMessages()).containsExactly(message);
```

Add a new test:

```java
@Test
void chatSessionRequestsExposeMutators() {
    CreateChatSessionRequest create = new CreateChatSessionRequest();
    create.setTitle("title");
    assertThat(create.getTitle()).isEqualTo("title");

    UpdateChatSessionRequest update = new UpdateChatSessionRequest();
    update.setTitle("new");
    assertThat(update.getTitle()).isEqualTo("new");

    BatchDeleteChatSessionsRequest batch = new BatchDeleteChatSessionsRequest();
    UUID id = UUID.randomUUID();
    batch.setSessionIds(java.util.List.of(id));
    assertThat(batch.getSessionIds()).containsExactly(id);
}
```

- [ ] **Step 6: Run service and DTO tests**

Run:

```powershell
cd services/api
mvn -Dtest=ChatSessionServiceTest,DtoCoverageTest test
```

Expected: all tests pass.

- [ ] **Step 7: Commit service and DTOs**

```powershell
git add services/api/src/main/java/com/dupi/rag/dto/ChatSessionResponse.java `
  services/api/src/main/java/com/dupi/rag/dto/ChatMessageResponse.java `
  services/api/src/main/java/com/dupi/rag/dto/ChatSessionDetailResponse.java `
  services/api/src/main/java/com/dupi/rag/dto/CreateChatSessionRequest.java `
  services/api/src/main/java/com/dupi/rag/dto/UpdateChatSessionRequest.java `
  services/api/src/main/java/com/dupi/rag/dto/BatchDeleteChatSessionsRequest.java `
  services/api/src/main/java/com/dupi/rag/service/ChatSessionService.java `
  services/api/src/test/java/com/dupi/rag/service/ChatSessionServiceTest.java `
  services/api/src/test/java/com/dupi/rag/dto/DtoCoverageTest.java
git commit -m "feat: add chat session service"
```

---

## Task 3: Persist Messages During Chat SSE and Add Session APIs

**Files:**
- Modify: `services/api/src/main/java/com/dupi/rag/service/ChatService.java`
- Modify: `services/api/src/main/java/com/dupi/rag/controller/KnowledgeBaseController.java`
- Modify: `services/api/src/test/java/com/dupi/rag/service/ChatServiceTest.java`
- Modify: `services/api/src/test/java/com/dupi/rag/controller/ControllerLayerTest.java`

- [ ] **Step 1: Update failing ChatService tests**

In `ChatServiceTest`, add mock:

```java
@Mock ChatSessionService chatSessionService;
```

Change factory:

```java
ChatService service(RedisQueueProperties props) {
    return new ChatService(knowledgeBaseService, retrievalService, llmClient, redisTemplate, props, new ObjectMapper(), chatSessionService);
}
```

In `chatStreamsRetrievalTokensAndDoneEvent`, add:

```java
UUID sessionUuid = UUID.randomUUID();
when(chatSessionService.findOrThrow(kbId, sessionUuid)).thenReturn(
        ChatSession.builder().id(sessionUuid).kbId(kbId).title("T").build());
request.setSessionId(sessionUuid.toString());
```

Then assert persistence:

```java
verify(chatSessionService).saveUserMessage(sessionUuid, "闂");
verify(chatSessionService).saveAssistantMessage(eq(sessionUuid), eq("浣犲ソ"), anyList(), eq("completed"));
```

Add test for missing session ID:

```java
@Test
void chatStreamCreatesSessionWhenRequestOmitsSessionId() {
    UUID kbId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
    when(chatSessionService.createForFirstQuestion(kbId, "Q")).thenReturn(
            ChatSession.builder().id(sessionId).kbId(kbId).title("Q").build());
    when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().hits(List.of()).build());
    when(retrievalService.buildContext(List.of())).thenReturn("");
    when(llmClient.chatStream(anyString(), anyString())).thenReturn(Flux.just("A"));
    ChatRequest request = new ChatRequest();
    request.setQuery("Q");

    var events = service(redisProps()).chatStream(kbId, request).collectList().block();

    assertThat(events).extracting(e -> e.event()).containsExactly("retrieval", "token", "done");
    assertThat(events.get(2).data()).contains(sessionId.toString());
    verify(chatSessionService).saveUserMessage(sessionId, "Q");
    verify(chatSessionService).saveAssistantMessage(sessionId, "A", List.of(), "completed");
}
```

Add imports:

```java
import com.dupi.rag.domain.entity.ChatSession;
```

- [ ] **Step 2: Run ChatService tests and verify they fail**

Run:

```powershell
cd services/api
mvn -Dtest=ChatServiceTest test
```

Expected: compilation fails because `ChatService` constructor and behavior have not changed.

- [ ] **Step 3: Modify ChatService constructor and stream flow**

Add field to `ChatService`:

```java
private final ChatSessionService chatSessionService;
```

At the start of `chatStream`, after retrieval setup but before generating events, resolve session:

```java
UUID persistedSessionId;
if (request.getSessionId() == null || request.getSessionId().isBlank()) {
    persistedSessionId = chatSessionService.createForFirstQuestion(kbId, request.getQuery()).getId();
} else {
    persistedSessionId = UUID.fromString(request.getSessionId());
    chatSessionService.findOrThrow(kbId, persistedSessionId);
}
chatSessionService.saveUserMessage(persistedSessionId, request.getQuery());
String sessionId = persistedSessionId.toString();
StringBuilder assistantBuffer = new StringBuilder();
```

Change token events:

```java
Flux<ServerSentEvent<String>> tokenEvents = llmClient.chatStream(SYSTEM_PROMPT, userPrompt)
        .takeWhile(token -> !cancelledSessions.contains(sessionId))
        .doOnNext(assistantBuffer::append)
        .map(token -> ServerSentEvent.<String>builder()
                .event("token")
                .data(token)
                .build())
        .doOnComplete(() -> chatSessionService.saveAssistantMessage(
                persistedSessionId,
                assistantBuffer.toString(),
                citations,
                "completed"))
        .doFinally(signal -> {
            if (cancelledSessions.contains(sessionId) && !assistantBuffer.isEmpty()) {
                chatSessionService.saveAssistantMessage(
                        persistedSessionId,
                        assistantBuffer.toString(),
                        citations,
                        "interrupted");
            }
            cancelledSessions.remove(sessionId);
        });
```

Keep `doneEvent` using the same `sessionId`.

In `onErrorResume`, save failed assistant state before returning error:

```java
.onErrorResume(ex -> {
    if (!assistantBuffer.isEmpty()) {
        chatSessionService.saveAssistantMessage(persistedSessionId, assistantBuffer.toString(), citations, "failed");
    }
    return Flux.just(ServerSentEvent.<String>builder().event("error").data(ex.getMessage()).build());
});
```

- [ ] **Step 4: Add controller failing coverage**

Modify `ControllerLayerTest.knowledgeBaseControllerDelegatesCrudRetrieveChatCancelAndJobs()`:

Add:

```java
ChatSessionService chatSessionService = mock(ChatSessionService.class);
KnowledgeBaseController controller = new KnowledgeBaseController(kbService, retrievalService, chatService, ingestJobService, chatSessionService);
UUID sessionId = UUID.randomUUID();
ChatSessionResponse sessionResponse = ChatSessionResponse.builder().id(sessionId).kbId(kbId).title("T").build();
ChatSessionDetailResponse detail = ChatSessionDetailResponse.builder().session(sessionResponse).messages(List.of()).build();
when(chatSessionService.list(kbId)).thenReturn(List.of(sessionResponse));
when(chatSessionService.create(eq(kbId), any())).thenReturn(sessionResponse);
when(chatSessionService.getDetail(kbId, sessionId)).thenReturn(detail);
when(chatSessionService.rename(kbId, sessionId, "New")).thenReturn(ChatSessionResponse.builder().id(sessionId).kbId(kbId).title("New").build());
```

Add assertions:

```java
CreateChatSessionRequest createSession = new CreateChatSessionRequest();
createSession.setTitle("T");
UpdateChatSessionRequest updateSession = new UpdateChatSessionRequest();
updateSession.setTitle("New");
BatchDeleteChatSessionsRequest batchDelete = new BatchDeleteChatSessionsRequest();
batchDelete.setSessionIds(List.of(sessionId));

assertThat(controller.listChatSessions(kbId)).containsExactly(sessionResponse);
assertThat(controller.createChatSession(kbId, createSession)).isSameAs(sessionResponse);
assertThat(controller.getChatSession(kbId, sessionId)).isSameAs(detail);
assertThat(controller.renameChatSession(kbId, sessionId, updateSession).getTitle()).isEqualTo("New");
controller.deleteChatSession(kbId, sessionId);
controller.batchDeleteChatSessions(kbId, batchDelete);
verify(chatSessionService).delete(kbId, sessionId);
verify(chatSessionService).batchDelete(kbId, List.of(sessionId));
```

- [ ] **Step 5: Add controller endpoints**

Modify constructor by adding `private final ChatSessionService chatSessionService;`.

Add methods:

```java
@GetMapping("/{kbId}/chat-sessions")
public java.util.List<ChatSessionResponse> listChatSessions(@PathVariable UUID kbId) {
    return chatSessionService.list(kbId);
}

@PostMapping("/{kbId}/chat-sessions")
public ChatSessionResponse createChatSession(@PathVariable UUID kbId, @Valid @RequestBody CreateChatSessionRequest request) {
    return chatSessionService.create(kbId, request.getTitle());
}

@GetMapping("/{kbId}/chat-sessions/{sessionId}")
public ChatSessionDetailResponse getChatSession(@PathVariable UUID kbId, @PathVariable UUID sessionId) {
    return chatSessionService.getDetail(kbId, sessionId);
}

@PatchMapping("/{kbId}/chat-sessions/{sessionId}")
public ChatSessionResponse renameChatSession(
        @PathVariable UUID kbId,
        @PathVariable UUID sessionId,
        @Valid @RequestBody UpdateChatSessionRequest request) {
    return chatSessionService.rename(kbId, sessionId, request.getTitle());
}

@DeleteMapping("/{kbId}/chat-sessions/{sessionId}")
public void deleteChatSession(@PathVariable UUID kbId, @PathVariable UUID sessionId) {
    chatSessionService.delete(kbId, sessionId);
}

@PostMapping("/{kbId}/chat-sessions/batch-delete")
public void batchDeleteChatSessions(@PathVariable UUID kbId, @Valid @RequestBody BatchDeleteChatSessionsRequest request) {
    chatSessionService.batchDelete(kbId, request.getSessionIds());
}
```

- [ ] **Step 6: Run focused API tests**

Run:

```powershell
cd services/api
mvn -Dtest=ChatServiceTest,ControllerLayerTest test
```

Expected: all tests pass.

- [ ] **Step 7: Commit API chat persistence**

```powershell
git add services/api/src/main/java/com/dupi/rag/service/ChatService.java `
  services/api/src/main/java/com/dupi/rag/controller/KnowledgeBaseController.java `
  services/api/src/test/java/com/dupi/rag/service/ChatServiceTest.java `
  services/api/src/test/java/com/dupi/rag/controller/ControllerLayerTest.java
git commit -m "feat: persist chat messages during conversations"
```

---

## Task 4: Frontend API Types and Session Client

**Files:**
- Modify: `services/web/src/types/index.ts`
- Create: `services/web/src/api/chatSessions.ts`
- Modify: `services/web/src/api/resources.test.ts`
- Modify: `services/web/src/api/client.ts`
- Modify: `services/web/src/api/client.test.ts`

- [ ] **Step 1: Add failing API wrapper tests**

In `resources.test.ts`, import:

```ts
import {
  batchDeleteChatSessions,
  createChatSession,
  deleteChatSession,
  getChatSession,
  listChatSessions,
  renameChatSession,
} from './chatSessions'
```

Add test:

```ts
it('builds chat session API paths', async () => {
  apiClient.apiGet.mockResolvedValueOnce([{ id: 's1' }]).mockResolvedValueOnce({ session: { id: 's1' }, messages: [] })
  apiClient.apiPost.mockResolvedValueOnce({ id: 's2' }).mockResolvedValueOnce(undefined)
  apiClient.apiPatch.mockResolvedValue({ id: 's1', title: 'New' })
  apiClient.apiDelete.mockResolvedValue(undefined)

  await expect(listChatSessions('kb')).resolves.toEqual([{ id: 's1' }])
  await expect(getChatSession('kb', 's1')).resolves.toEqual({ session: { id: 's1' }, messages: [] })
  await expect(createChatSession('kb', 'Title')).resolves.toEqual({ id: 's2' })
  await expect(renameChatSession('kb', 's1', 'New')).resolves.toEqual({ id: 's1', title: 'New' })
  await expect(deleteChatSession('kb', 's1')).resolves.toBeUndefined()
  await expect(batchDeleteChatSessions('kb', ['s1', 's2'])).resolves.toBeUndefined()

  expect(apiClient.apiGet).toHaveBeenNthCalledWith(1, '/api/v1/knowledge-bases/kb/chat-sessions')
  expect(apiClient.apiGet).toHaveBeenNthCalledWith(2, '/api/v1/knowledge-bases/kb/chat-sessions/s1')
  expect(apiClient.apiPost).toHaveBeenNthCalledWith(1, '/api/v1/knowledge-bases/kb/chat-sessions', { title: 'Title' })
  expect(apiClient.apiPatch).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/chat-sessions/s1', { title: 'New' })
  expect(apiClient.apiDelete).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/chat-sessions/s1')
  expect(apiClient.apiPost).toHaveBeenNthCalledWith(2, '/api/v1/knowledge-bases/kb/chat-sessions/batch-delete', { sessionIds: ['s1', 's2'] })
})
```

Update mock:

```ts
apiPatch: vi.fn(),
```

- [ ] **Step 2: Run wrapper test and verify it fails**

Run with bundled Node:

```powershell
cd services/web
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\vitest\vitest.mjs' run src/api/resources.test.ts
```

Expected: fails because `chatSessions.ts` and `apiPatch` do not exist.

- [ ] **Step 3: Add apiPatch helper**

In `client.ts`:

```ts
export async function apiPatch<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw await parseError(res)
  if (res.status === 204) return undefined as T
  return res.json()
}
```

In `client.test.ts`, add test:

```ts
it('sends PATCH requests and parses JSON responses', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 'x' }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })))

  await expect(apiPatch('/resource', { title: 'New' })).resolves.toEqual({ id: 'x' })

  expect(fetch).toHaveBeenCalledWith('/resource', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: 'New' }),
  })
})
```

Add import in `client.test.ts`:

```ts
import { apiDelete, apiGet, apiPatch, apiPost, apiUpload, checkHealth, HttpError } from './client'
```

- [ ] **Step 4: Add frontend types**

Append to `types/index.ts`:

```ts
export interface ChatSession {
  id: string
  kbId: string
  title: string
  status: 'active' | 'deleted'
  createdAt: string
  updatedAt: string
}

export interface PersistedChatMessage extends ChatMessage {
  sessionId: string
  citations: Citation[]
  status: 'completed' | 'interrupted' | 'failed'
  createdAt: string
}

export interface ChatSessionDetail {
  session: ChatSession
  messages: PersistedChatMessage[]
}
```

- [ ] **Step 5: Add chat session API wrapper**

Create `chatSessions.ts`:

```ts
import type { ChatSession, ChatSessionDetail } from '@/types'
import { apiDelete, apiGet, apiPatch, apiPost } from './client'

const base = (kbId: string) => `/api/v1/knowledge-bases/${kbId}/chat-sessions`

export function listChatSessions(kbId: string): Promise<ChatSession[]> {
  return apiGet<ChatSession[]>(base(kbId))
}

export function getChatSession(kbId: string, sessionId: string): Promise<ChatSessionDetail> {
  return apiGet<ChatSessionDetail>(`${base(kbId)}/${sessionId}`)
}

export function createChatSession(kbId: string, title?: string): Promise<ChatSession> {
  return apiPost<ChatSession>(base(kbId), title ? { title } : {})
}

export function renameChatSession(kbId: string, sessionId: string, title: string): Promise<ChatSession> {
  return apiPatch<ChatSession>(`${base(kbId)}/${sessionId}`, { title })
}

export function deleteChatSession(kbId: string, sessionId: string): Promise<void> {
  return apiDelete(`${base(kbId)}/${sessionId}`)
}

export function batchDeleteChatSessions(kbId: string, sessionIds: string[]): Promise<void> {
  return apiPost<void>(`${base(kbId)}/batch-delete`, { sessionIds })
}
```

- [ ] **Step 6: Run frontend API tests**

Run:

```powershell
cd services/web
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\vitest\vitest.mjs' run src/api/client.test.ts src/api/resources.test.ts
```

Expected: all tests pass.

- [ ] **Step 7: Commit frontend API client**

```powershell
git add services/web/src/types/index.ts `
  services/web/src/api/chatSessions.ts `
  services/web/src/api/resources.test.ts `
  services/web/src/api/client.ts `
  services/web/src/api/client.test.ts
git commit -m "feat: add chat session web client"
```

---

## Task 5: Frontend History Sidebar and ChatPanel Integration

**Files:**
- Create: `services/web/src/components/ChatHistorySidebar.tsx`
- Create: `services/web/src/components/ChatHistoryDrawer.tsx`
- Modify: `services/web/src/components/ChatPanel.tsx`
- Modify: `services/web/src/api/chat.test.ts`

- [ ] **Step 1: Extend streamChat done payload test**

In `chat.test.ts`, update or add assertion that `streamChat` sends `sessionId` only when present:

```ts
it('omits session id for new conversations', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([
    'event: done\ndata: {"sessionId":"new-session"}\n\n',
  ])))
  const events: string[] = []

  await streamChat('kb', 'new question', {
    onDone: (sessionId) => events.push(sessionId),
  })

  expect(fetch).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/chat', expect.objectContaining({
    body: JSON.stringify({ query: 'new question', stream: true }),
  }))
  expect(events).toEqual(['new-session'])
})
```

- [ ] **Step 2: Modify streamChat body construction**

In `chat.ts`, replace body line:

```ts
body: JSON.stringify({ query, stream: true, sessionId }),
```

With:

```ts
body: JSON.stringify(sessionId ? { query, stream: true, sessionId } : { query, stream: true }),
```

- [ ] **Step 3: Create ChatHistorySidebar**

Create `ChatHistorySidebar.tsx`:

```tsx
import type { ChatSession } from '@/types'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Check, Edit2, Plus, Trash2, X } from 'lucide-react'
import { useState } from 'react'
import { cn } from '@/lib/utils'

interface Props {
  sessions: ChatSession[]
  activeSessionId: string | null
  streaming: boolean
  onNewSession: () => void
  onSelectSession: (sessionId: string) => void
  onRenameSession: (sessionId: string, title: string) => Promise<void>
  onDeleteSession: (sessionId: string) => Promise<void>
  onBatchDelete: (sessionIds: string[]) => Promise<void>
}

export function ChatHistorySidebar({
  sessions,
  activeSessionId,
  streaming,
  onNewSession,
  onSelectSession,
  onRenameSession,
  onDeleteSession,
  onBatchDelete,
}: Props) {
  const [selectionMode, setSelectionMode] = useState(false)
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [editingId, setEditingId] = useState<string | null>(null)
  const [draftTitle, setDraftTitle] = useState('')

  const toggleSelected = (sessionId: string) => {
    setSelectedIds((prev) =>
      prev.includes(sessionId) ? prev.filter((id) => id !== sessionId) : [...prev, sessionId],
    )
  }

  const confirmBatchDelete = async () => {
    if (selectedIds.length === 0) return
    if (!confirm(`确定删除选中的 ${selectedIds.length} 条会话吗？此操作不可恢复。`)) return
    await onBatchDelete(selectedIds)
    setSelectedIds([])
    setSelectionMode(false)
  }

  return (
    <aside className="w-full shrink-0 rounded-lg border bg-card p-3 md:w-72">
      <div className="mb-3 flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold">历史会话</h3>
        <div className="flex gap-1">
          <Button size="sm" variant="ghost" onClick={onNewSession} disabled={streaming}>
            <Plus className="h-4 w-4" />
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setSelectionMode((v) => !v)}>
            {selectionMode ? <X className="h-4 w-4" /> : <Check className="h-4 w-4" />}
          </Button>
        </div>
      </div>

      {selectionMode && (
        <div className="mb-3 flex items-center justify-between rounded-md bg-muted p-2 text-xs">
          <span>已选择 {selectedIds.length} 条</span>
          <Button size="sm" variant="destructive" onClick={confirmBatchDelete} disabled={selectedIds.length === 0}>
            删除
          </Button>
        </div>
      )}

      <div className="space-y-1">
        {sessions.length === 0 && (
          <p className="rounded-md bg-muted p-3 text-xs text-muted-foreground">暂无历史会话</p>
        )}
        {sessions.map((session) => (
          <div
            key={session.id}
            className={cn(
              'rounded-md border p-2 text-sm',
              session.id === activeSessionId ? 'border-primary bg-primary/5' : 'bg-background',
            )}
          >
            <div className="flex items-start gap-2">
              {selectionMode && (
                <input
                  type="checkbox"
                  checked={selectedIds.includes(session.id)}
                  onChange={() => toggleSelected(session.id)}
                  className="mt-1"
                />
              )}
              <button
                className="min-w-0 flex-1 text-left"
                disabled={streaming || selectionMode}
                onClick={() => onSelectSession(session.id)}
              >
                {editingId === session.id ? (
                  <Input
                    value={draftTitle}
                    onChange={(e) => setDraftTitle(e.target.value)}
                    onClick={(e) => e.stopPropagation()}
                  />
                ) : (
                  <span className="line-clamp-2 break-words">{session.title}</span>
                )}
              </button>
              <div className="flex shrink-0 gap-1">
                {editingId === session.id ? (
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={async () => {
                      await onRenameSession(session.id, draftTitle)
                      setEditingId(null)
                    }}
                  >
                    <Check className="h-3 w-3" />
                  </Button>
                ) : (
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => {
                      setEditingId(session.id)
                      setDraftTitle(session.title)
                    }}
                  >
                    <Edit2 className="h-3 w-3" />
                  </Button>
                )}
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={async () => {
                    if (confirm('确定删除这条会话吗？此操作不可恢复。')) {
                      await onDeleteSession(session.id)
                    }
                  }}
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
              </div>
            </div>
          </div>
        ))}
      </div>
    </aside>
  )
}
```

- [ ] **Step 4: Create ChatHistoryDrawer**

Create `ChatHistoryDrawer.tsx`:

```tsx
import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { Menu, X } from 'lucide-react'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  children: ReactNode
}

export function ChatHistoryDrawer({ open, onOpenChange, children }: Props) {
  return (
    <>
      <Button className="mb-3 md:hidden" variant="outline" onClick={() => onOpenChange(true)}>
        <Menu className="h-4 w-4" />
        历史会话
      </Button>
      {open && (
        <div className="fixed inset-0 z-50 md:hidden">
          <div className="absolute inset-0 bg-black/50" onClick={() => onOpenChange(false)} />
          <div className="absolute left-0 top-0 h-full w-80 max-w-[85vw] overflow-y-auto bg-background p-4 shadow-xl">
            <div className="mb-3 flex justify-end">
              <Button size="sm" variant="ghost" onClick={() => onOpenChange(false)}>
                <X className="h-4 w-4" />
              </Button>
            </div>
            {children}
          </div>
        </div>
      )}
    </>
  )
}
```

- [ ] **Step 5: Refactor ChatPanel state**

In `ChatPanel.tsx`, add imports:

```tsx
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  batchDeleteChatSessions,
  deleteChatSession,
  getChatSession,
  listChatSessions,
  renameChatSession,
} from '@/api/chatSessions'
import type { ChatMessage, ChatSession, Citation } from '@/types'
import { ChatHistorySidebar } from '@/components/ChatHistorySidebar'
import { ChatHistoryDrawer } from '@/components/ChatHistoryDrawer'
```

Add state:

```tsx
const [sessions, setSessions] = useState<ChatSession[]>([])
const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
const [historyOpen, setHistoryOpen] = useState(false)
```

Add loaders:

```tsx
const loadSessions = useCallback(async () => {
  try {
    const items = await listChatSessions(kbId)
    setSessions(items)
    if (!activeSessionId && items.length > 0) {
      setActiveSessionId(items[0].id)
    }
  } catch (e) {
    showError(e instanceof Error ? e.message : '历史会话加载失败')
  }
}, [activeSessionId, kbId, showError])

const loadSessionDetail = useCallback(async (sessionId: string) => {
  try {
    const detail = await getChatSession(kbId, sessionId)
    setActiveSessionId(sessionId)
    setMessages(detail.messages.map((m) => ({
      id: m.id,
      role: m.role,
      content: m.content,
      citations: m.citations,
      streaming: false,
    })))
    const lastAssistant = [...detail.messages].reverse().find((m) => m.role === 'assistant')
    setCitations(lastAssistant?.citations ?? [])
  } catch (e) {
    showError(e instanceof Error ? e.message : '会话详情加载失败')
  }
}, [kbId, showError])

useEffect(() => {
  loadSessions()
}, [loadSessions])

useEffect(() => {
  if (activeSessionId && messages.length === 0 && !streaming) {
    loadSessionDetail(activeSessionId)
  }
}, [activeSessionId, loadSessionDetail, messages.length, streaming])
```

Add handlers:

```tsx
const startNewSession = () => {
  if (streaming) return
  setActiveSessionId(null)
  setMessages([])
  setCitations([])
  sessionIdRef.current = null
}

const selectSession = (sessionId: string) => {
  if (streaming) {
    showError('请等待当前回答完成或先停止生成')
    return
  }
  loadSessionDetail(sessionId)
  setHistoryOpen(false)
}

const renameSession = async (sessionId: string, title: string) => {
  const nextTitle = title.trim()
  if (!nextTitle) {
    showError('会话标题不能为空')
    return
  }
  const updated = await renameChatSession(kbId, sessionId, nextTitle)
  setSessions((prev) => prev.map((s) => (s.id === sessionId ? updated : s)))
}

const removeSession = async (sessionId: string) => {
  await deleteChatSession(kbId, sessionId)
  const remaining = sessions.filter((s) => s.id !== sessionId)
  setSessions(remaining)
  if (activeSessionId === sessionId) {
    const next = remaining[0]
    if (next) await loadSessionDetail(next.id)
    else startNewSession()
  }
}

const removeSessions = async (sessionIds: string[]) => {
  await batchDeleteChatSessions(kbId, sessionIds)
  const remaining = sessions.filter((s) => !sessionIds.includes(s.id))
  setSessions(remaining)
  if (activeSessionId && sessionIds.includes(activeSessionId)) {
    const next = remaining[0]
    if (next) await loadSessionDetail(next.id)
    else startNewSession()
  }
}
```

Update `send()` call to `streamChat`:

```tsx
const sessionId = activeSessionId
sessionIdRef.current = sessionId
```

Then pass `sessionId ?? undefined`.

In `onDone`, update active session and refresh sessions:

```tsx
onDone: (sid) => {
  const resolvedSessionId = sid || sessionId
  if (resolvedSessionId) {
    sessionIdRef.current = resolvedSessionId
    setActiveSessionId(resolvedSessionId)
  }
  setMessages((prev) =>
    prev.map((m) => (m.id === assistantId ? { ...m, streaming: false } : m)),
  )
  setStreaming(false)
  loadSessions()
},
```

Render layout:

```tsx
const history = (
  <ChatHistorySidebar
    sessions={sessions}
    activeSessionId={activeSessionId}
    streaming={streaming}
    onNewSession={startNewSession}
    onSelectSession={selectSession}
    onRenameSession={renameSession}
    onDeleteSession={removeSession}
    onBatchDelete={removeSessions}
  />
)

return (
  <div className="flex min-w-0 gap-4">
    <div className="hidden md:block">{history}</div>
    <div className="flex min-w-0 flex-1 flex-col">
      <ChatHistoryDrawer open={historyOpen} onOpenChange={setHistoryOpen}>
        {history}
      </ChatHistoryDrawer>
      {/* existing chat content */}
    </div>
    {/* existing citations aside */}
  </div>
)
```

- [ ] **Step 6: Run focused frontend tests**

Run:

```powershell
cd services/web
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\vitest\vitest.mjs' run src/api/chat.test.ts src/api/resources.test.ts
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\typescript\bin\tsc' -b
```

Expected: Vitest and TypeScript pass.

- [ ] **Step 7: Commit frontend history UI**

```powershell
git add services/web/src/components/ChatHistorySidebar.tsx `
  services/web/src/components/ChatHistoryDrawer.tsx `
  services/web/src/components/ChatPanel.tsx `
  services/web/src/api/chat.ts `
  services/web/src/api/chat.test.ts
git commit -m "feat: add chat session history UI"
```

---

## Task 6: E2E Coverage and Final Verification

**Files:**
- Modify: `scripts/e2e-main-flow.ps1` or create `scripts/e2e-chat-session-history.ps1`
- Test all changed code

- [ ] **Step 1: Add E2E session checks**

If modifying `scripts/e2e-main-flow.ps1`, add after Chat SSE step:

```powershell
Step "9. Chat session list" {
    $sessions = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat-sessions"
    if (-not $sessions -or $sessions.Count -lt 1) { throw "no chat sessions persisted" }
    $script:chatSessionId = $sessions[0].id
    "sessions=$($sessions.Count) first=$($script:chatSessionId)"
}

Step "10. Chat session detail" {
    $detail = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat-sessions/$($script:chatSessionId)"
    if ($detail.messages.Count -lt 2) { throw "expected user and assistant messages" }
    "messages=$($detail.messages.Count)"
}

Step "11. Rename chat session" {
    $renamed = Invoke-Json "PATCH" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat-sessions/$($script:chatSessionId)" @{ title = "e2e-renamed-session" }
    if ($renamed.title -ne "e2e-renamed-session") { throw "rename failed" }
    $renamed.title
}

Step "12. Delete chat session" {
    Invoke-WebRequest -UseBasicParsing -Method DELETE -Uri "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat-sessions/$($script:chatSessionId)" -TimeoutSec 30 | Out-Null
    $sessions = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat-sessions"
    $found = $sessions | Where-Object { $_.id -eq $script:chatSessionId } | Select-Object -First 1
    if ($found) { throw "deleted session still listed" }
    "deleted"
}
```

Also add near top:

```powershell
$script:chatSessionId = $null
```

- [ ] **Step 2: Run full API verification**

Run:

```powershell
cd services/api
mvn verify
```

Expected:

- `Tests run` includes new tests.
- `Failures: 0, Errors: 0`.
- `BUILD SUCCESS`.
- `All coverage checks have been met.`

- [ ] **Step 3: Run full frontend verification**

Run:

```powershell
cd services/web
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\vitest\vitest.mjs' run --coverage
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\typescript\bin\tsc' -b
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\vite\bin\vite.js' build
```

Expected:

- Vitest test files pass.
- Coverage remains above project threshold.
- TypeScript exits 0.
- Vite prints `built`.

- [ ] **Step 4: Run E2E with isolated Milvus collection**

Run:

```powershell
$override = Join-Path $env:TEMP 'dupi-compose-e2e.yml'
@'
services:
  api:
    environment:
      MILVUS_COLLECTION: dupi_chunks_e2e
  worker:
    environment:
      MILVUS_COLLECTION: dupi_chunks_e2e
'@ | Set-Content -Path $override -Encoding utf8
docker compose -f deploy/docker-compose.yml -f $override up -d --build
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/e2e-main-flow.ps1 -BaseUrl "http://localhost:8080" -PollSeconds 180 -PollInterval 3
```

Expected:

- All original 8 E2E steps pass.
- New chat session persistence steps pass.
- Report writes to `scripts/e2e-last-run.json`.

- [ ] **Step 5: Check worktree and diff hygiene**

Run:

```powershell
git diff --check
git status --short
```

Expected:

- `git diff --check` has no whitespace errors.
- Only intended source/test/docs/script files are modified.
- `.superpowers/` is not staged.

- [ ] **Step 6: Commit verification script updates**

```powershell
git add scripts/e2e-main-flow.ps1
git commit -m "test: verify chat session history e2e"
```

---

## Self-Review

### Spec coverage

- Per-KB session persistence: Tasks 1, 2, 3.
- User/assistant message persistence: Tasks 2, 3.
- Citation snapshots: Tasks 2, 3, 6.
- Session list/detail/rename/delete/batch delete APIs: Tasks 2, 3, 4.
- Desktop sidebar and mobile drawer: Task 5.
- Default first-question title and rename: Tasks 2, 3, 5.
- Single delete and selection-mode batch delete: Tasks 3, 5.
- Reloading existing KB and restoring messages: Tasks 4, 5, 6.
- E2E verification: Task 6.

### Completeness scan

This plan avoids open-ended filler markers and vague implementation instructions. Each code-changing step includes concrete code or exact edits and commands.

### Type consistency

- Backend uses `ChatSessionResponse`, `ChatMessageResponse`, and `ChatSessionDetailResponse`.
- Frontend mirrors these as `ChatSession`, `PersistedChatMessage`, and `ChatSessionDetail`.
- API paths consistently use `/api/v1/knowledge-bases/{kbId}/chat-sessions`.
- Message citation shape reuses the existing `Citation` DTO/type.

---

## Execution Options

Plan complete and saved to `docs/superpowers/plans/2026-07-04-chat-session-history-implementation.md`. Two execution options:

**1. Subagent-Driven (recommended)** - Dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** - Execute tasks in this session using $superpower-executing-plans, batch execution with checkpoints.

Which approach?
