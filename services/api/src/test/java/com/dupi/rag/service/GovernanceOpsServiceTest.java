package com.dupi.rag.service;

import com.dupi.rag.domain.enums.IngestFailureNotificationStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import com.dupi.rag.domain.enums.VectorCleanupStatus;
import com.dupi.rag.dto.AuditAlertResponse;
import com.dupi.rag.repository.IngestFailureNotificationRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import com.dupi.rag.repository.UploadQuotaReservationRepository;
import com.dupi.rag.repository.VectorCleanupTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovernanceOpsServiceTest {

    @Mock UploadQuotaReservationRepository uploadQuotaReservationRepository;
    @Mock IngestJobRepository ingestJobRepository;
    @Mock IngestOutboxEventRepository ingestOutboxEventRepository;
    @Mock IngestFailureNotificationRepository notificationRepository;
    @Mock VectorCleanupTaskRepository vectorCleanupTaskRepository;
    @Mock AuditLogService auditLogService;
    @Mock IngestJobService ingestJobService;
    @Mock VectorCleanupTaskService vectorCleanupTaskService;

    @Test
    void summarizeAggregatesGovernanceCountsAndAlerts() {
        Instant now = Instant.parse("2026-07-18T10:15:30Z");
        when(uploadQuotaReservationRepository.countByStatus(UploadQuotaReservationStatus.PENDING)).thenReturn(2L);
        when(uploadQuotaReservationRepository.countByStatus(UploadQuotaReservationStatus.COMMITTED)).thenReturn(5L);
        when(uploadQuotaReservationRepository.countByStatus(UploadQuotaReservationStatus.RELEASED)).thenReturn(1L);
        when(uploadQuotaReservationRepository.sumReservedBytesByStatus(UploadQuotaReservationStatus.PENDING)).thenReturn(400L);
        when(uploadQuotaReservationRepository.sumReservedBytesByStatus(UploadQuotaReservationStatus.COMMITTED)).thenReturn(1_000L);
        when(uploadQuotaReservationRepository.countStalePendingAttempts(now)).thenReturn(1L);

        when(ingestJobRepository.countByStatus(IngestJobStatus.PENDING)).thenReturn(3L);
        when(ingestJobRepository.countByStatus(IngestJobStatus.PROCESSING)).thenReturn(4L);
        when(ingestJobRepository.countByStatus(IngestJobStatus.CANCEL_REQUESTED)).thenReturn(1L);
        when(ingestJobRepository.countByStatus(IngestJobStatus.FAILED)).thenReturn(2L);
        when(ingestJobRepository.countByStatus(IngestJobStatus.DEAD_LETTER)).thenReturn(1L);
        when(ingestJobRepository.countExpiredProcessingLeases(now)).thenReturn(2L);

        when(ingestOutboxEventRepository.countByStatus(IngestOutboxStatus.PENDING)).thenReturn(6L);
        when(ingestOutboxEventRepository.countByStatus(IngestOutboxStatus.FAILED)).thenReturn(2L);
        when(ingestOutboxEventRepository.countByStatus(IngestOutboxStatus.SENT)).thenReturn(10L);
        when(ingestOutboxEventRepository.countByStatus(IngestOutboxStatus.CANCELLED)).thenReturn(1L);

        when(notificationRepository.countByDeliveryStatus(IngestFailureNotificationStatus.PENDING)).thenReturn(7L);
        when(notificationRepository.countByDeliveryStatus(IngestFailureNotificationStatus.IN_PROGRESS)).thenReturn(1L);
        when(notificationRepository.countByDeliveryStatus(IngestFailureNotificationStatus.FAILED)).thenReturn(3L);
        when(notificationRepository.countByDeliveryStatus(IngestFailureNotificationStatus.EXHAUSTED)).thenReturn(2L);
        when(notificationRepository.countByDeliveryStatus(IngestFailureNotificationStatus.DELIVERED)).thenReturn(11L);

        when(vectorCleanupTaskRepository.countByStatusIn(List.of(VectorCleanupStatus.PENDING))).thenReturn(8L);
        when(vectorCleanupTaskRepository.countByStatusIn(List.of(VectorCleanupStatus.FAILED))).thenReturn(4L);

        when(auditLogService.summarizeAlerts()).thenReturn(List.of(alert("AUDIT_FAILED_SPIKE")));
        when(ingestJobService.summarizeAlerts()).thenReturn(List.of(alert("INGEST_FAILURES_OPEN")));
        when(vectorCleanupTaskService.summarizeAlerts()).thenReturn(List.of(alert("VECTOR_CLEANUP_FAILURES_OPEN")));

        var summary = service(now).summarize();

        assertThat(summary.getGeneratedAt()).isEqualTo(now);
        assertThat(summary.getUploadQuota().getPendingReservations()).isEqualTo(2L);
        assertThat(summary.getUploadQuota().getCommittedReservations()).isEqualTo(5L);
        assertThat(summary.getUploadQuota().getReleasedReservations()).isEqualTo(1L);
        assertThat(summary.getUploadQuota().getPendingReservedBytes()).isEqualTo(400L);
        assertThat(summary.getUploadQuota().getCommittedReservedBytes()).isEqualTo(1_000L);
        assertThat(summary.getUploadQuota().getActiveReservedBytes()).isEqualTo(1_400L);
        assertThat(summary.getUploadQuota().getStalePendingAttempts()).isEqualTo(1L);

        assertThat(summary.getIngestJobs().getPendingJobs()).isEqualTo(3L);
        assertThat(summary.getIngestJobs().getProcessingJobs()).isEqualTo(4L);
        assertThat(summary.getIngestJobs().getCancelRequestedJobs()).isEqualTo(1L);
        assertThat(summary.getIngestJobs().getFailedJobs()).isEqualTo(2L);
        assertThat(summary.getIngestJobs().getDeadLetterJobs()).isEqualTo(1L);
        assertThat(summary.getIngestJobs().getExpiredProcessingLeases()).isEqualTo(2L);

        assertThat(summary.getIngestOutbox().getPendingEvents()).isEqualTo(6L);
        assertThat(summary.getIngestOutbox().getFailedEvents()).isEqualTo(2L);
        assertThat(summary.getIngestOutbox().getSentEvents()).isEqualTo(10L);
        assertThat(summary.getIngestOutbox().getCancelledEvents()).isEqualTo(1L);

        assertThat(summary.getFailureNotifications().getPending()).isEqualTo(7L);
        assertThat(summary.getFailureNotifications().getInProgress()).isEqualTo(1L);
        assertThat(summary.getFailureNotifications().getFailed()).isEqualTo(3L);
        assertThat(summary.getFailureNotifications().getExhausted()).isEqualTo(2L);
        assertThat(summary.getFailureNotifications().getDelivered()).isEqualTo(11L);

        assertThat(summary.getVectorCleanup().getPendingTasks()).isEqualTo(8L);
        assertThat(summary.getVectorCleanup().getFailedTasks()).isEqualTo(4L);
        assertThat(summary.getVectorCleanup().getOpenTasks()).isEqualTo(12L);

        assertThat(summary.getAlerts())
                .extracting(AuditAlertResponse::getCode)
                .containsExactly(
                        "AUDIT_FAILED_SPIKE",
                        "INGEST_FAILURES_OPEN",
                        "VECTOR_CLEANUP_FAILURES_OPEN",
                        "UPLOAD_STALE_PENDING_ATTEMPTS",
                        "INGEST_EXPIRED_PROCESSING_LEASES",
                        "INGEST_OUTBOX_FAILURES_OPEN",
                        "INGEST_FAILURE_NOTIFICATIONS_EXHAUSTED"
                );
    }

    private GovernanceOpsService service(Instant now) {
        return new GovernanceOpsService(
                uploadQuotaReservationRepository,
                ingestJobRepository,
                ingestOutboxEventRepository,
                notificationRepository,
                vectorCleanupTaskRepository,
                auditLogService,
                ingestJobService,
                vectorCleanupTaskService,
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    private static AuditAlertResponse alert(String code) {
        return AuditAlertResponse.builder()
                .code(code)
                .severity("WARN")
                .message(code)
                .count(1L)
                .threshold(0L)
                .build();
    }
}
