package com.dupi.rag.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class GovernanceSummaryResponse {
    private Instant generatedAt;
    private UploadQuota uploadQuota;
    private IngestJobs ingestJobs;
    private IngestOutbox ingestOutbox;
    private FailureNotifications failureNotifications;
    private VectorCleanup vectorCleanup;
    private Operations operations;
    private List<AuditAlertResponse> alerts;

    @Getter
    @Setter
    @Builder
    public static class UploadQuota {
        private long pendingReservations;
        private long committedReservations;
        private long releasedReservations;
        private long pendingReservedBytes;
        private long committedReservedBytes;
        private long activeReservedBytes;
        private long stalePendingAttempts;
    }

    @Getter
    @Setter
    @Builder
    public static class IngestJobs {
        private long pendingJobs;
        private long processingJobs;
        private long cancelRequestedJobs;
        private long failedJobs;
        private long deadLetterJobs;
        private long expiredProcessingLeases;
    }

    @Getter
    @Setter
    @Builder
    public static class IngestOutbox {
        private long pendingEvents;
        private long failedEvents;
        private long sentEvents;
        private long cancelledEvents;
    }

    @Getter
    @Setter
    @Builder
    public static class FailureNotifications {
        private long pending;
        private long inProgress;
        private long failed;
        private long exhausted;
        private long delivered;
    }

    @Getter
    @Setter
    @Builder
    public static class VectorCleanup {
        private long pendingTasks;
        private long failedTasks;
        private long openTasks;
    }

    @Getter
    @Setter
    @Builder
    public static class Operations {
        private Map<OperationType, Long> countsByType;
        private Map<OperationStatus, Long> countsByStatus;
        private long due;
        private long oldestDueAgeSeconds;
        /** Claimed attempts after the first claim of each entered execution phase. */
        private long retryCount;
        private long compensationFailures;
    }
}
