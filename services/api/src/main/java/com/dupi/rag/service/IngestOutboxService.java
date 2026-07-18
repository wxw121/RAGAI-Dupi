package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.IngestOutboxEvent;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class IngestOutboxService {

    private final IngestOutboxEventRepository outboxRepository;
    private final IngestJobRepository ingestJobRepository;
    private final DocumentRepository documentRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final IngestJobProducer ingestJobProducer;
    private final DocumentTombstoneService documentTombstoneService;

    @Transactional
    public void record(IngestJob job, KnowledgeBase kb, String objectKey, String fileName, String mimeType) {
        outboxRepository.save(IngestOutboxEvent.builder()
                .jobId(job.getId())
                .kbId(kb.getId())
                .docId(job.getDocId())
                .objectKey(objectKey)
                .fileName(fileName)
                .mimeType(mimeType)
                .status(IngestOutboxStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(Instant.now())
                .build());
    }

    @Transactional
    public void cancelPendingForJob(UUID jobId, String reason) {
        outboxRepository.findByJobIdAndStatusIn(jobId, List.of(IngestOutboxStatus.PENDING, IngestOutboxStatus.FAILED))
                .forEach(event -> cancel(event, reason));
    }

    @Scheduled(cron = "${dupi.ingest.outbox-dispatch-cron:*/10 * * * * *}")
    public void dispatchPendingOnSchedule() {
        int dispatched = dispatchPending();
        if (dispatched > 0) {
            log.info("Dispatched {} ingest outbox event(s)", dispatched);
        }
    }

    @Transactional
    public int dispatchPending() {
        Instant now = Instant.now();
        List<IngestOutboxEvent> events = outboxRepository
                .findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                        List.of(IngestOutboxStatus.PENDING, IngestOutboxStatus.FAILED),
                        now
                );
        int dispatched = 0;
        for (IngestOutboxEvent event : events) {
            if (documentTombstoneService.isDeleted(event.getDocId())) {
                cancel(event, "Document was deleted before ingest dispatch");
                continue;
            }

            IngestJob job = ingestJobRepository.findById(event.getJobId()).orElse(null);
            Document doc = documentRepository.findById(event.getDocId()).orElse(null);
            if (job == null || doc == null) {
                cancel(event, "Ingest job or document no longer exists");
                continue;
            }

            try {
                KnowledgeBase kb = knowledgeBaseService.findSystemOrThrow(event.getKbId());
                ingestJobProducer.enqueue(job, kb, event.getObjectKey(), event.getFileName(), event.getMimeType());
                event.setStatus(IngestOutboxStatus.SENT);
                event.setLastError(null);
                event.setNextAttemptAt(now);
                job.setErrorMessage(null);
                doc.setStatus(DocumentStatus.PROCESSING);
                doc.setErrorMessage(null);
                ingestJobRepository.save(job);
                documentRepository.save(doc);
                outboxRepository.save(event);
                dispatched++;
            } catch (Exception e) {
                markRetryable(event, job, doc, now, e);
            }
        }
        return dispatched;
    }

    private void cancel(IngestOutboxEvent event, String reason) {
        event.setStatus(IngestOutboxStatus.CANCELLED);
        event.setLastError(reason);
        event.setNextAttemptAt(Instant.now());
        outboxRepository.save(event);
    }

    private void markRetryable(IngestOutboxEvent event, IngestJob job, Document doc, Instant now, Exception error) {
        int attempts = event.getAttemptCount() == null ? 1 : event.getAttemptCount() + 1;
        String reason = errorReason(error);
        event.setStatus(IngestOutboxStatus.FAILED);
        event.setAttemptCount(attempts);
        event.setLastError("Waiting for ingest queue recovery: " + reason);
        event.setNextAttemptAt(now.plus(backoff(attempts)));
        outboxRepository.save(event);

        if (job != null) {
            job.setStatus(IngestJobStatus.PENDING);
            job.setStage(IngestStage.QUEUED);
            job.setErrorMessage("Waiting for ingest queue recovery: " + reason);
            ingestJobRepository.save(job);
        }
        if (doc != null) {
            doc.setStatus(DocumentStatus.PENDING);
            doc.setErrorMessage("Waiting for ingest queue recovery: " + reason);
            documentRepository.save(doc);
        }
    }

    private Duration backoff(int attempts) {
        long seconds = Math.min(300, Math.max(10, attempts * 10L));
        return Duration.ofSeconds(seconds);
    }

    private String errorReason(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }
}
