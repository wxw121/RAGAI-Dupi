package com.dupi.rag.controller;

import com.dupi.rag.dto.*;
import com.dupi.rag.service.ChatService;
import com.dupi.rag.service.ChatSessionService;
import com.dupi.rag.service.IngestJobService;
import com.dupi.rag.service.KnowledgeBaseExportService;
import com.dupi.rag.service.KnowledgeBaseService;
import com.dupi.rag.service.RagEvalService;
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
    private final RagEvalService ragEvalService;
    private final KnowledgeBaseExportService knowledgeBaseExportService;

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

    @PatchMapping("/{kbId}/retrieval-profile")
    public KnowledgeBaseResponse updateRetrievalProfile(
            @PathVariable UUID kbId,
            @Valid @RequestBody UpdateKnowledgeBaseRetrievalProfileRequest request
    ) {
        return knowledgeBaseService.updateRetrievalProfile(
                kbId,
                request.getRetrievalProfile()
        );
    }

    @DeleteMapping("/{kbId}")
    public void delete(@PathVariable UUID kbId) {
        knowledgeBaseService.delete(kbId);
    }

    @GetMapping("/{kbId}/export")
    public KnowledgeBaseExportResponse exportKnowledgeBase(@PathVariable UUID kbId) {
        return knowledgeBaseExportService.exportKnowledgeBase(kbId);
    }

    @PostMapping("/import")
    public KnowledgeBaseResponse importKnowledgeBase(@Valid @RequestBody KnowledgeBaseImportRequest request) {
        return knowledgeBaseExportService.restore(request);
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

    @PostMapping("/{kbId}/ingest-jobs/{jobId}/retry")
    public IngestJobResponse retryJob(@PathVariable UUID kbId, @PathVariable UUID jobId) {
        return ingestJobService.retryForKnowledgeBase(kbId, jobId);
    }

    @PostMapping("/{kbId}/reindex")
    public java.util.List<IngestJobResponse> reindex(@PathVariable UUID kbId) {
        return ingestJobService.reindexKnowledgeBase(kbId);
    }

    @GetMapping("/{kbId}/rag-eval/cases")
    public java.util.List<RagEvalCaseResponse> listRagEvalCases(@PathVariable UUID kbId) {
        return ragEvalService.listCases(kbId);
    }

    @PostMapping("/{kbId}/rag-eval/cases")
    public RagEvalCaseResponse createRagEvalCase(
            @PathVariable UUID kbId,
            @Valid @RequestBody RagEvalCaseRequest request
    ) {
        return ragEvalService.createCase(kbId, request);
    }

    @PatchMapping("/{kbId}/rag-eval/cases/{caseId}")
    public RagEvalCaseResponse updateRagEvalCase(
            @PathVariable UUID kbId,
            @PathVariable UUID caseId,
            @Valid @RequestBody RagEvalCaseRequest request
    ) {
        return ragEvalService.updateCase(kbId, caseId, request);
    }

    @DeleteMapping("/{kbId}/rag-eval/cases/{caseId}")
    public void deleteRagEvalCase(@PathVariable UUID kbId, @PathVariable UUID caseId) {
        ragEvalService.deleteCase(kbId, caseId);
    }

    @GetMapping("/{kbId}/rag-eval/runs")
    public java.util.List<RagEvalRunResponse> listRagEvalRuns(@PathVariable UUID kbId) {
        return ragEvalService.listRuns(kbId);
    }

    @PostMapping("/{kbId}/rag-eval/runs")
    public RagEvalRunResponse runRagEval(
            @PathVariable UUID kbId,
            @RequestBody(required = false) RagEvalRunRequest request
    ) {
        return ragEvalService.run(
                kbId,
                request != null && Boolean.TRUE.equals(request.getUseRerank()),
                request == null ? null : request.getProfiles()
        );
    }
}
