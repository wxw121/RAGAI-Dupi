package com.dupi.rag.service;

import com.dupi.rag.domain.enums.IngestFailureNotificationStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import com.dupi.rag.domain.enums.VectorCleanupStatus;
import com.dupi.rag.dto.AuditAlertResponse;
import com.dupi.rag.dto.GovernanceSummaryResponse;
import com.dupi.rag.repository.IngestFailureNotificationRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.UploadQuotaReservationRepository;
import com.dupi.rag.repository.VectorCleanupTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GovernanceOpsService {
    private static final long ZERO_THRESHOLD = 0L;

    private final UploadQuotaReservationRepository uploadQuotaReservationRepository;
    private final IngestJobRepository ingestJobRepository;
    private final IngestOutboxEventRepository ingestOutboxEventRepository;
    private final IngestFailureNotificationRepository notificationRepository;
    private final VectorCleanupTaskRepository vectorCleanupTaskRepository;
    private final OperationJobRepository operationJobRepository;
    private final AuditLogService auditLogService;
    private final IngestJobService ingestJobService;
    private final VectorCleanupTaskService vectorCleanupTaskService;
    private final Clock clock;

    @Autowired
    public GovernanceOpsService(
            UploadQuotaReservationRepository uploadQuotaReservationRepository,
            IngestJobRepository ingestJobRepository,
            IngestOutboxEventRepository ingestOutboxEventRepository,
            IngestFailureNotificationRepository notificationRepository,
            VectorCleanupTaskRepository vectorCleanupTaskRepository,
            OperationJobRepository operationJobRepository,
            AuditLogService auditLogService,
            IngestJobService ingestJobService,
            VectorCleanupTaskService vectorCleanupTaskService
    ) {
        this(
                uploadQuotaReservationRepository,
                ingestJobRepository,
                ingestOutboxEventRepository,
                notificationRepository,
                vectorCleanupTaskRepository,
                operationJobRepository,
                auditLogService,
                ingestJobService,
                vectorCleanupTaskService,
                Clock.systemUTC()
        );
    }

    GovernanceOpsService(
            UploadQuotaReservationRepository uploadQuotaReservationRepository,
            IngestJobRepository ingestJobRepository,
            IngestOutboxEventRepository ingestOutboxEventRepository,
            IngestFailureNotificationRepository notificationRepository,
            VectorCleanupTaskRepository vectorCleanupTaskRepository,
            OperationJobRepository operationJobRepository,
            AuditLogService auditLogService,
            IngestJobService ingestJobService,
            VectorCleanupTaskService vectorCleanupTaskService,
            Clock clock
    ) {
        this.uploadQuotaReservationRepository = uploadQuotaReservationRepository;
        this.ingestJobRepository = ingestJobRepository;
        this.ingestOutboxEventRepository = ingestOutboxEventRepository;
        this.notificationRepository = notificationRepository;
        this.vectorCleanupTaskRepository = vectorCleanupTaskRepository;
        this.operationJobRepository = operationJobRepository;
        this.auditLogService = auditLogService;
        this.ingestJobService = ingestJobService;
        this.vectorCleanupTaskService = vectorCleanupTaskService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GovernanceSummaryResponse summarize() {
        Instant now = Instant.now(clock);

        long pendingReservations = uploadQuotaReservationRepository.countByStatus(UploadQuotaReservationStatus.PENDING);
        long committedReservations = uploadQuotaReservationRepository.countByStatus(UploadQuotaReservationStatus.COMMITTED);
        long releasedReservations = uploadQuotaReservationRepository.countByStatus(UploadQuotaReservationStatus.RELEASED);
        long pendingReservedBytes = uploadQuotaReservationRepository.sumReservedBytesByStatus(UploadQuotaReservationStatus.PENDING);
        long committedReservedBytes = uploadQuotaReservationRepository.sumReservedBytesByStatus(UploadQuotaReservationStatus.COMMITTED);
        long stalePendingAttempts = uploadQuotaReservationRepository.countStalePendingAttempts(now);

        long pendingJobs = ingestJobRepository.countByStatus(IngestJobStatus.PENDING);
        long processingJobs = ingestJobRepository.countByStatus(IngestJobStatus.PROCESSING);
        long cancelRequestedJobs = ingestJobRepository.countByStatus(IngestJobStatus.CANCEL_REQUESTED);
        long failedJobs = ingestJobRepository.countByStatus(IngestJobStatus.FAILED);
        long deadLetterJobs = ingestJobRepository.countByStatus(IngestJobStatus.DEAD_LETTER);
        long expiredProcessingLeases = ingestJobRepository.countExpiredProcessingLeases(now);

        long pendingOutboxEvents = ingestOutboxEventRepository.countByStatus(IngestOutboxStatus.PENDING);
        long failedOutboxEvents = ingestOutboxEventRepository.countByStatus(IngestOutboxStatus.FAILED);
        long sentOutboxEvents = ingestOutboxEventRepository.countByStatus(IngestOutboxStatus.SENT);
        long cancelledOutboxEvents = ingestOutboxEventRepository.countByStatus(IngestOutboxStatus.CANCELLED);

        long pendingNotifications = notificationRepository.countByDeliveryStatus(IngestFailureNotificationStatus.PENDING);
        long inProgressNotifications = notificationRepository.countByDeliveryStatus(IngestFailureNotificationStatus.IN_PROGRESS);
        long failedNotifications = notificationRepository.countByDeliveryStatus(IngestFailureNotificationStatus.FAILED);
        long exhaustedNotifications = notificationRepository.countByDeliveryStatus(IngestFailureNotificationStatus.EXHAUSTED);
        long deliveredNotifications = notificationRepository.countByDeliveryStatus(IngestFailureNotificationStatus.DELIVERED);

        long pendingVectorCleanupTasks = vectorCleanupTaskRepository.countByStatusIn(List.of(VectorCleanupStatus.PENDING));
        long failedVectorCleanupTasks = vectorCleanupTaskRepository.countByStatusIn(List.of(VectorCleanupStatus.FAILED));

        EnumMap<OperationType, Long> operationCountsByType = zeroCounts(OperationType.class);
        operationJobRepository.countGroupedByType().forEach(row -> {
            if (row.getType() != null) operationCountsByType.put(row.getType(), row.getCount());
        });
        EnumMap<OperationStatus, Long> operationCountsByStatus = zeroCounts(OperationStatus.class);
        operationJobRepository.countGroupedByStatus().forEach(row -> {
            if (row.getStatus() != null) operationCountsByStatus.put(row.getStatus(), row.getCount());
        });
        long dueOperations = operationJobRepository.countDueBefore(now);
        long oldestDueAgeSeconds = operationJobRepository.findOldestDueAt(now)
                .map(dueAt -> Math.max(0L, Duration.between(dueAt, now).getSeconds()))
                .orElse(0L);
        long operationRetryCount = operationJobRepository.sumRetryCount();
        long compensationFailures = operationJobRepository.countByPhaseAndStatus(
                OperationPhase.COMPENSATION, OperationStatus.FAILED);

        List<AuditAlertResponse> alerts = new ArrayList<>();
        alerts.addAll(auditLogService.summarizeAlerts());
        alerts.addAll(ingestJobService.summarizeAlerts());
        alerts.addAll(vectorCleanupTaskService.summarizeAlerts());
        addAlertIfOpen(alerts, "UPLOAD_STALE_PENDING_ATTEMPTS", stalePendingAttempts,
                "Upload quota reservations have stale pending attempts.");
        addAlertIfOpen(alerts, "INGEST_EXPIRED_PROCESSING_LEASES", expiredProcessingLeases,
                "Ingest jobs have expired processing leases.");
        addAlertIfOpen(alerts, "INGEST_OUTBOX_FAILURES_OPEN", failedOutboxEvents,
                "Ingest outbox events are in FAILED status.");
        addAlertIfOpen(alerts, "INGEST_FAILURE_NOTIFICATIONS_EXHAUSTED", exhaustedNotifications,
                "Ingest failure notifications are exhausted.");

        return GovernanceSummaryResponse.builder()
                .generatedAt(now)
                .uploadQuota(GovernanceSummaryResponse.UploadQuota.builder()
                        .pendingReservations(pendingReservations)
                        .committedReservations(committedReservations)
                        .releasedReservations(releasedReservations)
                        .pendingReservedBytes(pendingReservedBytes)
                        .committedReservedBytes(committedReservedBytes)
                        .activeReservedBytes(pendingReservedBytes + committedReservedBytes)
                        .stalePendingAttempts(stalePendingAttempts)
                        .build())
                .ingestJobs(GovernanceSummaryResponse.IngestJobs.builder()
                        .pendingJobs(pendingJobs)
                        .processingJobs(processingJobs)
                        .cancelRequestedJobs(cancelRequestedJobs)
                        .failedJobs(failedJobs)
                        .deadLetterJobs(deadLetterJobs)
                        .expiredProcessingLeases(expiredProcessingLeases)
                        .build())
                .ingestOutbox(GovernanceSummaryResponse.IngestOutbox.builder()
                        .pendingEvents(pendingOutboxEvents)
                        .failedEvents(failedOutboxEvents)
                        .sentEvents(sentOutboxEvents)
                        .cancelledEvents(cancelledOutboxEvents)
                        .build())
                .failureNotifications(GovernanceSummaryResponse.FailureNotifications.builder()
                        .pending(pendingNotifications)
                        .inProgress(inProgressNotifications)
                        .failed(failedNotifications)
                        .exhausted(exhaustedNotifications)
                        .delivered(deliveredNotifications)
                        .build())
                .vectorCleanup(GovernanceSummaryResponse.VectorCleanup.builder()
                        .pendingTasks(pendingVectorCleanupTasks)
                        .failedTasks(failedVectorCleanupTasks)
                        .openTasks(pendingVectorCleanupTasks + failedVectorCleanupTasks)
                        .build())
                .operations(GovernanceSummaryResponse.Operations.builder()
                        .countsByType(operationCountsByType)
                        .countsByStatus(operationCountsByStatus)
                        .due(dueOperations)
                        .oldestDueAgeSeconds(oldestDueAgeSeconds)
                        .retryCount(operationRetryCount)
                        .compensationFailures(compensationFailures)
                        .build())
                .alerts(alerts)
                .build();
    }

    private <E extends Enum<E>> EnumMap<E, Long> zeroCounts(Class<E> enumType) {
        EnumMap<E, Long> counts = new EnumMap<>(enumType);
        for (E value : enumType.getEnumConstants()) counts.put(value, 0L);
        return counts;
    }

    private void addAlertIfOpen(List<AuditAlertResponse> alerts, String code, long count, String message) {
        if (count <= ZERO_THRESHOLD) {
            return;
        }
        alerts.add(AuditAlertResponse.builder()
                .code(code)
                .severity("WARN")
                .message(message)
                .count(count)
                .threshold(ZERO_THRESHOLD)
                .build());
    }
}
