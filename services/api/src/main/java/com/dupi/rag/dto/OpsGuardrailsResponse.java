package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OpsGuardrailsResponse {
    private UploadRateLimit uploadRateLimit;
    private IngestQueue ingestQueue;
    private Audit audit;
    private Multipart multipart;

    @Data
    @Builder
    public static class UploadRateLimit {
        private boolean enabled;
        private int requests;
        private long windowSeconds;
    }

    @Data
    @Builder
    public static class IngestQueue {
        private int maxPendingJobs;
        private int maxRecoveryAttempts;
    }

    @Data
    @Builder
    public static class Audit {
        private int alertWindowMinutes;
        private int alertFailedThreshold;
    }

    @Data
    @Builder
    public static class Multipart {
        private long maxFileSizeBytes;
    }
}
