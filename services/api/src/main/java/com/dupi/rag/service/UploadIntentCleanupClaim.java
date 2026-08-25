package com.dupi.rag.service;

import java.util.UUID;

record UploadIntentCleanupClaim(
        UUID reservationId,
        UUID documentId,
        UUID jobId,
        String objectKey,
        String owner
) {
}
