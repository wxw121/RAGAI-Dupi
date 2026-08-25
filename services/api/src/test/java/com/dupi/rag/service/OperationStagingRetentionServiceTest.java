package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.repository.OperationJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OperationStagingRetentionServiceTest {

    @Test
    void configuredRetentionDrivesABoundedExpiredIntakeCleanup() {
        Instant now = Instant.parse("2026-08-25T10:00:00Z");
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        OperationStagingRetentionPersistenceService persistence =
                mock(OperationStagingRetentionPersistenceService.class);
        UUID jobId = UUID.randomUUID();
        when(jobs.findExpiredStagingIntakes(
                now.minusSeconds(6 * 3600L), PageRequest.of(0, 7)))
                .thenReturn(List.of(jobId));
        when(persistence.scheduleCleanup(jobId, now.minusSeconds(6 * 3600L), now))
                .thenReturn(true);

        int scheduled = new OperationStagingRetentionService(
                jobs, persistence, 6, 7, Clock.fixed(now, ZoneOffset.UTC))
                .scheduleExpiredIntakeCleanup();

        assertThat(scheduled).isEqualTo(1);
        verify(persistence).scheduleCleanup(jobId, now.minusSeconds(6 * 3600L), now);
    }

    @Test
    void nonPositiveRetentionAndLimitClampToOne() {
        Instant now = Instant.parse("2026-08-25T10:00:00Z");
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        OperationStagingRetentionPersistenceService persistence =
                mock(OperationStagingRetentionPersistenceService.class);
        when(jobs.findExpiredStagingIntakes(now.minusSeconds(3600), PageRequest.of(0, 1)))
                .thenReturn(List.of());

        new OperationStagingRetentionService(
                jobs, persistence, 0, -4, Clock.fixed(now, ZoneOffset.UTC))
                .scheduleExpiredIntakeCleanup();

        verify(jobs).findExpiredStagingIntakes(now.minusSeconds(3600), PageRequest.of(0, 1));
    }

    @Test
    void persistenceFencesAndAuditsOnlyTheFirstExpiredTransition() {
        Instant cutoff = Instant.parse("2026-08-25T04:00:00Z");
        Instant now = Instant.parse("2026-08-25T10:00:00Z");
        UUID jobId = UUID.randomUUID();
        OperationJob job = OperationJob.builder().id(jobId).tenantId("tenant-a")
                .operationType(OperationType.RECOVERY_ARCHIVE_IMPORT)
                .status(OperationStatus.PREPARED).phase(OperationPhase.FORWARD)
                .runnable(false).createdAt(cutoff.minusSeconds(1)).build();
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        OperationStagingRetentionPersistenceService persistence =
                new OperationStagingRetentionPersistenceService(jobs, audit);

        assertThat(persistence.scheduleCleanup(jobId, cutoff, now)).isTrue();
        assertThat(persistence.scheduleCleanup(jobId, cutoff, now)).isFalse();

        assertThat(job.getStatus()).isEqualTo(OperationStatus.COMPENSATING);
        assertThat(job.getPhase()).isEqualTo(OperationPhase.COMPENSATION);
        assertThat(job.getRunnable()).isTrue();
        verify(audit, times(1)).recordOperationInCurrentTransaction(
                "tenant-a", AuditLogService.OPERATION_COMPENSATE, jobId,
                "operation_staging_retention_expired");
    }
}
