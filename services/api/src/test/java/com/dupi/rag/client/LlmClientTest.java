package com.dupi.rag.client;

import com.dupi.rag.config.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmClientTest {

    @Test
    void embedParsesEmbeddingVectorAndRejectsEmptyResponse() {
        LlmClient ok = client("""
                {"data":[{"embedding":[1.0,2.5,3.0]}]}
                """);

        assertThat(ok.embed("hello", "override-model")).containsExactly(1.0f, 2.5f, 3.0f);

        LlmClient empty = client("{}");
        assertThatThrownBy(() -> empty.embed("hello", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Empty embedding response");
    }

    @Test
    void chatParsesMessageContentAndRejectsEmptyChoicesOrNullResponse() {
        LlmClient ok = client("""
                {"choices":[{"message":{"content":"answer"}}]}
                """);
        LlmClient emptyChoices = client("""
                {"choices":[]}
                """);

        assertThat(ok.chat("system", "user")).isEqualTo("answer");
        assertThatThrownBy(() -> emptyChoices.chat("system", "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Empty chat choices");
    }

    @Test
    void chatStreamDelegatesToSseParser() {
        LlmClient client = client("""
                data: {"choices":[{"delta":{"content":"A"}}]}
                data: [DONE]
                """);

        assertThat(client.chatStream("system", "user").collectList().block()).containsExactly("A");
    }

    @Test
    void chatStreamParsesOnlyValidSseTokenLines() {
        LlmClient client = client();

        assertThat(parse(client, """
            data: {"choices":[{"delta":{"content":"你"}}]}
            ignored
            data: malformed
            data: {"choices":[{"delta":{"content":""}}]}
            data: {"choices":[{"delta":{"content":"好"}}]}
            data: [DONE]
            """)).containsExactly("你", "好");

        assertThat(parse(client, null)).isEmpty();
        assertThat(parse(client, "  ")).isEmpty();
    }

    @Test
    void chatStreamParsesDecodedJsonEventChunks() {
        LlmClient client = client();

        assertThat(parse(client, """
            {"choices":[{"delta":{"content":"A"}}]}
            [DONE]
            {"choices":[{"delta":{"content":"B"}}]}
            """)).containsExactly("A", "B");
    }

    @SuppressWarnings("unchecked")
    private static List<String> parse(LlmClient client, String body) {
        try {
            Method method = LlmClient.class.getDeclaredMethod("parseStreamTokens", String.class);
            method.setAccessible(true);
            return (List<String>) method.invoke(client, body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static LlmClient client() {
        LlmProperties props = new LlmProperties();
        props.getChat().setBaseUrl("http://chat.test");
        props.getChat().setApiKey("chat-key");
        props.getChat().setModel("chat-model");
        props.getEmbedding().setBaseUrl("http://embed.test");
        props.getEmbedding().setApiKey("embed-key");
        props.getEmbedding().setModel("embed-model");
        props.getEmbedding().setDimension(3);
        return new LlmClient(props, WebClient.builder(), new ObjectMapper());
    }

    private static LlmClient client(String responseBody) {
        LlmProperties props = new LlmProperties();
        props.getChat().setBaseUrl("http://chat.test");
        props.getChat().setApiKey("chat-key");
        props.getChat().setModel("chat-model");
        props.getEmbedding().setBaseUrl("http://embed.test");
        props.getEmbedding().setApiKey("embed-key");
        props.getEmbedding().setModel("embed-model");
        props.getEmbedding().setDimension(3);
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", request.headers().getAccept().toString().contains("text/event-stream")
                        ? "text/event-stream" : "application/json")
                .body(responseBody)
                .build());
        return new LlmClient(props, WebClient.builder().exchangeFunction(exchange), new ObjectMapper());
    }
}
