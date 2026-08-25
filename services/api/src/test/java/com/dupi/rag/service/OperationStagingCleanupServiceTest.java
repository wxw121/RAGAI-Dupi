package com.dupi.rag.service;

import com.dupi.rag.repository.OperationStagingAttemptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OperationStagingCleanupServiceTest {
    private final Instant now = Instant.parse("2026-08-25T10:00:00Z");
    private final OperationStagingAttemptRepository attempts = mock(OperationStagingAttemptRepository.class);
    private final OperationStagingCleanupPersistence persistence = mock(OperationStagingCleanupPersistence.class);
    private final MinioStorageService minio = mock(MinioStorageService.class);
    private final RecoveryStorageService recovery = mock(RecoveryStorageService.class);

    @Test
    void absentObjectKeepsDurableCleanupTruthForALateWriter() {
        UUID id = UUID.randomUUID();
        var claim = new OperationStagingCleanupClaim(id, UUID.randomUUID(), "MINIO", "stage.attempt-1");
        when(attempts.findCleanupPending(PageRequest.of(0, 1))).thenReturn(List.of(id));
        when(persistence.claim(eq(id), eq(now), any())).thenReturn(claim);
        when(minio.existsChecked(claim.objectKey())).thenReturn(false);

        assertThat(service(0).cleanupPending()).isZero();

        verify(persistence).releaseAbsent(claim, now);
        verify(persistence, never()).complete(any(), any());
        verify(minio, never()).deleteChecked(any());
    }

    @Test
    void lateObjectIsCheckedDeletedBeforeTruthIsCompleted() {
        UUID id = UUID.randomUUID();
        var claim = new OperationStagingCleanupClaim(id, UUID.randomUUID(), "RECOVERY", "stage.attempt-2");
        when(attempts.findCleanupPending(PageRequest.of(0, 3))).thenReturn(List.of(id));
        when(persistence.claim(eq(id), eq(now), any())).thenReturn(claim);
        when(recovery.exists(claim.objectKey())).thenReturn(true);

        assertThat(service(3).cleanupPending()).isOne();

        var order = inOrder(recovery, persistence);
        order.verify(recovery).delete(claim.objectKey());
        order.verify(persistence).complete(claim, now);
    }

    private OperationStagingCleanupService service(int limit) {
        return new OperationStagingCleanupService(attempts, persistence, minio, recovery,
                limit, Duration.ofMinutes(5), Clock.fixed(now, ZoneOffset.UTC));
    }
}
