package com.dupi.rag.service;

record UploadIntentCleanupDecision(State state, UploadIntentCleanupClaim claim) {
    enum State {
        CLAIMED,
        PUBLISHED,
        SKIPPED
    }

    static UploadIntentCleanupDecision claimed(UploadIntentCleanupClaim claim) {
        return new UploadIntentCleanupDecision(State.CLAIMED, claim);
    }

    static UploadIntentCleanupDecision published() {
        return new UploadIntentCleanupDecision(State.PUBLISHED, null);
    }

    static UploadIntentCleanupDecision skipped() {
        return new UploadIntentCleanupDecision(State.SKIPPED, null);
    }
}
