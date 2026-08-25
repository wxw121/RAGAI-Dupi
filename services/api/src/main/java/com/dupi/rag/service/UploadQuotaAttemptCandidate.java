package com.dupi.rag.service;

import java.util.UUID;

/** Immutable discovery result; reconciliation must reload the reservation before mutation. */
public record UploadQuotaAttemptCandidate(UUID reservationId, UUID attemptId, String ownerToken) {
}
