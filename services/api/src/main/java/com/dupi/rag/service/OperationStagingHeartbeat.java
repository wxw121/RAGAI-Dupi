package com.dupi.rag.service;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

final class OperationStagingHeartbeat implements AutoCloseable {
    private final ScheduledFuture<?> task;
    private final AtomicBoolean lost;
    OperationStagingHeartbeat(ScheduledFuture<?> task, AtomicBoolean lost) {
        this.task = task; this.lost = lost;
    }
    boolean ownershipLost() { return lost.get(); }
    @Override public void close() { task.cancel(false); }
}
