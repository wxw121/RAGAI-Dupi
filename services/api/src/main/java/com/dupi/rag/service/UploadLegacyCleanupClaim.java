package com.dupi.rag.service;

import java.util.UUID;

record UploadLegacyCleanupClaim(
        UUID reservationId,
        UUID attemptId,
        UUID documentId,
        UUID jobId,
        String objectKey,
        String ownerToken) {
}
