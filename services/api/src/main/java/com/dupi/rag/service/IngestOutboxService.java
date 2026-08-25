package com.dupi.rag.service;

import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.IngestOutboxEvent;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
public class IngestOutboxService {

    private final IngestOutboxEventRepository outboxRepository;
    private final IngestJobProducer ingestJobProducer;
    private final IngestOutboxDispatchPersistence dispatchPersistence;

    @Autowired
    public IngestOutboxService(IngestOutboxEventRepository outboxRepository,
                               IngestJobProducer ingestJobProducer,
                               IngestOutboxDispatchPersistence dispatchPersistence) {
        this.outboxRepository = outboxRepository;
        this.ingestJobProducer = ingestJobProducer;
        this.dispatchPersistence = dispatchPersistence;
    }

    IngestOutboxService(IngestOutboxEventRepository outboxRepository,
                        IngestJobRepository ingestJobRepository,
                        DocumentRepository documentRepository,
                        KnowledgeBaseService knowledgeBaseService,
                        IngestJobProducer ingestJobProducer,
                        DocumentTombstoneService documentTombstoneService) {
        this(outboxRepository, ingestJobProducer, new IngestOutboxDispatchPersistence(
                outboxRepository, ingestJobRepository, documentRepository,
                knowledgeBaseService, documentTombstoneService));
    }

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

    public void cancelPendingForJob(UUID jobId, String reason) {
        dispatchPersistence.cancelPendingForJob(jobId, reason);
    }

    @Scheduled(cron = "${dupi.ingest.outbox-dispatch-cron:*/10 * * * * *}")
    public void dispatchPendingOnSchedule() {
        int dispatched = dispatchPending();
        if (dispatched > 0) {
            log.info("Dispatched {} ingest outbox event(s)", dispatched);
        }
    }

    public int dispatchPending() {
        int dispatched = 0;
        for (IngestOutboxDispatchClaim claim : dispatchPersistence.claimDue()) {
            try {
                ingestJobProducer.enqueue(claim.job(), claim.knowledgeBase(), claim.objectKey(),
                        claim.fileName(), claim.mimeType(), claim.executionId());
                if (dispatchPersistence.complete(claim)) dispatched++;
            } catch (Exception e) {
                dispatchPersistence.fail(claim, e);
            }
        }
        return dispatched;
    }

    @Transactional(readOnly = true)
    public boolean hasDurableRecord(UUID jobId) {
        return !outboxRepository.findByJobId(jobId).isEmpty();
    }

}
