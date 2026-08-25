package com.dupi.rag.service;

import java.util.UUID;

/** Stable fencing identity held by the process currently writing an upload object. */
record UploadAttemptLease(UUID reservationId, UUID attemptId, String ownerToken) {
}
