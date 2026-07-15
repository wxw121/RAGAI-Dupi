package com.dupi.rag.service;

import com.dupi.rag.config.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class RecoveryJobExecutor {
    private final Executor executor;
    private final RecoveryArchiveService archives;
    private final RecoveryRestoreService restores;

    public RecoveryJobExecutor(@Qualifier("recoveryExecutor") Executor executor,
                               RecoveryArchiveService archives, RecoveryRestoreService restores) {
        this.executor = executor;
        this.archives = archives;
        this.restores = restores;
    }

    public void submitArchive(UUID archiveId) {
        String tenantId = archives.getSystem(archiveId).getTenantId();
        try {
            CompletableFuture.runAsync(() -> {
                TenantContext.setTenantId(tenantId);
                try {
                    archives.capture(archiveId);
                } finally {
                    TenantContext.clear();
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            archives.markFailed(archiveId, "RECOVERY_CAPACITY_EXCEEDED");
        }
    }

    public void submitRestore(UUID jobId, String tenantId) {
        try {
            CompletableFuture.runAsync(() -> {
                TenantContext.setTenantId(tenantId);
                try {
                    restores.execute(jobId);
                } finally {
                    TenantContext.clear();
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            restores.markFailed(jobId, tenantId, "RECOVERY_CAPACITY_EXCEEDED");
        }
    }
}
