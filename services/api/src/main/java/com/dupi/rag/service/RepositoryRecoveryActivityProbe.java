package com.dupi.rag.service;

import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.SparseMigrationState;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.DocumentTombstoneRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.SparseMigrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RepositoryRecoveryActivityProbe implements RecoveryActivityProbe {
    private static final List<IngestJobStatus> ACTIVE_INGEST =
            List.of(IngestJobStatus.UPLOAD_INTENT, IngestJobStatus.PENDING, IngestJobStatus.PROCESSING);
    private static final List<SparseMigrationState> ACTIVE_MIGRATIONS = List.of(
            SparseMigrationState.PREPARING,
            SparseMigrationState.BACKFILLING,
            SparseMigrationState.DUAL_WRITING,
            SparseMigrationState.SHADOW_VALIDATING,
            SparseMigrationState.CUTOVER);

    private final IngestJobRepository ingestJobs;
    private final RagEvalRunRepository evalRuns;
    private final SparseMigrationRepository sparseMigrations;
    private final DocumentRepository documents;
    private final DocumentTombstoneRepository tombstones;

    @Override
    public boolean hasActiveWork(UUID knowledgeBaseId) {
        return ingestJobs.existsByKbIdAndStatusIn(knowledgeBaseId, ACTIVE_INGEST)
                || evalRuns.existsByKbIdAndStatus(knowledgeBaseId, RagEvalRunStatus.RUNNING)
                || sparseMigrations.existsByKbIdAndStateIn(knowledgeBaseId, ACTIVE_MIGRATIONS)
                || documents.existsByKbIdAndStatus(knowledgeBaseId, DocumentStatus.DELETING)
                || tombstones.existsByKbIdAndReasonIn(knowledgeBaseId, List.of(
                        DocumentTombstoneService.UPLOAD_WRITE_ARMED,
                        DocumentTombstoneService.UPLOAD_ABANDONED));
    }
}
