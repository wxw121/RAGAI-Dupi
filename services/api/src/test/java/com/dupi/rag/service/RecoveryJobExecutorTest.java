package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RecoveryArchive;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.Mockito.*;

class RecoveryJobExecutorTest {
    @Test
    void propagatesTenantForArchiveAndRestoreJobs() {
        RecoveryArchiveService archives = mock(RecoveryArchiveService.class);
        RecoveryRestoreService restores = mock(RecoveryRestoreService.class);
        UUID archiveId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(archives.getSystem(archiveId)).thenReturn(RecoveryArchive.builder().tenantId("tenant-a").build());
        RecoveryJobExecutor executor = new RecoveryJobExecutor(Runnable::run, archives, restores);

        executor.submitArchive(archiveId);
        executor.submitRestore(jobId, "tenant-a");

        verify(archives).capture(archiveId);
        verify(restores).execute(jobId);
    }

    @Test
    void rejectedJobsBecomeRetryableFailures() {
        RecoveryArchiveService archives = mock(RecoveryArchiveService.class);
        RecoveryRestoreService restores = mock(RecoveryRestoreService.class);
        UUID archiveId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(archives.getSystem(archiveId)).thenReturn(RecoveryArchive.builder().tenantId("tenant-a").build());
        Executor rejecting = command -> { throw new RejectedExecutionException("full"); };
        RecoveryJobExecutor executor = new RecoveryJobExecutor(rejecting, archives, restores);

        executor.submitArchive(archiveId);
        executor.submitRestore(jobId, "tenant-a");

        verify(archives).markFailed(archiveId, "RECOVERY_CAPACITY_EXCEEDED");
        verify(restores).markFailed(jobId, "tenant-a", "RECOVERY_CAPACITY_EXCEEDED");
    }
}
