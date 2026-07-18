package com.dupi.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "dupi.ingest.notifications")
public class IngestNotificationProperties {
    private String webhookUrl;
    private String webhookSecret;
    private int timeoutSeconds = 10;
    private int maxAttempts = 5;
    private int maxErrorMessageLength = 512;
    private boolean allowInsecureWebhook = false;
}
