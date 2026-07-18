package com.dupi.rag.repository;

import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UploadQuotaReservationRepositoryTest {

    @Test
    void defaultUsageHelpersDelegateToCommittedAndActiveStatusQueries() {
        UploadQuotaReservationRepository repository = mock(
                UploadQuotaReservationRepository.class,
                CALLS_REAL_METHODS
        );
        when(repository.sumBytesByStatus("tenant-a", "alice", UploadQuotaReservationStatus.COMMITTED))
                .thenReturn(12L);
        when(repository.countByStatus("tenant-a", "alice", UploadQuotaReservationStatus.COMMITTED))
                .thenReturn(2L);
        List<UploadQuotaReservationStatus> activeStatuses = List.of(
                UploadQuotaReservationStatus.PENDING,
                UploadQuotaReservationStatus.COMMITTED);
        when(repository.sumBytesByStatuses("tenant-a", "alice", activeStatuses)).thenReturn(30L);
        when(repository.countByStatuses("tenant-a", "alice", activeStatuses)).thenReturn(3L);

        assertThat(repository.sumCommittedBytes("tenant-a", "alice")).isEqualTo(12L);
        assertThat(repository.countCommittedDocuments("tenant-a", "alice")).isEqualTo(2L);
        assertThat(repository.sumActiveReservedBytes("tenant-a", "alice")).isEqualTo(30L);
        assertThat(repository.countActiveReservedDocuments("tenant-a", "alice")).isEqualTo(3L);
    }
}
