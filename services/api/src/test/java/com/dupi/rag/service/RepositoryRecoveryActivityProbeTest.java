package com.dupi.rag.service;

import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.SparseMigrationRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RepositoryRecoveryActivityProbeTest {
    @Test
    void reportsActivityFromEachDurableJobSource() {
        IngestJobRepository ingest = mock(IngestJobRepository.class);
        RagEvalRunRepository eval = mock(RagEvalRunRepository.class);
        SparseMigrationRepository sparse = mock(SparseMigrationRepository.class);
        RepositoryRecoveryActivityProbe probe = new RepositoryRecoveryActivityProbe(ingest, eval, sparse);
        UUID kbId = UUID.randomUUID();

        when(ingest.existsByKbIdAndStatusIn(eq(kbId), anyList())).thenReturn(true);
        assertThat(probe.hasActiveWork(kbId)).isTrue();
        reset(ingest);
        when(eval.existsByKbIdAndStatus(any(), any())).thenReturn(true);
        assertThat(probe.hasActiveWork(kbId)).isTrue();
        reset(eval);
        when(sparse.existsByKbIdAndStateIn(eq(kbId), anyList())).thenReturn(true);
        assertThat(probe.hasActiveWork(kbId)).isTrue();
        reset(sparse);
        assertThat(probe.hasActiveWork(kbId)).isFalse();
    }
}
