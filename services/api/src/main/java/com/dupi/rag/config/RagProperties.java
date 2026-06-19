package com.dupi.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "dupi.rag")
@Getter
@Setter
public class RagProperties {
    private int defaultTopK;
    private int maxContextChars;
}
