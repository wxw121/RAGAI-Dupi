package com.dupi.rag.service;

import com.dupi.rag.client.LlmClient;
import com.dupi.rag.config.RedisQueueProperties;
import com.dupi.rag.domain.entity.ChatSession;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.dto.ChatRequest;
import com.dupi.rag.dto.ChatSessionResponse;
import com.dupi.rag.dto.Citation;
import com.dupi.rag.dto.RetrievalHit;
import com.dupi.rag.dto.RetrieveResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock RetrievalService retrievalService;
    @Mock LlmClient llmClient;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ChatSessionService chatSessionService;

    ChatService service(RedisQueueProperties props) {
        return new ChatService(knowledgeBaseService, retrievalService, llmClient, redisTemplate, props, new ObjectMapper(),
                chatSessionService);
    }

    @Test
    void chatStreamsExistingSessionPersistsMessagesAndDoneEvent() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).topK(5).build();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(chatSessionService.findOrThrow(kbId, sessionId)).thenReturn(ChatSession.builder()
                .id(sessionId)
                .kbId(kbId)
                .build());
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
        request.setSessionId(sessionId.toString());

        var events = service(redisProps()).chatStream(kbId, request).collectList().block();

        assertThat(events).extracting(e -> e.event()).containsExactly("retrieval", "token", "token", "done");
        assertThat(events.get(3).data()).contains(sessionId.toString());
        verify(chatSessionService).saveUserMessage(kbId, sessionId, "问题");
        ArgumentCaptor<List<Citation>> citationsCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatSessionService).saveAssistantMessage(eq(kbId), eq(sessionId), eq("你好"), citationsCaptor.capture());
        assertThat(citationsCaptor.getValue()).hasSize(1);
        assertThat(citationsCaptor.getValue().get(0).getFileName()).isEqualTo("doc.md");
    }

    @Test
    void chatStreamTruncatesCitationsClampsTopKAndCreatesPersistedSession() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(500).build());
        when(chatSessionService.createForFirstQuestion(kbId, "问题")).thenReturn(ChatSessionResponse.builder()
                .id(sessionId)
                .kbId(kbId)
                .title("问题")
                .build());
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
        assertThat(events.get(1).data()).contains(sessionId.toString());
        verify(chatSessionService).saveUserMessage(kbId, sessionId, "问题");
        verify(chatSessionService, never()).saveAssistantMessage(any(), any(), anyString(), anyList());
    }

    @Test
    void chatStreamTurnsLlmErrorsIntoErrorEventAndPersistsPartialAssistantText() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(chatSessionService.findOrThrow(kbId, sessionId)).thenReturn(ChatSession.builder()
                .id(sessionId)
                .kbId(kbId)
                .build());
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().hits(List.of()).build());
        when(retrievalService.buildContext(List.of())).thenReturn("");
        when(llmClient.chatStream(anyString(), anyString())).thenReturn(Flux.concat(
                Flux.just("部", "分"),
                Flux.error(new IllegalStateException("llm down"))
        ));
        ChatRequest request = new ChatRequest();
        request.setQuery("问题");
        request.setSessionId(sessionId.toString());

        var events = service(redisProps()).chatStream(kbId, request).collectList().block();

        assertThat(events).extracting(e -> e.event()).containsExactly("retrieval", "token", "token", "error");
        assertThat(events.get(3).data()).isEqualTo("llm down");
        verify(chatSessionService).saveUserMessage(kbId, sessionId, "问题");
        verify(chatSessionService, times(1)).saveAssistantMessage(kbId, sessionId, "部分", List.of());
    }

    @Test
    void chatStreamPersistsPartialAssistantTextWhenCancelledAfterTokens() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(chatSessionService.findOrThrow(kbId, sessionId)).thenReturn(ChatSession.builder()
                .id(sessionId)
                .kbId(kbId)
                .build());
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().hits(List.of()).build());
        when(retrievalService.buildContext(List.of())).thenReturn("");
        ChatRequest request = new ChatRequest();
        request.setQuery("问题");
        request.setSessionId(sessionId.toString());
        ChatService service = service(redisProps());
        when(llmClient.chatStream(anyString(), anyString())).thenReturn(Flux.create(sink -> {
            sink.next("部");
            service.cancel(sessionId.toString());
            sink.next("分");
            sink.complete();
        }));

        var events = service.chatStream(kbId, request).collectList().block();

        assertThat(events).extracting(e -> e.event()).containsExactly("retrieval", "token", "done");
        assertThat(events.get(2).data()).contains(sessionId.toString());
        verify(chatSessionService).saveUserMessage(kbId, sessionId, "问题");
        verify(chatSessionService, times(1)).saveAssistantMessage(kbId, sessionId, "部", List.of());
        verify(redisTemplate).convertAndSend("cancel-channel", sessionId.toString());
    }

    @Test
    void chatStreamPersistsPartialAssistantTextWhenSubscriberCancelsAfterTokens() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(chatSessionService.findOrThrow(kbId, sessionId)).thenReturn(ChatSession.builder()
                .id(sessionId)
                .kbId(kbId)
                .build());
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().hits(List.of()).build());
        when(retrievalService.buildContext(List.of())).thenReturn("");
        Sinks.Many<String> tokens = Sinks.many().multicast().onBackpressureBuffer();
        when(llmClient.chatStream(anyString(), anyString())).thenReturn(tokens.asFlux());
        ChatRequest request = new ChatRequest();
        request.setQuery("闂");
        request.setSessionId(sessionId.toString());

        var subscription = service(redisProps()).chatStream(kbId, request).subscribe();
        tokens.tryEmitNext("閮?");
        subscription.dispose();
        Thread.sleep(50);

        verify(chatSessionService).saveUserMessage(kbId, sessionId, "闂");
        verify(chatSessionService, timeout(200).times(1)).saveAssistantMessage(kbId, sessionId, "閮?", List.of());
    }

    @Test
    void cancelTerminatesStreamPromptlyAndPersistsPartialAssistantText() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(chatSessionService.findOrThrow(kbId, sessionId)).thenReturn(ChatSession.builder()
                .id(sessionId)
                .kbId(kbId)
                .build());
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().hits(List.of()).build());
        when(retrievalService.buildContext(List.of())).thenReturn("");
        Sinks.Many<String> tokens = Sinks.many().multicast().onBackpressureBuffer();
        when(llmClient.chatStream(anyString(), anyString())).thenReturn(tokens.asFlux());
        ChatRequest request = new ChatRequest();
        request.setQuery("闂");
        request.setSessionId(sessionId.toString());
        AtomicReference<List<String>> events = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        ChatService service = service(redisProps());

        service.chatStream(kbId, request)
                .map(event -> event.event())
                .collectList()
                .doOnNext(events::set)
                .doFinally(signal -> completed.countDown())
                .subscribe();
        tokens.tryEmitNext("閮?");
        service.cancel(sessionId.toString());
        awaitCompletion(completed);

        assertThat(events.get()).containsExactly("retrieval", "token", "done");
        verify(chatSessionService).saveUserMessage(kbId, sessionId, "闂");
        verify(chatSessionService, times(1)).saveAssistantMessage(kbId, sessionId, "閮?", List.of());
        verify(redisTemplate).convertAndSend("cancel-channel", sessionId.toString());
    }

    @Test
    void chatStreamRejectsInvalidNonblankSessionIdWithoutPersistingMessages() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().hits(List.of()).build());
        when(retrievalService.buildContext(List.of())).thenReturn("");
        ChatRequest request = new ChatRequest();
        request.setQuery("闂");
        request.setSessionId("not-a-uuid");

        assertThatThrownBy(() -> service(redisProps()).chatStream(kbId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid sessionId");

        verify(chatSessionService, never()).findOrThrow(any(), any());
        verify(chatSessionService, never()).saveUserMessage(any(), any(), anyString());
        verify(chatSessionService, never()).saveAssistantMessage(any(), any(), anyString(), anyList());
    }

    @Test
    void chatStreamCreatesSessionForBlankSessionIdWithoutFindOrThrow() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(chatSessionService.createForFirstQuestion(kbId, "闂")).thenReturn(ChatSessionResponse.builder()
                .id(sessionId)
                .kbId(kbId)
                .title("闂")
                .build());
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().hits(List.of()).build());
        when(retrievalService.buildContext(List.of())).thenReturn("");
        when(llmClient.chatStream(anyString(), anyString())).thenReturn(Flux.empty());
        ChatRequest request = new ChatRequest();
        request.setQuery("闂");
        request.setSessionId("   ");

        var events = service(redisProps()).chatStream(kbId, request).collectList().block();

        assertThat(events).extracting(e -> e.event()).containsExactly("retrieval", "done");
        verify(chatSessionService).createForFirstQuestion(kbId, "闂");
        verify(chatSessionService, never()).findOrThrow(any(), any());
        verify(chatSessionService).saveUserMessage(kbId, sessionId, "闂");
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

    private static void awaitCompletion(CountDownLatch completed) {
        try {
            assertThat(completed.await(Duration.ofMillis(500).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
