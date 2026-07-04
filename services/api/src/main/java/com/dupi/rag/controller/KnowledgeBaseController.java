package com.dupi.rag.controller;

import com.dupi.rag.dto.*;
import com.dupi.rag.service.ChatService;
import com.dupi.rag.service.ChatSessionService;
import com.dupi.rag.service.IngestJobService;
import com.dupi.rag.service.KnowledgeBaseService;
import com.dupi.rag.service.RetrievalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final RetrievalService retrievalService;
    private final ChatService chatService;
    private final IngestJobService ingestJobService;
    private final ChatSessionService chatSessionService;

    @PostMapping
    public KnowledgeBaseResponse create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return knowledgeBaseService.create(request);
    }

    @GetMapping
    public java.util.List<KnowledgeBaseResponse> list() {
        return knowledgeBaseService.list();
    }

    @GetMapping("/{kbId}")
    public KnowledgeBaseResponse get(@PathVariable UUID kbId) {
        return knowledgeBaseService.get(kbId);
    }

    @DeleteMapping("/{kbId}")
    public void delete(@PathVariable UUID kbId) {
        knowledgeBaseService.delete(kbId);
    }

    @PostMapping("/{kbId}/retrieve")
    public RetrieveResponse retrieve(@PathVariable UUID kbId, @Valid @RequestBody RetrieveRequest request) {
        return retrievalService.retrieve(kbId, request);
    }

    @PostMapping(value = "/{kbId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@PathVariable UUID kbId, @Valid @RequestBody ChatRequest request) {
        if (Boolean.FALSE.equals(request.getStream())) {
            String answer = chatService.chat(kbId, request);
            return Flux.just(ServerSentEvent.<String>builder().event("token").data(answer).build(),
                    ServerSentEvent.<String>builder().event("done").data("{}").build());
        }
        return chatService.chatStream(kbId, request);
    }

    @PostMapping("/{kbId}/chat/cancel")
    public Map<String, String> cancelChat(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        if (sessionId != null) {
            chatService.cancel(sessionId);
        }
        return Map.of("status", "cancel_requested");
    }

    @GetMapping("/{kbId}/chat-sessions")
    public java.util.List<ChatSessionResponse> listChatSessions(@PathVariable UUID kbId) {
        return chatSessionService.list(kbId);
    }

    @PostMapping("/{kbId}/chat-sessions")
    public ChatSessionResponse createChatSession(
            @PathVariable UUID kbId,
            @Valid @RequestBody CreateChatSessionRequest request
    ) {
        return chatSessionService.create(kbId, request);
    }

    @GetMapping("/{kbId}/chat-sessions/{sessionId}")
    public ChatSessionDetailResponse getChatSession(@PathVariable UUID kbId, @PathVariable UUID sessionId) {
        return chatSessionService.getDetail(kbId, sessionId);
    }

    @PatchMapping("/{kbId}/chat-sessions/{sessionId}")
    public ChatSessionResponse renameChatSession(
            @PathVariable UUID kbId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody UpdateChatSessionRequest request
    ) {
        return chatSessionService.rename(kbId, sessionId, request);
    }

    @DeleteMapping("/{kbId}/chat-sessions/{sessionId}")
    public void deleteChatSession(@PathVariable UUID kbId, @PathVariable UUID sessionId) {
        chatSessionService.delete(kbId, sessionId);
    }

    @PostMapping("/{kbId}/chat-sessions/batch-delete")
    public void batchDeleteChatSessions(
            @PathVariable UUID kbId,
            @Valid @RequestBody BatchDeleteChatSessionsRequest request
    ) {
        chatSessionService.batchDelete(kbId, request.getSessionIds());
    }

    @GetMapping("/{kbId}/ingest-jobs")
    public java.util.List<IngestJobResponse> listJobs(@PathVariable UUID kbId) {
        return ingestJobService.listByKb(kbId);
    }
}
