package com.dupi.rag.service;

import com.dupi.rag.config.IngestNotificationProperties;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestFailureNotification;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestFailureNotificationStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.repository.IngestFailureNotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestFailureNotificationServiceTest {

    @Mock IngestFailureNotificationRepository repository;
    @Mock KnowledgeBaseService knowledgeBaseService;

    @Test
    void recordTerminalFailureCreatesSingleDedupeEvent() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId, executionId, IngestJobStatus.FAILED);
        Document doc = doc(kbId, docId);
        when(repository.existsByEventKey(jobId + ":" + executionId + ":FAILED"))
                .thenReturn(false)
                .thenReturn(true);
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(KnowledgeBase.builder()
                .id(kbId)
                .tenantId("tenant-a")
                .build());

        IngestFailureNotificationService service = service();
        service.recordTerminalFailure(job, doc);
        service.recordTerminalFailure(job, doc);

        ArgumentCaptor<IngestFailureNotification> captor = ArgumentCaptor.forClass(IngestFailureNotification.class);
        verify(repository, times(1)).save(captor.capture());
        IngestFailureNotification notification = captor.getValue();
        assertThat(notification.getEventKey()).isEqualTo(jobId + ":" + executionId + ":FAILED");
        assertThat(notification.getTenantId()).isEqualTo("tenant-a");
        assertThat(notification.getKbId()).isEqualTo(kbId);
        assertThat(notification.getDocId()).isEqualTo(docId);
        assertThat(notification.getStatus()).isEqualTo("FAILED");
        assertThat(notification.getStage()).isEqualTo("FAILED");
        assertThat(notification.getErrorMessage()).isEqualTo("bad pdf");
    }

    @Test
    void recordTerminalFailureSkipsCancelledAndNonFailureStates() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        service().recordTerminalFailure(job(kbId, docId, UUID.randomUUID(), UUID.randomUUID(), IngestJobStatus.CANCELLED), doc(kbId, docId));
        service().recordTerminalFailure(job(kbId, docId, UUID.randomUUID(), UUID.randomUUID(), IngestJobStatus.COMPLETED), doc(kbId, docId));

        verify(repository, never()).save(any());
        verifyNoInteractions(knowledgeBaseService);
    }

    @Test
    void recordTerminalFailureSkipsNullInputsAndJobsWithoutExecutionId() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        IngestFailureNotificationService service = service();
        service.recordTerminalFailure(null, doc(kbId, docId));
        service.recordTerminalFailure(job(kbId, docId, UUID.randomUUID(), UUID.randomUUID(), IngestJobStatus.FAILED), null);
        service.recordTerminalFailure(job(kbId, docId, UUID.randomUUID(), null, IngestJobStatus.FAILED), doc(kbId, docId));

        verifyNoInteractions(repository, knowledgeBaseService);
    }

    @Test
    void dispatchPendingDeliversWebhookAndMarksEventDelivered() {
        IngestFailureNotification event = notification(0);
        when(repository.findTop50ByDeliveryStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(List.of(
                        IngestFailureNotificationStatus.PENDING,
                        IngestFailureNotificationStatus.FAILED,
                        IngestFailureNotificationStatus.IN_PROGRESS)),
                any(Instant.class)
        )).thenReturn(List.of(event));
        AtomicReference<String> deliveredUrl = new AtomicReference<>();
        AtomicReference<IngestFailureNotificationStatus> statusAtDelivery = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            deliveredUrl.set(request.url().toString());
            statusAtDelivery.set(event.getDeliveryStatus());
            return Mono.just(ClientResponse.create(HttpStatus.ACCEPTED).build());
        };

        int delivered = service(
                properties("https://notify.example.test/ingest"),
                WebClient.builder().exchangeFunction(exchange)
        ).dispatchPending();

        assertThat(delivered).isEqualTo(1);
        assertThat(deliveredUrl.get()).isEqualTo("https://notify.example.test/ingest");
        assertThat(statusAtDelivery.get()).isEqualTo(IngestFailureNotificationStatus.IN_PROGRESS);
        assertThat(event.getDeliveryStatus()).isEqualTo(IngestFailureNotificationStatus.DELIVERED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).isNull();
        verify(repository, times(2)).save(event);
    }

    @Test
    void dispatchPendingOnScheduleClaimsAndDeliversDueWebhook() {
        IngestFailureNotification event = notification(0);
        when(repository.findTop50ByDeliveryStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(event));
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.ACCEPTED).build());

        service(properties("https://notify.example.test/ingest"), WebClient.builder().exchangeFunction(exchange))
                .dispatchPendingOnSchedule();

        assertThat(event.getDeliveryStatus()).isEqualTo(IngestFailureNotificationStatus.DELIVERED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        verify(repository, times(2)).save(event);
    }

    @Test
    void dispatchPendingPersistsBackoffAndExhaustsBoundedRetries() {
        IngestFailureNotification retryable = notification(1);
        IngestFailureNotification exhausted = notification(2);
        when(repository.findTop50ByDeliveryStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(List.of(
                        IngestFailureNotificationStatus.PENDING,
                        IngestFailureNotificationStatus.FAILED,
                        IngestFailureNotificationStatus.IN_PROGRESS)),
                any(Instant.class)
        )).thenReturn(List.of(retryable, exhausted));
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
        IngestNotificationProperties properties = properties("https://notify.example.test/ingest");
        properties.setMaxAttempts(3);
        Instant before = Instant.now();

        int delivered = service(properties, WebClient.builder().exchangeFunction(exchange)).dispatchPending();

        assertThat(delivered).isZero();
        assertThat(retryable.getDeliveryStatus()).isEqualTo(IngestFailureNotificationStatus.FAILED);
        assertThat(retryable.getAttemptCount()).isEqualTo(2);
        assertThat(retryable.getNextAttemptAt()).isAfter(before);
        assertThat(retryable.getLastError()).isNotBlank();
        assertThat(exhausted.getDeliveryStatus()).isEqualTo(IngestFailureNotificationStatus.EXHAUSTED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(3);
        verify(repository, times(2)).save(retryable);
        verify(repository, times(2)).save(exhausted);
    }

    @Test
    void dispatchPendingRejectsUnsafeWebhookUrlByDefault() {
        assertThat(service(properties("http://127.0.0.1/internal"), WebClient.builder()).dispatchPending()).isZero();
        verify(repository, never()).findTop50ByDeliveryStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void dispatchPendingAllowsInsecureWebhookOnlyWhenExplicitlyEnabled() {
        IngestFailureNotification event = notification(0);
        when(repository.findTop50ByDeliveryStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(event));
        IngestNotificationProperties properties = properties("http://127.0.0.1/internal");
        properties.setAllowInsecureWebhook(true);
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.ACCEPTED).build());

        assertThat(service(properties, WebClient.builder().exchangeFunction(exchange)).dispatchPending()).isEqualTo(1);

        assertThat(event.getDeliveryStatus()).isEqualTo(IngestFailureNotificationStatus.DELIVERED);
    }

    @Test
    void dispatchPendingRejectsMalformedWebhookUrl() {
        assertThat(service(properties("https://[bad"), WebClient.builder()).dispatchPending()).isZero();

        verify(repository, never()).findTop50ByDeliveryStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void dispatchPendingAddsSecretHeaderWhenConfigured() {
        IngestFailureNotification event = notification(0);
        when(repository.findTop50ByDeliveryStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(event));
        AtomicReference<String> secret = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            secret.set(request.headers().getFirst("X-Dupi-Webhook-Secret"));
            return Mono.just(ClientResponse.create(HttpStatus.ACCEPTED).build());
        };
        IngestNotificationProperties properties = properties("https://notify.example.test/ingest");
        properties.setWebhookSecret("secret-token");

        assertThat(service(properties, WebClient.builder().exchangeFunction(exchange)).dispatchPending()).isEqualTo(1);

        assertThat(secret.get()).isEqualTo("secret-token");
    }

    @Test
    void recordTerminalFailureTruncatesLongErrorMessageBeforeExternalDelivery() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, UUID.randomUUID(), UUID.randomUUID(), IngestJobStatus.FAILED);
        job.setErrorMessage("secret-token-" + "x".repeat(700));
        when(repository.existsByEventKey(job.getId() + ":" + job.getExecutionId() + ":FAILED"))
                .thenReturn(false);
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(KnowledgeBase.builder()
                .id(kbId)
                .tenantId("tenant-a")
                .build());

        service().recordTerminalFailure(job, doc(kbId, docId));

        ArgumentCaptor<IngestFailureNotification> captor = ArgumentCaptor.forClass(IngestFailureNotification.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getErrorMessage()).hasSizeLessThanOrEqualTo(512);
        assertThat(captor.getValue().getErrorMessage()).endsWith("…");
    }

    @Test
    void dispatchPendingSkipsRepositoryWhenWebhookIsNotConfigured() {
        assertThat(service(properties(" "), WebClient.builder()).dispatchPending()).isZero();
        verify(repository, never()).findTop50ByDeliveryStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(any(), any());
    }

    private IngestFailureNotificationService service() {
        return service(properties(" "), WebClient.builder());
    }

    private IngestFailureNotificationService service(
            IngestNotificationProperties properties,
            WebClient.Builder webClientBuilder
    ) {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new IngestFailureNotificationService(
                repository,
                knowledgeBaseService,
                properties,
                webClientBuilder,
                transactionManager);
    }

    private static IngestNotificationProperties properties(String webhookUrl) {
        IngestNotificationProperties properties = new IngestNotificationProperties();
        properties.setWebhookUrl(webhookUrl);
        properties.setTimeoutSeconds(1);
        properties.setMaxAttempts(3);
        return properties;
    }

    private static IngestJob job(UUID kbId, UUID docId, UUID jobId, UUID executionId, IngestJobStatus status) {
        return IngestJob.builder()
                .id(jobId)
                .kbId(kbId)
                .docId(docId)
                .executionId(executionId)
                .status(status)
                .stage(status == IngestJobStatus.DEAD_LETTER ? IngestStage.DEAD_LETTER : IngestStage.FAILED)
                .errorMessage("bad pdf")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private static Document doc(UUID kbId, UUID docId) {
        return Document.builder()
                .id(docId)
                .kbId(kbId)
                .fileName("a.md")
                .objectKey("kb/a.md")
                .mimeType("text/markdown")
                .fileSize(1L)
                .status(DocumentStatus.FAILED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private static IngestFailureNotification notification(int attemptCount) {
        return IngestFailureNotification.builder()
                .id(UUID.randomUUID())
                .eventKey(UUID.randomUUID() + ":FAILED")
                .tenantId("tenant-a")
                .kbId(UUID.randomUUID())
                .docId(UUID.randomUUID())
                .jobId(UUID.randomUUID())
                .executionId(UUID.randomUUID())
                .status("FAILED")
                .stage("FAILED")
                .errorMessage("bad pdf")
                .deliveryStatus(attemptCount == 0
                        ? IngestFailureNotificationStatus.PENDING
                        : IngestFailureNotificationStatus.FAILED)
                .attemptCount(attemptCount)
                .nextAttemptAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
