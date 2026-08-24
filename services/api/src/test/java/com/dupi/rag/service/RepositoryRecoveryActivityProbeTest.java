package com.dupi.rag.service;

import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.SparseMigrationRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RepositoryRecoveryActivityProbeTest {
    @Test
    void reportsActivityFromEachDurableJobSource() {
        IngestJobRepository ingest = mock(IngestJobRepository.class);
        RagEvalRunRepository eval = mock(RagEvalRunRepository.class);
        SparseMigrationRepository sparse = mock(SparseMigrationRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        RepositoryRecoveryActivityProbe probe = new RepositoryRecoveryActivityProbe(
                ingest, eval, sparse, documents);
        UUID kbId = UUID.randomUUID();

        when(ingest.existsByKbIdAndStatusIn(eq(kbId), anyList())).thenReturn(true);
        assertThat(probe.hasActiveWork(kbId)).isTrue();
        ArgumentCaptor<java.util.List<IngestJobStatus>> activeStatuses = ArgumentCaptor.forClass(java.util.List.class);
        verify(ingest).existsByKbIdAndStatusIn(eq(kbId), activeStatuses.capture());
        assertThat(activeStatuses.getValue()).contains(IngestJobStatus.UPLOAD_INTENT);
        reset(ingest);
        when(eval.existsByKbIdAndStatus(any(), any())).thenReturn(true);
        assertThat(probe.hasActiveWork(kbId)).isTrue();
        reset(eval);
        when(sparse.existsByKbIdAndStateIn(eq(kbId), anyList())).thenReturn(true);
        assertThat(probe.hasActiveWork(kbId)).isTrue();
        reset(sparse);
        assertThat(probe.hasActiveWork(kbId)).isFalse();
    }

    @Test
    void deletingDocumentBlocksKnowledgeBaseDeletionUntilFinalized() {
        IngestJobRepository ingest = mock(IngestJobRepository.class);
        RagEvalRunRepository eval = mock(RagEvalRunRepository.class);
        SparseMigrationRepository sparse = mock(SparseMigrationRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        RepositoryRecoveryActivityProbe probe = new RepositoryRecoveryActivityProbe(
                ingest, eval, sparse, documents);
        UUID kbId = UUID.randomUUID();
        when(documents.existsByKbIdAndStatus(kbId, DocumentStatus.DELETING)).thenReturn(true);

        assertThat(probe.hasActiveWork(kbId)).isTrue();
    }
}
