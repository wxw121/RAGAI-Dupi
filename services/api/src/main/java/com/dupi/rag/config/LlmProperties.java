package com.dupi.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "dupi.llm")
@Getter
@Setter
public class LlmProperties {

    private ChatConfig chat = new ChatConfig();
    private EmbeddingConfig embedding = new EmbeddingConfig();

    @Getter
    @Setter
    public static class ChatConfig {
        private String baseUrl;
        private String apiKey;
        private String model;
    }

    @Getter
    @Setter
    public static class EmbeddingConfig {
        private String baseUrl;
        private String apiKey;
        private String model;
        private int dimension;
    }
}
