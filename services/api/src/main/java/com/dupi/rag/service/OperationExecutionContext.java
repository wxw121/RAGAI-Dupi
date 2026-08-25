package com.dupi.rag.service;

import com.dupi.rag.domain.enums.OperationPhase;
import java.util.UUID;

/** Immutable ownership proof passed to all workflow and step mutations. */
public record OperationExecutionContext(
        UUID jobId,
        UUID claimToken,
        long claimEpoch,
        long retryEpoch,
        OperationPhase phase,
        String tenantId,
        String createdBy
) {
    public OperationExecutionContext(UUID jobId, UUID claimToken, long claimEpoch, long retryEpoch,
                                     OperationPhase phase) {
        this(jobId, claimToken, claimEpoch, retryEpoch, phase, null, null);
    }
}
