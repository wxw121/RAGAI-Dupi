<!-- language-switch -->
[English](../../../en/superpowers/plans/2026-07-04-chat-session-history-implementation.md)

#聊天记录实现计划

b> **对于代理工作者：**需要的子技能：使用$superpower-subagents（推荐）或$superpower- execution -plans逐个任务地执行此计划。步骤使用复选框（' -[]'）语法通过update_plan进行跟踪。

**目标：**坚持知识库聊天会话，以便用户可以重新打开知识库，恢复以前的对话，重命名会话，并删除一个或多个会话。

架构：在Spring API中添加一流的聊天会话和聊天消息持久性，然后调整React聊天UI来加载和管理每个知识库的会话。现有的‘ /chat ’ SSE端点仍然是实时回答路径，但现在创建或附加到持久会话，并存储用户消息、助理响应和引用快照。

**技术栈：** Spring Boot 3， JPA/Hibernate, Flyway, PostgreSQL JSONB, Reactor SSE, React 18, TypeScript, Vite, Vitest。

---

##文件结构

# # #的后端

创建“services/api/src/main/resources/db/migration/V2__chat_sessions.sql”
-增加‘ chat_sessions ’和‘ chat_messages ’。
-创建“services/api/src/main/java/com/dupi/rag/domain/entity/ChatSession.java”
-一个知识库内一个会话的JPA实体。
-创建“services/api/src/main/java/com/dupi/rag/domain/entity/ChatMessage.java”
-带有引用JSON的用户/助理消息的JPA实体。
-创建“services/api/src/main/java/ com/dupi/rag/repository/chatsessionrepositoryjava”
—按KB查询会话、强制KB归属、按id删除会话。
-创建“services/api/src/main/java/com/dupi/rag/repository/ChatMessageRepository.java”
—按会话时间顺序查询。
-创建dto：
——“ChatSessionResponse.java”
——“ChatMessageResponse.java”
——“ChatSessionDetailResponse.java”
——“CreateChatSessionRequest.java”
——“UpdateChatSessionRequest.java”
——“BatchDeleteChatSessionsRequest.java”
-创建“services/api/src/main/java/com/dupi/rag/service/ChatSessionService.java”
—拥有会话CRUD、标题验证、消息持久化和KB所有权检查。
修改“services/api/src/main/java/com/dupi/rag/service/ChatService.java”
-在SSE期间使用‘ ChatSessionService ’创建/附加会话消息。
修改“services/api/src/main/java/com/dupi/rag/controller/KnowledgeBaseController.java”
-增加聊天会话管理端点。
-添加测试：
——“服务/ api / src /测试/ java / com/dupi/rag/service/ChatSessionServiceTest.java '
扩展“ChatServiceTest.java”
扩展“ControllerLayerTest.java”
扩展“DtoCoverageTest.java”

# # #前端

修改“services/web/src/types/index.ts”
-增加聊天会话/消息响应类型。
-创建“services/web/src/api/chatSessions.ts”
- API包装列表/详细/创建/重命名/删除/批量删除。
扩展“services/web/src/api/chat.test.ts”
-涵盖‘ streamChat ’会话创建行为。
扩展“services/web/src/api/resources.test.ts”
-涵盖聊天会话API路径。
创建“services/web/src/components/ChatHistorySidebar.tsx”
-桌面历史列表，单个删除，重命名，选择模式。
创建“services/web/src/components/ChatHistoryDrawer.tsx”
-移动抽屉包装相同的历史记录控件。
重构“services/web/src/components/ChatPanel.tsx”
-协调活动会话，历史列表，消息，引用，流状态。
-可选的小UI助手：
-重用“对话框”，“按钮”，“输入”，“文本区”。
-使用内置的‘ confirm() ’来删除确认，如果一个专用的确认对话框将扩展范围太大。

# # #验证

- API: ‘ mvn verify ’
- Web：捆绑Node ‘ Node .exe ’与Vitest覆盖，TypeScript构建，Vite构建
—端到端：扩展或添加一个脚本，用于在聊天SSE后验证持久的会话列表/详细信息/重命名/删除

---

任务1：后端数据库和实体

* *文件:* *
-创建：‘ services/api/src/main/resources/db/migration/V2__chat_sessions.sql ’
-创建：‘ services/api/src/main/java/com/dupi/rag/domain/entity/ChatSession.java ’
-创建：‘ services/api/src/main/java/com/dupi/rag/domain/entity/ChatMessage.java ’
-创建：‘ services/api/src/main/java/ com/dupi/rag/repository/chatsessionrepositoryjava ’
-创建：‘ services/api/src/main/java/com/dupi/rag/repository/ChatMessageRepository.java ’
-测试：‘ services/api/src/ Test /java/com/dupi/rag/domain/EntityLifecycleTest.java ’

-[] **步骤1：添加失败实体生命周期覆盖**

附加到‘ EntityLifecycleTest.java ’：

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

要添加的导入：

```java

import com.dupi.rag.domain.entity.ChatMessage;
import com.dupi.rag.domain.entity.ChatSession;
import java.util.UUID;

```

-[] **第二步：运行测试并验证失败**

运行:

```powershell

cd services/api
mvn -Dtest=EntityLifecycleTest test

```

预期：编译失败，因为‘ ChatSession ’和‘ ChatMessage ’不存在。

-[] **步骤3：添加飞道迁移**

创建“V2__chat_sessions.sql”:

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

-[] **步骤4：添加实体**

创建“ChatSession.java”:

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

创建“ChatMessage.java”:

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

-[] **步骤5：添加存储库**

创建“ChatSessionRepository.java”:

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

创建“ChatMessageRepository.java”:

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

-[] **步骤6：运行实体测试**

运行:

```powershell

cd services/api
mvn -Dtest=EntityLifecycleTest test

```

预期：“测试运行：2，失败：0，错误：0”。

-[] **步骤7：提交后端模式和实体**

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

任务2：后端dto和ChatSessionService

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

-[] **步骤1：写失败的ChatSessionService测试

创建“ChatSessionServiceTest.java”:

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

-[] **第二步：运行测试并验证失败**

运行:

```powershell

cd services/api
mvn -Dtest=ChatSessionServiceTest test

```

预期：编译失败，因为dto和‘ ChatSessionService ’不存在。

-[] **步骤3：添加dto **

创建“ChatSessionResponse.java”:

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

创建“ChatMessageResponse.java”:

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

创建“ChatSessionDetailResponse.java”:

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

创建“CreateChatSessionRequest.java”:

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

创建“UpdateChatSessionRequest.java”:

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

创建“BatchDeleteChatSessionsRequest.java”:

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

-[] **步骤4：添加业务实现**

创建“ChatSessionService.java”:

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

-[] **步骤5：扩展DTO覆盖范围**

添加到‘ DtoCoverageTest.responseDtosExposeAllBuilderFields() ’：

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

添加一个新测试：

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

-[] **步骤6：运行service和DTO测试**

运行:

```powershell

cd services/api
mvn -Dtest=ChatSessionServiceTest,DtoCoverageTest test

```

预期：所有测试都通过。

-[] **步骤7：提交服务和dto **

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

任务3：在SSE聊天期间持久化消息并添加会话api

**Files:**
- Modify: `services/api/src/main/java/com/dupi/rag/service/ChatService.java`
- Modify: `services/api/src/main/java/com/dupi/rag/controller/KnowledgeBaseController.java`
- Modify: `services/api/src/test/java/com/dupi/rag/service/ChatServiceTest.java`
- Modify: `services/api/src/test/java/com/dupi/rag/controller/ControllerLayerTest.java`

-[] **步骤1：更新失败的ChatService测试

在ChatServiceTest中，添加mock：

```java

@Mock ChatSessionService chatSessionService;

```

改变工厂:

```java

ChatService service(RedisQueueProperties props) {
    return new ChatService(knowledgeBaseService, retrievalService, llmClient, redisTemplate, props, new ObjectMapper(), chatSessionService);
}

```

在“chatStreamsRetrievalTokensAndDoneEvent”中，添加：

```java

UUID sessionUuid = UUID.randomUUID();
when(chatSessionService.findOrThrow(kbId, sessionUuid)).thenReturn(
        ChatSession.builder().id(sessionUuid).kbId(kbId).title("T").build());
request.setSessionId(sessionUuid.toString());

```

然后断言持久性：

```java

verify(chatSessionService).saveUserMessage(sessionUuid, "闂");
verify(chatSessionService).saveAssistantMessage(eq(sessionUuid), eq("浣犲ソ"), anyList(), eq("completed"));

```

为丢失的会话ID添加测试：

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

增加进口:

```java

import com.dupi.rag.domain.entity.ChatSession;

```

-[] **步骤2：运行ChatService测试并确认失败**

运行:

```powershell

cd services/api
mvn -Dtest=ChatServiceTest test

```

预期：编译失败，因为‘ ChatService ’构造函数和行为没有改变。

-[] **第三步：修改ChatService构造函数和流流

为ChatService添加字段：

```java

private final ChatSessionService chatSessionService;

```

在‘ chatStream ’开始时，在检索设置之后但在生成事件之前，解析会话：

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

更改令牌事件：

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

保持‘ doneEvent ’使用相同的‘ sessionId ’。

在‘ onErrorResume ’中，在返回错误之前保存失败的助理状态：

```java

.onErrorResume(ex -> {
    if (!assistantBuffer.isEmpty()) {
        chatSessionService.saveAssistantMessage(persistedSessionId, assistantBuffer.toString(), citations, "failed");
    }
    return Flux.just(ServerSentEvent.<String>builder().event("error").data(ex.getMessage()).build());
});

```

-[] **步骤4：添加控制器失效覆盖**

修改“ControllerLayerTest.knowledgeBaseControllerDelegatesCrudRetrieveChatCancelAndJobs()”:

添加:

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

添加断言:

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

-[] **步骤5：添加控制器端点**

通过添加‘ private final ChatSessionService ChatSessionService; ’来修改构造函数。

添加方法:

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

-[] **步骤6：运行重点API测试**

运行:

```powershell

cd services/api
mvn -Dtest=ChatServiceTest,ControllerLayerTest test

```

预期：所有测试都通过。

-[] **步骤7：提交API聊天持久性**

```powershell

git add services/api/src/main/java/com/dupi/rag/service/ChatService.java `
  services/api/src/main/java/com/dupi/rag/controller/KnowledgeBaseController.java `
  services/api/src/test/java/com/dupi/rag/service/ChatServiceTest.java `
  services/api/src/test/java/com/dupi/rag/controller/ControllerLayerTest.java
git commit -m "feat: persist chat messages during conversations"

```

---

任务4：前端API类型和会话客户端

* *文件:* *
-修改：‘ services/web/src/types/index.ts ’
-创建：“services/web/src/api/chatSessions.ts”
修改：‘ services/web/src/api/resources.test.ts ’
修改：“services/web/src/api/client.ts”
修改：‘ services/web/src/api/client.test.ts ’

-[] **步骤1：添加失败的API包装测试**

在“resources.test。ts的导入:

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

添加测试:

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

更新模拟:

```ts

apiPatch: vi.fn(),

```

-[] **步骤2：运行wrapper test，验证是否失败**

使用捆绑的Node运行：

```powershell

cd services/web
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\vitest\vitest.mjs' run src/api/resources.test.ts

```

预期：由于“chatSessions”失败。‘ ts ’和‘ apiPatch ’不存在。

-[] **第三步：添加apiPatch helper**

在“client.ts”:

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

在“client.test。，添加测试：

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

在“client.test.ts”中添加导入：

```ts

import { apiDelete, apiGet, apiPatch, apiPost, apiUpload, checkHealth, HttpError } from './client'

```

-[] **步骤4：添加前端类型

追加到‘ types/index.ts ’：

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

-[] **步骤5：添加聊天会话API包装器**

创建“chatSessions.ts”:

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

-[] **步骤6：运行前端API测试**

运行:

```powershell

cd services/web
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\vitest\vitest.mjs' run src/api/client.test.ts src/api/resources.test.ts

```

预期：所有测试都通过。

-[] **步骤7：提交前端API客户端

```powershell

git add services/web/src/types/index.ts `
  services/web/src/api/chatSessions.ts `
  services/web/src/api/resources.test.ts `
  services/web/src/api/client.ts `
  services/web/src/api/client.test.ts
git commit -m "feat: add chat session web client"

```

---

任务5：前端历史工具条和ChatPanel的集成

* *文件:* *
-创建：“services/web/src/components/ChatHistorySidebar.tsx”
-创建：“services/web/src/components/ChatHistoryDrawer.tsx”
修改：“services/web/src/components/ChatPanel.tsx”
修改：“services/web/src/api/chat.test.ts”

-[] **步骤1：扩展streamChat完成负载测试**

在“chat.test。更新或添加断言‘ streamChat ’只在存在时发送‘ sessionId ’：

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

-[] **第二步：修改流聊天主体结构**

在聊天。t’，替换体线：

```ts

body: JSON.stringify({ query, stream: true, sessionId }),

```

:

```ts

body: JSON.stringify(sessionId ? { query, stream: true, sessionId } : { query, stream: true }),

```

-[]第三步：创建ChatHistorySidebar

创建“ChatHistorySidebar.tsx”:

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

-[] **第四步：创建ChatHistoryDrawer

创建“ChatHistoryDrawer.tsx”:

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

-[]第五步：重构ChatPanel状态

在“ChatPanel。添加导入：

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

添加状态:

```tsx

const [sessions, setSessions] = useState<ChatSession[]>([])
const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
const [historyOpen, setHistoryOpen] = useState(false)

```

添加加载器:

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

添加处理程序:

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

更新‘ send() ’调用到‘ streamChat ’：

```tsx

const sessionId = activeSessionId
sessionIdRef.current = sessionId

```

然后传递sessionId ?？未定义的”。

在“onDone”中，更新活动会话和刷新会话：

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

呈现布局:

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

-[] **步骤6：运行集中的前端测试**

运行:

```powershell

cd services/web
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\vitest\vitest.mjs' run src/api/chat.test.ts src/api/resources.test.ts
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\typescript\bin\tsc' -b

```

期望：通过测试和TypeScript。

-[] **步骤7：提交前端历史UI**

```powershell

git add services/web/src/components/ChatHistorySidebar.tsx `
  services/web/src/components/ChatHistoryDrawer.tsx `
  services/web/src/components/ChatPanel.tsx `
  services/web/src/api/chat.ts `
  services/web/src/api/chat.test.ts
git commit -m "feat: add chat session history UI"

```

---

##任务6:E2E覆盖和最终验证

* *文件:* *
—修改：“scripts/e2e-main-flow”。或创建“scripts/e2e-chat-session-history.ps1”
-测试所有更改的代码

-[] **步骤1：添加端到端会话检查**

如果修改“scripts/e2e-main-flow”。ps1 '，添加后聊天SSE步骤：

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

也添加近顶部：

```powershell

$script:chatSessionId = $null

```

-[] **步骤2：运行完整API验证**

运行:

```powershell

cd services/api
mvn verify

```

预期:

-“测试运行”包括新测试。
—“Failures: 0, Errors: 0”。
-“建立成功”。
-“所有覆盖检查都已满足。”

-[] **步骤3：运行全前端验证**

运行:

```powershell

cd services/web
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\vitest\vitest.mjs' run --coverage
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\typescript\bin\tsc' -b
& 'C:\Users\Wxw\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' '.\node_modules\vite\bin\vite.js' build

```

预期:

—测试文件通过。
—覆盖率保持在项目阈值以上。
—TypeScript退出0。
- Vite打印‘ built ’。

-[] **步骤4：用分离的Milvus收集运行E2E **

运行:

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

预期:

-所有原来的8个E2E步骤通过。
-新的聊天会话持久性步骤通过。
-报告写入‘ scripts/e2e-last-run.json ’。

-[] **第五步：检查工作树和diff卫生**

运行:

```powershell

git diff --check
git status --short

```

预期:

- ‘ git diff——check ’没有空格错误。
-只修改预期的源/测试/文档/脚本文件。
- - - - - -”。“超级大国/”不是演戏。

-[] **第六步：提交校验脚本更新**

```powershell

git add scripts/e2e-main-flow.ps1
git commit -m "test: verify chat session history e2e"

```

---

# #自我回顾

###规格覆盖率

—每kb会话持久性：任务1、2、3。
—用户/助手消息持久性：任务2、3。
-引文快照：任务2、3、6。
—Session list/detail/rename/delete/batch delete api: Tasks 2,3,4。
-桌面边栏和移动抽屉：任务5。
-默认第一个问题的标题和重命名：任务2,3,5。
—单次删除和选择模式批量删除：任务3、5。
—重新加载现有的KB和恢复消息：任务4,5,6。
—端到端验证：任务6。

完整性扫描

该计划避免了开放式填充标记和模糊的实现说明。每个代码更改步骤都包括具体的代码或精确的编辑和命令。

###类型一致性

-后端使用‘ ChatSessionResponse ’， ‘ chatmessagerresponse ’和‘ ChatSessionDetailResponse ’。
-前端镜像这些为‘ ChatSession ’， ‘ PersistedChatMessage ’，和‘ ChatSessionDetail ’。
- API路径一致使用‘ / API /v1/知识库/{kbId}/chat-sessions ’。
-消息引用形状重用现有的“引用”DTO/类型。

---

##执行选项

计划完成并保存到‘ docs/superpowers/plans/2026-07-04-chat-session-history-implementation.md ’。两个执行选项：

* * 1。子代理驱动（推荐）** -每个任务调度一个新的子代理，任务之间的审查，快速迭代。

* * 2。内联执行** -使用$superpower- Execution -plans执行此会话中的任务，使用检查点批量执行。

哪种方法呢?
