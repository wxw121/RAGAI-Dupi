package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;

/** One bounded database outcome: claim, terminal cleanup, or no selectable row. */
public record OperationClaimResult(OperationClaimKind kind, OperationJob job) {
    static OperationClaimResult claimed(OperationJob job) {
        return new OperationClaimResult(OperationClaimKind.CLAIMED, job);
    }

    static OperationClaimResult terminalized(OperationJob job) {
        return new OperationClaimResult(OperationClaimKind.TERMINALIZED, job);
    }

    static OperationClaimResult none() {
        return new OperationClaimResult(OperationClaimKind.NONE, null);
    }
}
