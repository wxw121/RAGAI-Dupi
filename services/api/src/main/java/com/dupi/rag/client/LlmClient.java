package com.dupi.rag.client;

import com.dupi.rag.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LlmClient {

    private final LlmProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public List<Float> embed(String text, String modelOverride) {
        String model = modelOverride != null ? modelOverride : properties.getEmbedding().getModel();
        WebClient client = buildEmbeddingClient();

        JsonNode response = client.post()
                .uri("/embeddings")
                .body(BodyInserters.fromValue(Map.of(
                        "model", model,
                        "input", text
                )))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (response == null || !response.has("data")) {
            throw new IllegalStateException("Empty embedding response");
        }
        JsonNode embedding = response.get("data").get(0).get("embedding");
        return objectMapper.convertValue(
                embedding,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Float.class)
        );
    }

    public Flux<String> chatStream(String systemPrompt, String userPrompt) {
        WebClient client = buildChatClient();
        return client.post()
                .uri("/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(BodyInserters.fromValue(Map.of(
                        "model", properties.getChat().getModel(),
                        "stream", true,
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt)
                        )
                )))
                .retrieve()
                .bodyToFlux(String.class)
                .concatMap(chunk -> Flux.fromIterable(parseStreamTokens(chunk)));
    }

    private List<String> parseStreamTokens(String body) {
        List<String> tokens = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return tokens;
        }
        for (String line : body.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String data = trimmed.startsWith("data:") ? trimmed.substring(5).trim() : trimmed;
            if (data.isEmpty() || "[DONE]".equals(data)) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(data);
                JsonNode choices = node.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    JsonNode delta = choices.get(0).path("delta").path("content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        String token = delta.asText();
                        if (!token.isEmpty()) {
                            tokens.add(token);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return tokens;
    }

    public String chat(String systemPrompt, String userPrompt) {
        WebClient client = buildChatClient();
        JsonNode response = client.post()
                .uri("/chat/completions")
                .body(BodyInserters.fromValue(Map.of(
                        "model", properties.getChat().getModel(),
                        "stream", false,
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt)
                        )
                )))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (response == null) {
            throw new IllegalStateException("Empty chat response");
        }
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("Empty chat choices");
        }
        return choices.get(0).path("message").path("content").asText();
    }

    private WebClient buildChatClient() {
        return webClientBuilder
                .baseUrl(properties.getChat().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getChat().getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private WebClient buildEmbeddingClient() {
        return webClientBuilder
                .baseUrl(properties.getEmbedding().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getEmbedding().getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
