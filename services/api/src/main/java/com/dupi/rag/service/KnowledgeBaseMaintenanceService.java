package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.domain.entity.RecoveryArchive;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.exception.KnowledgeBaseMaintenanceException;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import com.dupi.rag.repository.RecoveryArchiveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseMaintenanceService {
    private static final Collection<RecoveryArchiveStatus> ACTIVE_ARCHIVE_STATES = List.of(
            RecoveryArchiveStatus.PREPARING,
            RecoveryArchiveStatus.CAPTURING,
            RecoveryArchiveStatus.VERIFYING);

    private final KnowledgeBaseRepository knowledgeBases;
    private final RecoveryArchiveRepository archives;
    private final List<RecoveryActivityProbe> activityProbes;
    private final RecoveryProperties properties;

    @Transactional
    public void acquire(UUID knowledgeBaseId, UUID archiveId) {
        knowledgeBases.findSystemByIdForUpdate(knowledgeBaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base not found: " + knowledgeBaseId));
        RecoveryArchive archive = archives.findById(archiveId)
                .orElseThrow(() -> new ResourceNotFoundException("Recovery archive not found: " + archiveId));
        if (!knowledgeBaseId.equals(archive.getSourceKnowledgeBaseId())
                || archive.getStatus() != RecoveryArchiveStatus.PREPARING) {
            throw new KnowledgeBaseMaintenanceException("Recovery archive cannot acquire this knowledge base");
        }
        if (archives.existsActiveBySourceKnowledgeBaseIdExcluding(knowledgeBaseId, archiveId)) {
            throw new KnowledgeBaseMaintenanceException("Another recovery archive owns this knowledge base");
        }

        Instant deadline = Instant.now().plus(Duration.ofSeconds(properties.getQuiescenceTimeoutSeconds()));
        while (hasActiveWork(knowledgeBaseId)) {
            if (!Instant.now().isBefore(deadline)) {
                throw new KnowledgeBaseMaintenanceException("Knowledge base did not become quiescent before recovery timeout");
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KnowledgeBaseMaintenanceException("Interrupted while waiting for recovery maintenance");
            }
        }
        archive.setStatus(RecoveryArchiveStatus.CAPTURING);
        archives.save(archive);
    }

    public void assertMutationAllowed(UUID knowledgeBaseId) {
        if (archives.existsBySourceKnowledgeBaseIdAndStatusIn(knowledgeBaseId, ACTIVE_ARCHIVE_STATES)) {
            throw new KnowledgeBaseMaintenanceException(
                    "Knowledge base mutations are blocked by an active recovery archive");
        }
    }

    @Transactional
    public void release(UUID archiveId, RecoveryArchiveStatus terminalStatus) {
        if (terminalStatus != RecoveryArchiveStatus.COMPLETED
                && terminalStatus != RecoveryArchiveStatus.FAILED) {
            throw new IllegalArgumentException("Recovery maintenance can only release to a terminal state");
        }
        RecoveryArchive archive = archives.findById(archiveId)
                .orElseThrow(() -> new ResourceNotFoundException("Recovery archive not found: " + archiveId));
        archive.setStatus(terminalStatus);
        archives.save(archive);
    }

    private boolean hasActiveWork(UUID knowledgeBaseId) {
        return activityProbes.stream().anyMatch(probe -> probe.hasActiveWork(knowledgeBaseId));
    }
}
