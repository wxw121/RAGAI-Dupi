package com.dupi.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "dupi.redis")
@Getter
@Setter
public class RedisQueueProperties {
    private String ingestQueue;
    private String cancelChannel;
}
