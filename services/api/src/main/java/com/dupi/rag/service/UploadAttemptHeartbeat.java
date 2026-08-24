package com.dupi.rag.service;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

final class UploadAttemptHeartbeat implements AutoCloseable {
    private final ScheduledFuture<?> task;
    private final AtomicBoolean ownershipLost;

    UploadAttemptHeartbeat(ScheduledFuture<?> task, AtomicBoolean ownershipLost) {
        this.task = task;
        this.ownershipLost = ownershipLost;
    }

    boolean ownershipLost() {
        return ownershipLost.get();
    }

    @Override
    public void close() {
        task.cancel(false);
    }
}
