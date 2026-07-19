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
    private double combinedChildWeight = 1.0;
    private double combinedQaWeight = 0.8;
    private int rrfK = 60;
}
