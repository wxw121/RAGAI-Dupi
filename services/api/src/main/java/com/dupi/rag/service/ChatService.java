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

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String SYSTEM_PROMPT = """
            你是企业知识库助手。请仅根据提供的上下文回答问题。
            如果上下文中没有足够信息，请明确说明「根据现有知识库资料无法回答」。
            回答时引用相关片段编号，保持简洁准确。
            """;

    private final KnowledgeBaseService knowledgeBaseService;
    private final RetrievalService retrievalService;
    private final LlmClient llmClient;
    private final StringRedisTemplate redisTemplate;
    private final RedisQueueProperties queueProperties;
    private final ObjectMapper objectMapper;

    private final Set<String> cancelledSessions = ConcurrentHashMap.newKeySet();

    public Flux<ServerSentEvent<String>> chatStream(UUID kbId, ChatRequest request) {
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        RetrieveRequest retrieveRequest = new RetrieveRequest();
        retrieveRequest.setQuery(request.getQuery());
        retrieveRequest.setTopK(request.getTopK() != null ? request.getTopK() : kb.getTopK());
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

        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();

        Flux<ServerSentEvent<String>> retrievalEvent;
        try {
            retrievalEvent = Flux.just(ServerSentEvent.<String>builder()
                    .event("retrieval")
                    .data(objectMapper.writeValueAsString(citations))
                    .build());
        } catch (Exception e) {
            retrievalEvent = Flux.empty();
        }

        Flux<ServerSentEvent<String>> tokenEvents = llmClient.chatStream(SYSTEM_PROMPT, userPrompt)
                .takeWhile(token -> !cancelledSessions.contains(sessionId))
                .map(token -> ServerSentEvent.<String>builder()
                        .event("token")
                        .data(token)
                        .build())
                .doFinally(signal -> cancelledSessions.remove(sessionId));

        Flux<ServerSentEvent<String>> doneEvent = Flux.just(
                ServerSentEvent.<String>builder().event("done").data("{\"sessionId\":\"" + sessionId + "\"}").build()
        );

        return Flux.concat(retrievalEvent, tokenEvents, doneEvent)
                .onErrorResume(ex -> Flux.just(
                        ServerSentEvent.<String>builder().event("error").data(ex.getMessage()).build()
                ));
    }

    public String chat(UUID kbId, ChatRequest request) {
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        RetrieveRequest retrieveRequest = new RetrieveRequest();
        retrieveRequest.setQuery(request.getQuery());
        retrieveRequest.setTopK(request.getTopK() != null ? request.getTopK() : kb.getTopK());
        retrieveRequest.setUseRerank(request.getUseRerank());

        RetrieveResponse retrieval = retrievalService.retrieve(kbId, retrieveRequest);
        String context = retrievalService.buildContext(retrieval.getHits());
        String userPrompt = "上下文：\n" + context + "\n\n问题：" + request.getQuery();
        return llmClient.chat(SYSTEM_PROMPT, userPrompt);
    }

    public void cancel(String sessionId) {
        cancelledSessions.add(sessionId);
        redisTemplate.convertAndSend(queueProperties.getCancelChannel(), sessionId);
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
