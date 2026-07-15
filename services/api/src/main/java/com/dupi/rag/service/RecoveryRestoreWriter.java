package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RecoveryRestoreJob;

public interface RecoveryRestoreWriter {
    void restore(RecoveryRestoreJob job);
    void abandon(RecoveryRestoreJob job);
}
