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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Short database transactions around unlocked Redis publication. */
@Service
class IngestOutboxDispatchPersistence {
    private final IngestOutboxEventRepository outbox;
    private final IngestJobRepository jobs;
    private final DocumentRepository documents;
    private final IngestOutboxCandidateClaimPersistence candidateClaims;

    @Autowired
    IngestOutboxDispatchPersistence(IngestOutboxEventRepository outbox,
                                    IngestJobRepository jobs,
                                    DocumentRepository documents,
                                    IngestOutboxCandidateClaimPersistence candidateClaims) {
        this.outbox = outbox;
        this.jobs = jobs;
        this.documents = documents;
        this.candidateClaims = candidateClaims;
    }

    IngestOutboxDispatchPersistence(IngestOutboxEventRepository outbox,
                                    IngestJobRepository jobs,
                                    DocumentRepository documents,
                                    KnowledgeBaseService knowledgeBases,
                                    DocumentTombstoneService tombstones) {
        this(outbox, jobs, documents, new IngestOutboxCandidateClaimPersistence(
                outbox, jobs, documents, knowledgeBases, tombstones));
    }

    List<IngestOutboxDispatchClaim> claimDue() {
        Instant now = Instant.now();
        List<IngestOutboxDispatchClaim> claims = new ArrayList<>();
        for (IngestOutboxDispatchCandidate candidate : outbox.findDispatchCandidates(
                List.of(IngestOutboxStatus.PENDING, IngestOutboxStatus.FAILED), now,
                PageRequest.of(0, 50))) {
            candidateClaims.claim(candidate).ifPresent(claims::add);
        }
        return claims;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean complete(IngestOutboxDispatchClaim claim) {
        LockedClaim locked = locked(claim);
        if (locked == null) return false;
        if (!isDispatchable(locked.job(), locked.document())) {
            cancel(locked.event(), "Ingest job or document is no longer dispatchable");
            return false;
        }
        IngestOutboxEvent event = locked.event();
        IngestJob job = locked.job();
        Document document = locked.document();
        event.setStatus(IngestOutboxStatus.SENT);
        event.setLastError(null);
        event.setNextAttemptAt(Instant.now());
        job.setErrorMessage(null);
        document.setStatus(DocumentStatus.PROCESSING);
        document.setErrorMessage(null);
        jobs.save(job);
        documents.save(document);
        outbox.save(event);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(IngestOutboxDispatchClaim claim, Exception error) {
        LockedClaim locked = locked(claim);
        if (locked == null) return;
        IngestOutboxEvent event = locked.event();
        IngestJob job = locked.job();
        Document document = locked.document();
        if (!isDispatchable(job, document)
                || error instanceof OperationConflictException
                || error instanceof ResourceNotFoundException) {
            cancel(event, "Knowledge base or ingest work is no longer dispatchable");
            return;
        }
        int attempts = event.getAttemptCount() == null ? 1 : event.getAttemptCount() + 1;
        String reason = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
        String diagnostic = "Waiting for ingest queue recovery: " + reason;
        event.setStatus(IngestOutboxStatus.FAILED);
        event.setAttemptCount(attempts);
        event.setLastError(diagnostic);
        event.setNextAttemptAt(Instant.now().plus(backoff(attempts)));
        job.setStatus(IngestJobStatus.PENDING);
        job.setStage(IngestStage.QUEUED);
        job.setErrorMessage(diagnostic);
        document.setStatus(DocumentStatus.PENDING);
        document.setErrorMessage(diagnostic);
        outbox.save(event);
        jobs.save(job);
        documents.save(document);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void cancelPendingForJob(UUID jobId, String reason) {
        for (UUID eventId : outbox.findIdsByJobIdAndStatusIn(jobId,
                List.of(IngestOutboxStatus.PENDING, IngestOutboxStatus.FAILED))) {
            IngestOutboxEvent event = outbox.findByIdForUpdate(eventId).orElse(null);
            if (event != null && jobId.equals(event.getJobId())
                    && (event.getStatus() == IngestOutboxStatus.PENDING
                    || event.getStatus() == IngestOutboxStatus.FAILED)) {
                cancel(event, reason);
            }
        }
    }

    private LockedClaim locked(IngestOutboxDispatchClaim claim) {
        IngestJob job = jobs.findByIdForUpdate(claim.job().getId()).orElse(null);
        Document document = documents.findByIdForUpdate(claim.document().getId()).orElse(null);
        IngestOutboxEvent event = outbox.findByIdForUpdate(claim.eventId()).orElse(null);
        if (job == null || document == null || event == null
                || !claim.executionId().equals(job.getExecutionId())
                || !job.getId().equals(event.getJobId())
                || !document.getId().equals(event.getDocId())
                || (event.getStatus() != IngestOutboxStatus.PENDING
                && event.getStatus() != IngestOutboxStatus.FAILED)
                || !claim.claimMarker().equals(event.getLastError())) return null;
        return new LockedClaim(event, job, document);
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

    private Duration backoff(int attempts) {
        return Duration.ofSeconds(Math.min(300, Math.max(10, attempts * 10L)));
    }

    private record LockedClaim(IngestOutboxEvent event, IngestJob job, Document document) { }
}
