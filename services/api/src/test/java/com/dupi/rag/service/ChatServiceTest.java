package com.dupi.rag.service;

import com.dupi.rag.client.LlmClient;
import com.dupi.rag.config.RedisQueueProperties;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.dto.ChatRequest;
import com.dupi.rag.dto.RetrievalHit;
import com.dupi.rag.dto.RetrieveResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock RetrievalService retrievalService;
    @Mock LlmClient llmClient;
    @Mock StringRedisTemplate redisTemplate;

    ChatService service(RedisQueueProperties props) {
        return new ChatService(knowledgeBaseService, retrievalService, llmClient, redisTemplate, props, new ObjectMapper());
    }

    @Test
    void chatStreamsRetrievalTokensAndDoneEvent() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).topK(5).build();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder()
                .hits(List.of(RetrievalHit.builder()
                        .chunkId(UUID.randomUUID())
                        .docId(UUID.randomUUID())
                        .fileName("doc.md")
                        .content("abcdefghijklmnopqrstuvwxyz")
                        .score(0.8)
                        .metadata(Map.of())
                        .build()))
                .build());
        when(retrievalService.buildContext(anyList())).thenReturn("context");
        when(llmClient.chatStream(anyString(), contains("context"))).thenReturn(Flux.just("你", "好"));
        ChatRequest request = new ChatRequest();
        request.setQuery("问题");
        request.setSessionId("s1");

        var events = service(redisProps()).chatStream(kbId, request).collectList().block();

        assertThat(events).extracting(e -> e.event()).containsExactly("retrieval", "token", "token", "done");
        assertThat(events.get(3).data()).contains("s1");
    }

    @Test
    void chatStreamTruncatesCitationsClampsTopKAndGeneratesSessionId() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(500).build());
        String longContent = "x".repeat(250);
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder()
                .hits(List.of(RetrievalHit.builder()
                        .chunkId(UUID.randomUUID())
                        .docId(UUID.randomUUID())
                        .fileName("doc.md")
                        .content(longContent)
                        .score(0.8)
                        .metadata(Map.of())
                        .build()))
                .build());
        when(retrievalService.buildContext(anyList())).thenReturn("context");
        when(llmClient.chatStream(anyString(), anyString())).thenReturn(Flux.empty());
        ChatRequest request = new ChatRequest();
        request.setQuery("问题");

        var events = service(redisProps()).chatStream(kbId, request).collectList().block();

        ArgumentCaptor<com.dupi.rag.dto.RetrieveRequest> captor = ArgumentCaptor.forClass(com.dupi.rag.dto.RetrieveRequest.class);
        verify(retrievalService).retrieve(eq(kbId), captor.capture());
        assertThat(captor.getValue().getTopK()).isEqualTo(50);
        assertThat(events).extracting(e -> e.event()).containsExactly("retrieval", "done");
        assertThat(events.get(0).data()).contains("...").contains("doc.md");
        assertThat(events.get(1).data()).contains("sessionId");
    }

    @Test
    void chatStreamTurnsLlmErrorsIntoErrorEventAndRemovesCancelledSession() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().hits(List.of()).build());
        when(retrievalService.buildContext(List.of())).thenReturn("");
        when(llmClient.chatStream(anyString(), anyString())).thenReturn(Flux.error(new IllegalStateException("llm down")));
        ChatRequest request = new ChatRequest();
        request.setQuery("问题");
        request.setSessionId("s-error");

        var events = service(redisProps()).chatStream(kbId, request).collectList().block();

        assertThat(events).extracting(e -> e.event()).containsExactly("retrieval", "error");
        assertThat(events.get(1).data()).isEqualTo("llm down");
    }

    @Test
    void chatReturnsNonStreamingLlmAnswer() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().hits(List.of()).build());
        when(retrievalService.buildContext(List.of())).thenReturn("");
        when(llmClient.chat(anyString(), contains("问题"))).thenReturn("答案");
        ChatRequest request = new ChatRequest();
        request.setQuery("问题");

        assertThat(service(redisProps()).chat(kbId, request)).isEqualTo("答案");
    }

    @Test
    void cancelStoresSessionAndPublishesRedisMessage() {
        RedisQueueProperties props = redisProps();

        service(props).cancel("session-1");

        verify(redisTemplate).convertAndSend("cancel-channel", "session-1");
    }

    private static RedisQueueProperties redisProps() {
        RedisQueueProperties props = new RedisQueueProperties();
        props.setCancelChannel("cancel-channel");
        props.setIngestQueue("ingest");
        return props;
    }
}
