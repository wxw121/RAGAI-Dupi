package com.dupi.rag.service;

import com.dupi.rag.config.AuditProperties;
import com.dupi.rag.dto.AuditAlertResponse;
import com.dupi.rag.dto.OpsNotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OpsNotificationService {

    private final AuditProperties auditProperties;
    private final WebClient.Builder webClientBuilder;

    public OpsNotificationResponse notifyAlerts(List<AuditAlertResponse> alerts) {
        String webhookUrl = auditProperties.getAlertWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return OpsNotificationResponse.builder()
                    .configured(false)
                    .delivered(false)
                    .alertCount(alerts.size())
                    .message("alert webhook is not configured")
                    .build();
        }
        try {
            var response = webClientBuilder.build()
                    .post()
                    .uri(webhookUrl)
                    .bodyValue(Map.of("alerts", alerts))
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(Math.max(1, auditProperties.getAlertWebhookTimeoutSeconds())));
            int status = response == null ? 0 : response.getStatusCode().value();
            return OpsNotificationResponse.builder()
                    .configured(true)
                    .delivered(status >= 200 && status < 300)
                    .alertCount(alerts.size())
                    .statusCode(status)
                    .message("webhook delivered")
                    .build();
        } catch (Exception ex) {
            return OpsNotificationResponse.builder()
                    .configured(true)
                    .delivered(false)
                    .alertCount(alerts.size())
                    .message(ex.getMessage())
                    .build();
        }
    }
}
