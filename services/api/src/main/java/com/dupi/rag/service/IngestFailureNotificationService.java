package com.dupi.rag.service;

import com.dupi.rag.config.IngestNotificationProperties;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestFailureNotification;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.IngestFailureNotificationStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.repository.IngestFailureNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class IngestFailureNotificationService {

    private final IngestFailureNotificationRepository repository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final IngestNotificationProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final PlatformTransactionManager transactionManager;

    @Transactional
    public void recordTerminalFailure(IngestJob job, Document doc) {
        if (job == null || doc == null) {
            return;
        }
        if (job.getStatus() != IngestJobStatus.FAILED && job.getStatus() != IngestJobStatus.DEAD_LETTER) {
            return;
        }
        if (job.getExecutionId() == null) {
            log.warn("Skipping ingest failure notification for job {} without executionId", job.getId());
            return;
        }
        String eventKey = job.getId() + ":" + job.getExecutionId() + ":" + job.getStatus();
        if (repository.existsByEventKey(eventKey)) {
            return;
        }
        KnowledgeBase kb = knowledgeBaseService.findSystemOrThrow(job.getKbId());
        repository.save(IngestFailureNotification.builder()
                .eventKey(eventKey)
                .tenantId(kb.getTenantId())
                .kbId(job.getKbId())
                .docId(job.getDocId())
                .jobId(job.getId())
                .executionId(job.getExecutionId())
                .status(job.getStatus().name())
                .stage(job.getStage() == null ? null : job.getStage().name())
                .errorMessage(sanitizeError(job.getErrorMessage()))
                .build());
    }

    @Scheduled(cron = "${dupi.ingest.notifications.dispatch-cron:*/30 * * * * *}")
    public void dispatchPendingOnSchedule() {
        int delivered = dispatchPending();
        if (delivered > 0) {
            log.info("Delivered {} ingest failure notification(s)", delivered);
        }
    }

    public int dispatchPending() {
        String webhookUrl = properties.getWebhookUrl();
        if (!isUsableWebhookUrl(webhookUrl)) {
            return 0;
        }

        Instant now = Instant.now();
        List<IngestFailureNotification> events = claimDueEvents(now);
        int delivered = 0;
        for (IngestFailureNotification event : events) {
            try {
                var response = webClientBuilder.build()
                        .post()
                        .uri(webhookUrl)
                        .headers(headers -> {
                            String secret = properties.getWebhookSecret();
                            if (secret != null && !secret.isBlank()) {
                                headers.set("X-Dupi-Webhook-Secret", secret);
                            }
                        })
                        .bodyValue(payload(event))
                        .retrieve()
                        .toBodilessEntity()
                        .block(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())));
                if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException("Webhook returned no successful response");
                }
                recordDeliverySuccess(event, now);
                delivered++;
            } catch (Exception error) {
                recordDeliveryFailure(event, now, error);
            }
        }
        return delivered;
    }

    private List<IngestFailureNotification> claimDueEvents(Instant now) {
        return transactionTemplate().execute(status -> {
            List<IngestFailureNotification> events = repository
                    .findTop50ByDeliveryStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                            List.of(
                                    IngestFailureNotificationStatus.PENDING,
                                    IngestFailureNotificationStatus.FAILED,
                                    IngestFailureNotificationStatus.IN_PROGRESS),
                            now
                    );
            for (IngestFailureNotification event : events) {
                int attempts = event.getAttemptCount() == null ? 1 : event.getAttemptCount() + 1;
                event.setAttemptCount(attempts);
                event.setDeliveryStatus(IngestFailureNotificationStatus.IN_PROGRESS);
                event.setLastError(null);
                event.setNextAttemptAt(now.plus(claimTimeout()));
                repository.save(event);
            }
            return events;
        });
    }

    private void recordDeliverySuccess(IngestFailureNotification event, Instant now) {
        transactionTemplate().executeWithoutResult(status -> {
            event.setDeliveryStatus(IngestFailureNotificationStatus.DELIVERED);
            event.setLastError(null);
            event.setNextAttemptAt(now);
            repository.save(event);
        });
    }

    private void recordDeliveryFailure(IngestFailureNotification event, Instant now, Exception error) {
        transactionTemplate().executeWithoutResult(status -> {
            int attempts = event.getAttemptCount() == null ? 1 : event.getAttemptCount();
            event.setLastError(errorReason(error));
            if (attempts >= Math.max(1, properties.getMaxAttempts())) {
                event.setDeliveryStatus(IngestFailureNotificationStatus.EXHAUSTED);
                event.setNextAttemptAt(now);
            } else {
                event.setDeliveryStatus(IngestFailureNotificationStatus.FAILED);
                event.setNextAttemptAt(now.plus(backoff(attempts)));
            }
            repository.save(event);
        });
    }

    private Map<String, Object> payload(IngestFailureNotification event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.getId());
        payload.put("eventKey", event.getEventKey());
        payload.put("tenantId", event.getTenantId());
        payload.put("kbId", event.getKbId());
        payload.put("docId", event.getDocId());
        payload.put("jobId", event.getJobId());
        payload.put("executionId", event.getExecutionId());
        payload.put("status", event.getStatus());
        payload.put("stage", event.getStage());
        payload.put("error", sanitizeError(event.getErrorMessage()));
        return payload;
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private Duration claimTimeout() {
        return Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds()) + 5L);
    }

    private boolean isUsableWebhookUrl(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(webhookUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isBlank()) {
                return false;
            }
            if (!"https".equalsIgnoreCase(scheme) && !properties.isAllowInsecureWebhook()) {
                return false;
            }
            return properties.isAllowInsecureWebhook() || !isLocalOrMetadataHost(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isLocalOrMetadataHost(String host) {
        String normalized = host.toLowerCase();
        return normalized.equals("localhost")
                || normalized.equals("127.0.0.1")
                || normalized.equals("0.0.0.0")
                || normalized.equals("::1")
                || normalized.startsWith("127.")
                || normalized.startsWith("169.254.");
    }

    private Duration backoff(int attempts) {
        int exponent = Math.min(9, Math.max(0, attempts - 1));
        return Duration.ofSeconds(Math.min(3600, 10L * (1L << exponent)));
    }

    private String errorReason(Exception error) {
        String reason = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
        return sanitizeError(reason);
    }

    private String sanitizeError(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int maxLength = Math.max(64, properties.getMaxErrorMessageLength());
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 1) + "…";
    }
}
