package com.dupi.rag.service;

import com.dupi.rag.client.LlmClient;
import com.dupi.rag.config.RedisQueueProperties;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String SYSTEM_PROMPT = """
            你是企业知识库助手。请仅根据提供的上下文回答问题。
            如果上下文中没有足够信息，请明确说明「根据现有知识库资料无法回答」。
            
            上下文中的每个片段已是 Markdown 格式，并标注 section（章节）与 type（prose/table/code）。
            回答时请保持与上下文相同的 Markdown 结构，不要压成一行。
            
            输出格式要求（必须遵守）：
            1. 使用标准 Markdown；章节标题用「## 标题」（# 后必须有空格），禁止使用一级标题「#」
            2. 步骤列表从 1 开始连续编号，每行一项；不要用上下文片段编号（如 [5]）作为列表序号
            3. 架构类问题按检索到的 section 分节组织（如 ## 技术栈、## 模块划分）
            4. type=table 的片段：表格必须逐行输出，表头、分隔行、数据行各占一行
            5. type=code 的片段：命令与配置放在 ```bash 或 ```env 代码块中
            6. 目录树、部署链路放在 ```text 代码块中，保持换行
            7. 不同章节之间用空行分隔；禁止输出行尾 #、---#、[1]# 等残留符号
            8. 引用上下文时在句末标注 [编号]；禁止对整段加粗，仅对少量关键词使用 **加粗**
            9. 开头不要写「输出：」等元描述，直接给出答案
            
            保持准确、结构清晰、便于阅读与逐步执行。
            """;

    private final KnowledgeBaseService knowledgeBaseService;
    private final RetrievalService retrievalService;
    private final LlmClient llmClient;
    private final StringRedisTemplate redisTemplate;
    private final RedisQueueProperties queueProperties;
    private final ObjectMapper objectMapper;
    private final ChatSessionService chatSessionService;

    private final Set<String> cancelledSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<CancellationRegistration>> cancellationSignals = new ConcurrentHashMap<>();

    public Flux<ServerSentEvent<String>> chatStream(UUID kbId, ChatRequest request) {
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        UUID persistedSessionId = resolveSessionId(kbId, request);
        String sessionId = persistedSessionId.toString();

        return Flux.defer(() -> {
            CancellationRegistration cancellation = new CancellationRegistration();
            cancellationSignals.compute(sessionId, (ignored, activeRegistrations) -> {
                Set<CancellationRegistration> registrations = activeRegistrations;
                if (registrations == null) {
                    registrations = ConcurrentHashMap.newKeySet();
                }
                registrations.add(cancellation);
                return registrations;
            });

            try {
                RetrieveRequest retrieveRequest = new RetrieveRequest();
                retrieveRequest.setQuery(request.getQuery());
                retrieveRequest.setTopK(clampTopK(request.getTopK() != null ? request.getTopK() : kb.getTopK()));
                retrieveRequest.setUseRerank(request.getUseRerank());

                RetrieveResponse retrieval = retrievalService.retrieve(kbId, retrieveRequest);
                List<Citation> citations = retrieval.getHits().stream()
                        .map(hit -> Citation.builder()
                                .chunkId(hit.getChunkId())
                                .docId(hit.getDocId())
                                .fileName(hit.getFileName())
                                .snippet(truncate(hit.getContent(), 200))
                                .score(hit.getScore())
                                .build())
                        .collect(Collectors.toList());

                String context = retrievalService.buildContext(retrieval.getHits());
                String userPrompt = "上下文：\n" + context + "\n\n问题：" + request.getQuery();

                chatSessionService.saveUserMessage(kbId, persistedSessionId, request.getQuery());

                Flux<ServerSentEvent<String>> retrievalEvent;
                try {
                    retrievalEvent = Flux.just(ServerSentEvent.<String>builder()
                            .event("retrieval")
                            .data(objectMapper.writeValueAsString(Map.of(
                                    "citations", citations,
                                    "diagnostics", retrieval.getDiagnostics() != null ? retrieval.getDiagnostics() : Map.of()
                            )))
                            .build());
                } catch (Exception e) {
                    retrievalEvent = Flux.empty();
                }

                StringBuilder assistantBuffer = new StringBuilder();
                AtomicBoolean assistantSaved = new AtomicBoolean(false);
                Runnable saveAssistantIfPresent = () -> {
                    if (assistantSaved.compareAndSet(false, true) && !assistantBuffer.isEmpty()) {
                        chatSessionService.saveAssistantMessage(kbId, persistedSessionId, assistantBuffer.toString(), citations);
                    }
                };
                Flux<ServerSentEvent<String>> tokenEvents = cancellation.cancelled.get()
                        ? Flux.empty()
                        : llmClient.chatStream(SYSTEM_PROMPT, userPrompt)
                                .takeWhile(token -> !cancellation.cancelled.get())
                                .takeUntilOther(cancellation.signal.asMono())
                                .doOnNext(assistantBuffer::append)
                                .map(token -> ServerSentEvent.<String>builder()
                                        .event("token")
                                        .data(token)
                                        .build())
                                .doOnComplete(saveAssistantIfPresent)
                                .doOnError(ex -> saveAssistantIfPresent.run());

                Flux<ServerSentEvent<String>> doneEvent = Flux.just(
                        ServerSentEvent.<String>builder().event("done").data("{\"sessionId\":\"" + sessionId + "\"}").build()
                );

                return Flux.concat(retrievalEvent, tokenEvents, doneEvent)
                        .onErrorResume(ex -> Flux.just(
                                ServerSentEvent.<String>builder().event("error").data(ex.getMessage()).build()
                        ))
                        .doFinally(signal -> {
                            if (signal == SignalType.CANCEL) {
                                saveAssistantIfPresent.run();
                            }
                            removeCancellationRegistration(sessionId, cancellation);
                        });
            } catch (Exception ex) {
                removeCancellationRegistration(sessionId, cancellation);
                return Flux.error(ex);
            }
        });
    }

    public String chat(UUID kbId, ChatRequest request) {
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        RetrieveRequest retrieveRequest = new RetrieveRequest();
        retrieveRequest.setQuery(request.getQuery());
        retrieveRequest.setTopK(clampTopK(request.getTopK() != null ? request.getTopK() : kb.getTopK()));
        retrieveRequest.setUseRerank(request.getUseRerank());

        RetrieveResponse retrieval = retrievalService.retrieve(kbId, retrieveRequest);
        String context = retrievalService.buildContext(retrieval.getHits());
        String userPrompt = "上下文：\n" + context + "\n\n问题：" + request.getQuery();
        return llmClient.chat(SYSTEM_PROMPT, userPrompt);
    }

    public void cancel(String sessionId) {
        AtomicBoolean emittedToActiveStream = new AtomicBoolean(false);
        List<CancellationRegistration> registrationsToSignal = new ArrayList<>();
        cancellationSignals.computeIfPresent(sessionId, (ignored, activeRegistrations) -> {
            if (activeRegistrations.isEmpty()) {
                return null;
            }
            cancelledSessions.add(sessionId);
            emittedToActiveStream.set(true);
            activeRegistrations.forEach(registration -> {
                registration.cancelled.set(true);
                registrationsToSignal.add(registration);
            });
            return activeRegistrations;
        });
        registrationsToSignal.forEach(registration -> registration.signal.tryEmitEmpty());
        if (!emittedToActiveStream.get()) {
            cancelledSessions.remove(sessionId);
        }
        redisTemplate.convertAndSend(queueProperties.getCancelChannel(), sessionId);
    }

    private void removeCancellationRegistration(String sessionId, CancellationRegistration cancellation) {
        AtomicBoolean hasRemainingCancelledRegistration = new AtomicBoolean(false);
        cancellationSignals.computeIfPresent(sessionId, (ignored, activeRegistrations) -> {
            activeRegistrations.remove(cancellation);
            activeRegistrations.forEach(registration -> {
                if (registration.cancelled.get()) {
                    hasRemainingCancelledRegistration.set(true);
                }
            });
            return activeRegistrations.isEmpty() ? null : activeRegistrations;
        });
        if (!hasRemainingCancelledRegistration.get()) {
            cancelledSessions.remove(sessionId);
        }
    }

    private static final class CancellationRegistration {
        private final Sinks.Empty<Void> signal = Sinks.empty();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private int clampTopK(Integer topK) {
        if (topK == null) {
            return 5;
        }
        return Math.max(1, Math.min(50, topK));
    }

    private UUID resolveSessionId(UUID kbId, ChatRequest request) {
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            return chatSessionService.createForFirstQuestion(kbId, request.getQuery()).getId();
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(request.getSessionId());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid sessionId", ex);
        }
        chatSessionService.findOrThrow(kbId, sessionId);
        return sessionId;
    }
}
