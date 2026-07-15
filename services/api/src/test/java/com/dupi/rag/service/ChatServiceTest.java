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
import com.dupi.rag.exception.ChatPipelineException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void chatStreamTurnsLlmErrorsIntoErrorEventAndPersistsPartialAssistantText() throws Exception {
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
        @SuppressWarnings("unchecked")
        Map<String, Object> error = new ObjectMapper().readValue(events.get(3).data(), Map.class);
        assertThat(error).containsEntry("error", "chat_pipeline_error")
                .containsEntry("stage", "llm");
        assertThat(error.get("message")).asString().contains("llm down");
        assertThat(error.get("suggestion")).asString().contains("CHAT_API_KEY");
        assertThat(error.get("requestId")).asString().isNotBlank();
        verify(chatSessionService).saveUserMessage(kbId, sessionId, "问题");
        verify(chatSessionService, times(1)).saveAssistantMessage(kbId, sessionId, "部分", List.of());
    }

    @Test
    void chatStreamKeepsEntryTraceIdForErrorsEmittedAfterMdcIsCleared() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(chatSessionService.findOrThrow(kbId, sessionId)).thenReturn(ChatSession.builder()
                .id(sessionId)
                .kbId(kbId)
                .build());
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().hits(List.of()).build());
        when(retrievalService.buildContext(List.of())).thenReturn("");
        when(llmClient.chatStream(anyString(), anyString())).thenReturn(Flux.error(new IllegalStateException("later failure")));
        ChatRequest request = new ChatRequest();
        request.setQuery("question");
        request.setSessionId(sessionId.toString());

        Flux<org.springframework.http.codec.ServerSentEvent<String>> stream;
        MDC.put("traceId", "trace-before-async");
        try {
            stream = service(redisProps()).chatStream(kbId, request);
        } finally {
            MDC.remove("traceId");
        }
        var events = stream.collectList().block();

        @SuppressWarnings("unchecked")
        Map<String, Object> error = new ObjectMapper().readValue(events.get(events.size() - 1).data(), Map.class);
        assertThat(error).containsEntry("stage", "llm")
                .containsEntry("requestId", "trace-before-async");
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
    void cancelDuringRetrievalPreventsLaterLlmStreaming() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(chatSessionService.findOrThrow(kbId, sessionId)).thenReturn(ChatSession.builder()
                .id(sessionId)
                .kbId(kbId)
                .build());
        ChatRequest request = new ChatRequest();
        request.setQuery("question");
        request.setSessionId(sessionId.toString());
        CountDownLatch retrievalStarted = new CountDownLatch(1);
        CountDownLatch allowRetrievalToFinish = new CountDownLatch(1);
        ChatService service = service(redisProps());
        when(retrievalService.retrieve(eq(kbId), any())).thenAnswer(invocation -> {
            retrievalStarted.countDown();
            assertThat(allowRetrievalToFinish.await(Duration.ofMillis(500).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            return RetrieveResponse.builder().hits(List.of()).build();
        });
        when(retrievalService.buildContext(List.of())).thenReturn("");
        CompletableFuture<List<String>> events = CompletableFuture.supplyAsync(() -> service.chatStream(kbId, request)
                .map(event -> event.event())
                .collectList()
                .block());
        awaitCompletion(retrievalStarted);
        service.cancel(sessionId.toString());
        allowRetrievalToFinish.countDown();

        assertThat(events.get(1, TimeUnit.SECONDS)).containsExactly("retrieval", "done");
        verify(llmClient, never()).chatStream(anyString(), anyString());
        verify(chatSessionService).saveUserMessage(kbId, sessionId, "question");
        verify(chatSessionService, never()).saveAssistantMessage(any(), any(), anyString(), anyList());
        verify(redisTemplate).convertAndSend("cancel-channel", sessionId.toString());
        assertThat(cancelledSessions(service)).doesNotContain(sessionId.toString());
        assertThat(activeCancellationSignals(service)).doesNotContainKey(sessionId.toString());
    }

    @Test
    void overlappingSameSessionStreamCompletionDoesNotClearCancelledRetrievalStream() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(chatSessionService.findOrThrow(kbId, sessionId)).thenReturn(ChatSession.builder()
                .id(sessionId)
                .kbId(kbId)
                .build());
        ChatRequest request = new ChatRequest();
        request.setQuery("question");
        request.setSessionId(sessionId.toString());
        CountDownLatch secondRetrievalStarted = new CountDownLatch(1);
        CountDownLatch allowSecondRetrievalToFinish = new CountDownLatch(1);
        CountDownLatch firstTokenSinkReady = new CountDownLatch(1);
        AtomicReference<Sinks.Many<String>> firstTokens = new AtomicReference<>();
        ChatService service = service(redisProps());
        when(retrievalService.retrieve(eq(kbId), any()))
                .thenReturn(RetrieveResponse.builder().hits(List.of()).build())
                .thenAnswer(invocation -> {
                    secondRetrievalStarted.countDown();
                    assertThat(allowSecondRetrievalToFinish.await(Duration.ofMillis(500).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
                    return RetrieveResponse.builder().hits(List.of()).build();
                });
        when(retrievalService.buildContext(List.of())).thenReturn("");
        when(llmClient.chatStream(anyString(), anyString())).thenAnswer(invocation -> {
            Sinks.Many<String> tokens = Sinks.many().multicast().onBackpressureBuffer();
            firstTokens.set(tokens);
            firstTokenSinkReady.countDown();
            return tokens.asFlux();
        });

        CompletableFuture<List<String>> firstEvents = CompletableFuture.supplyAsync(() -> service.chatStream(kbId, request)
                .map(event -> event.event())
                .collectList()
                .block());
        awaitCompletion(firstTokenSinkReady);
        assertThat(firstTokens.get()).isNotNull();
        CompletableFuture<List<String>> secondEvents = CompletableFuture.supplyAsync(() -> service.chatStream(kbId, request)
                .map(event -> event.event())
                .collectList()
                .block());
        awaitCompletion(secondRetrievalStarted);

        service.cancel(sessionId.toString());
        assertThat(firstEvents.get(1, TimeUnit.SECONDS)).containsExactly("retrieval", "done");
        allowSecondRetrievalToFinish.countDown();

        assertThat(secondEvents.get(1, TimeUnit.SECONDS)).containsExactly("retrieval", "done");
        verify(llmClient, times(1)).chatStream(anyString(), anyString());
        verify(chatSessionService, times(2)).saveUserMessage(kbId, sessionId, "question");
        verify(chatSessionService, never()).saveAssistantMessage(any(), any(), anyString(), anyList());
        assertThat(cancelledSessions(service)).doesNotContain(sessionId.toString());
        assertThat(activeCancellationSignals(service)).doesNotContainKey(sessionId.toString());
    }

    @Test
    void chatStreamCleansCancellationStateWhenRetrievalThrowsAfterRegistration() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(chatSessionService.findOrThrow(kbId, sessionId)).thenReturn(ChatSession.builder()
                .id(sessionId)
                .kbId(kbId)
                .build());
        when(retrievalService.retrieve(eq(kbId), any())).thenThrow(new IllegalStateException("retrieval down"));
        ChatRequest request = new ChatRequest();
        request.setQuery("question");
        request.setSessionId(sessionId.toString());
        ChatService service = service(redisProps());

        var events = service.chatStream(kbId, request).collectList().block();

        assertThat(events).extracting(e -> e.event()).containsExactly("error");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = new ObjectMapper().readValue(events.get(0).data(), Map.class);
        assertThat(error).containsEntry("error", "chat_pipeline_error")
                .containsEntry("stage", "retrieval");
        assertThat(error.get("message")).asString().contains("retrieval down");

        verify(chatSessionService, never()).saveUserMessage(any(), any(), anyString());
        verify(llmClient, never()).chatStream(anyString(), anyString());
        assertThat(cancelledSessions(service)).doesNotContain(sessionId.toString());
        assertThat(activeCancellationSignals(service)).doesNotContainKey(sessionId.toString());
    }

    @Test
    void chatStreamRejectsInvalidNonblankSessionIdWithoutPersistingMessages() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
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
    void chatWrapsNonStreamingRetrievalFailureWithStageAndSuggestion() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(retrievalService.retrieve(eq(kbId), any())).thenThrow(new IllegalStateException("retrieval down"));
        ChatRequest request = new ChatRequest();
        request.setQuery("question");

        assertThatThrownBy(() -> service(redisProps()).chat(kbId, request))
                .isInstanceOf(ChatPipelineException.class)
                .hasMessage("retrieval down")
                .satisfies(ex -> {
                    ChatPipelineException pipelineException = (ChatPipelineException) ex;
                    assertThat(pipelineException.getStage()).isEqualTo("retrieval");
                    assertThat(pipelineException.getSuggestion()).contains("indexed documents");
                    assertThat(pipelineException.getCause()).isInstanceOf(IllegalStateException.class);
                });

        verify(llmClient, never()).chat(anyString(), anyString());
    }

    @Test
    void chatWrapsNonStreamingLlmFailureWithStageAndSuggestion() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().hits(List.of()).build());
        when(retrievalService.buildContext(List.of())).thenReturn("");
        when(llmClient.chat(anyString(), anyString())).thenThrow(new IllegalStateException("provider down"));
        ChatRequest request = new ChatRequest();
        request.setQuery("question");

        assertThatThrownBy(() -> service(redisProps()).chat(kbId, request))
                .isInstanceOf(ChatPipelineException.class)
                .hasMessage("provider down")
                .satisfies(ex -> {
                    ChatPipelineException pipelineException = (ChatPipelineException) ex;
                    assertThat(pipelineException.getStage()).isEqualTo("llm");
                    assertThat(pipelineException.getSuggestion()).contains("CHAT_API_KEY");
                    assertThat(pipelineException.getCause()).isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void cancelStoresSessionAndPublishesRedisMessage() {
        RedisQueueProperties props = redisProps();
        ChatService service = service(props);

        service.cancel("session-1");

        verify(redisTemplate).convertAndSend("cancel-channel", "session-1");
        assertThat(activeCancellationSignals(service)).doesNotContainKey("session-1");
        assertThat(cancelledSessions(service)).doesNotContain("session-1");
    }

    @Test
    void inactiveCancelDoesNotSuppressLaterStreamForSameSession() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(chatSessionService.findOrThrow(kbId, sessionId)).thenReturn(ChatSession.builder()
                .id(sessionId)
                .kbId(kbId)
                .build());
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().hits(List.of()).build());
        when(retrievalService.buildContext(List.of())).thenReturn("");
        when(llmClient.chatStream(anyString(), anyString())).thenReturn(Flux.just("par", "tial"));
        ChatRequest request = new ChatRequest();
        request.setQuery("question");
        request.setSessionId(sessionId.toString());
        ChatService service = service(redisProps());

        service.cancel(sessionId.toString());
        var events = service.chatStream(kbId, request).collectList().block();

        assertThat(events).extracting(e -> e.event()).containsExactly("retrieval", "token", "token", "done");
        verify(redisTemplate).convertAndSend("cancel-channel", sessionId.toString());
        verify(chatSessionService).saveUserMessage(kbId, sessionId, "question");
        verify(chatSessionService, times(1)).saveAssistantMessage(kbId, sessionId, "partial", List.of());
        assertThat(cancelledSessions(service)).doesNotContain(sessionId.toString());
    }

    @Test
    void cancelAfterOneSameSessionStreamFinishesStillTerminatesRemainingStream() {
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).topK(2).build());
        when(chatSessionService.findOrThrow(kbId, sessionId)).thenReturn(ChatSession.builder()
                .id(sessionId)
                .kbId(kbId)
                .build());
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().hits(List.of()).build());
        when(retrievalService.buildContext(List.of())).thenReturn("");
        Sinks.Many<String> firstTokens = Sinks.many().multicast().onBackpressureBuffer();
        Sinks.Many<String> secondTokens = Sinks.many().multicast().onBackpressureBuffer();
        when(llmClient.chatStream(anyString(), anyString()))
                .thenReturn(firstTokens.asFlux(), secondTokens.asFlux());
        ChatRequest request = new ChatRequest();
        request.setQuery("闂");
        request.setSessionId(sessionId.toString());
        ChatService service = service(redisProps());
        CountDownLatch firstCompleted = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        AtomicReference<List<String>> secondEvents = new AtomicReference<>();

        service.chatStream(kbId, request)
                .collectList()
                .doFinally(signal -> firstCompleted.countDown())
                .subscribe();
        service.chatStream(kbId, request)
                .map(event -> event.event())
                .collectList()
                .doOnNext(secondEvents::set)
                .doFinally(signal -> secondCompleted.countDown())
                .subscribe();
        firstTokens.tryEmitNext("閮?");
        secondTokens.tryEmitNext("鍓?");
        firstTokens.tryEmitComplete();
        awaitCompletion(firstCompleted);

        service.cancel(sessionId.toString());
        awaitCompletion(secondCompleted);

        assertThat(secondEvents.get()).containsExactly("retrieval", "token", "done");
        verify(chatSessionService, times(2)).saveUserMessage(kbId, sessionId, "闂");
        verify(chatSessionService, times(1)).saveAssistantMessage(kbId, sessionId, "閮?", List.of());
        verify(chatSessionService, times(1)).saveAssistantMessage(kbId, sessionId, "鍓?", List.of());
        verify(redisTemplate).convertAndSend("cancel-channel", sessionId.toString());
        assertThat(activeCancellationSignals(service)).doesNotContainKey(sessionId.toString());
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

    @SuppressWarnings("unchecked")
    private static Map<String, Collection<?>> activeCancellationSignals(ChatService service) {
        try {
            Field field = ChatService.class.getDeclaredField("cancellationSignals");
            field.setAccessible(true);
            return (Map<String, Collection<?>>) field.get(service);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> cancelledSessions(ChatService service) {
        try {
            Field field = ChatService.class.getDeclaredField("cancelledSessions");
            field.setAccessible(true);
            return (Set<String>) field.get(service);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
