package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.IngestOutboxEvent;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxDispatchCandidate;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Claims one unlocked outbox candidate in its own short transaction. */
@Service
@RequiredArgsConstructor
class IngestOutboxCandidateClaimPersistence {
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

    private final IngestOutboxEventRepository outbox;
    private final IngestJobRepository jobs;
    private final DocumentRepository documents;
    private final KnowledgeBaseService knowledgeBases;
    private final DocumentTombstoneService tombstones;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<IngestOutboxDispatchClaim> claim(IngestOutboxDispatchCandidate candidate) {
        Instant now = Instant.now();
        IngestJob job = jobs.findByIdForUpdate(candidate.jobId()).orElse(null);
        Document document = documents.findByIdForUpdate(candidate.documentId()).orElse(null);
        IngestOutboxEvent event = outbox.findByIdForUpdate(candidate.eventId()).orElse(null);
        if (!eligible(candidate, event, now)) return Optional.empty();
        if (tombstones.isDeleted(event.getDocId())) {
            cancel(event, "Document was deleted before ingest dispatch");
            return Optional.empty();
        }
        if (job == null || document == null) {
            cancel(event, "Ingest job or document no longer exists");
            return Optional.empty();
        }
        if (!isDispatchable(job, document)) {
            cancel(event, "Ingest job or document is not dispatchable");
            return Optional.empty();
        }
        try {
            var knowledgeBase = knowledgeBases.findSystemOrThrow(event.getKbId());
            if (job.getExecutionId() == null) {
                job.setExecutionId(UUID.randomUUID());
                jobs.save(job);
            }
            String marker = "Dispatch claim " + UUID.randomUUID();
            event.setLastError(marker);
            event.setNextAttemptAt(now.plus(CLAIM_LEASE));
            outbox.save(event);
            return Optional.of(new IngestOutboxDispatchClaim(event.getId(), marker, job.getExecutionId(), job,
                    document, knowledgeBase, event.getObjectKey(), event.getFileName(), event.getMimeType()));
        } catch (OperationConflictException | ResourceNotFoundException unavailable) {
            cancel(event, "Knowledge base or ingest work is no longer dispatchable");
            return Optional.empty();
        }
    }

    private boolean eligible(IngestOutboxDispatchCandidate candidate,
                             IngestOutboxEvent event, Instant now) {
        return event != null
                && candidate.jobId().equals(event.getJobId())
                && candidate.documentId().equals(event.getDocId())
                && (event.getStatus() == IngestOutboxStatus.PENDING
                || event.getStatus() == IngestOutboxStatus.FAILED)
                && event.getNextAttemptAt() != null
                && !event.getNextAttemptAt().isAfter(now);
    }

    private boolean isDispatchable(IngestJob job, Document document) {
        return job.getStatus() == IngestJobStatus.PENDING
                && job.getStage() == IngestStage.QUEUED
                && document.getStatus() == DocumentStatus.PENDING;
    }

    private void cancel(IngestOutboxEvent event, String reason) {
        event.setStatus(IngestOutboxStatus.CANCELLED);
        event.setLastError(reason);
        event.setNextAttemptAt(Instant.now());
        outbox.save(event);
    }
}
