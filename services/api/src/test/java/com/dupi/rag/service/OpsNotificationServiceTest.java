package com.dupi.rag.service;

import com.dupi.rag.config.AuditProperties;
import com.dupi.rag.dto.AuditAlertResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class OpsNotificationServiceTest {

    @Test
    void notifyAlertsReturnsUnconfiguredWhenWebhookUrlIsBlank() {
        AuditProperties properties = new AuditProperties();

        var response = new OpsNotificationService(properties, WebClient.builder())
                .notifyAlerts(List.of(alert()));

        assertThat(response.isConfigured()).isFalse();
        assertThat(response.isDelivered()).isFalse();
        assertThat(response.getAlertCount()).isEqualTo(1);
    }

    @Test
    void notifyAlertsPostsCurrentAlertsToWebhook() {
        AuditProperties properties = new AuditProperties();
        properties.setAlertWebhookUrl("https://ops.example.test/hook");
        AtomicReference<String> url = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            url.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.ACCEPTED).build());
        };

        var response = new OpsNotificationService(properties, WebClient.builder().exchangeFunction(exchange))
                .notifyAlerts(List.of(alert()));

        assertThat(url.get()).isEqualTo("https://ops.example.test/hook");
        assertThat(response.isConfigured()).isTrue();
        assertThat(response.isDelivered()).isTrue();
        assertThat(response.getStatusCode()).isEqualTo(202);
        assertThat(response.getAlertCount()).isEqualTo(1);
    }

    @Test
    void notifyAlertsStopsWaitingAfterConfiguredTimeout() {
        AuditProperties properties = new AuditProperties();
        properties.setAlertWebhookUrl("https://ops.example.test/slow");
        properties.setAlertWebhookTimeoutSeconds(1);
        ExchangeFunction exchange = request -> Mono.never();
        OpsNotificationService service = new OpsNotificationService(
                properties,
                WebClient.builder().exchangeFunction(exchange)
        );

        var response = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> service.notifyAlerts(List.of(alert()))
        );

        assertThat(response.isConfigured()).isTrue();
        assertThat(response.isDelivered()).isFalse();
        assertThat(response.getMessage()).containsIgnoringCase("timeout");
    }

    private static AuditAlertResponse alert() {
        return AuditAlertResponse.builder()
                .code("INGEST_FAILURES_OPEN")
                .severity("WARN")
                .message("Open ingest failures")
                .count(2)
                .threshold(0)
                .build();
    }
}
