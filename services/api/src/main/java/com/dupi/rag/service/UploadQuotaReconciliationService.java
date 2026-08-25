package com.dupi.rag.service;

import com.dupi.rag.config.UploadQuotaProperties;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.IngestOutboxEvent;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import com.dupi.rag.repository.UploadQuotaReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UploadQuotaReconciliationService {

    private final UploadQuotaReservationRepository reservationRepository;
    private final DocumentRepository documentRepository;
    private final IngestJobRepository ingestJobRepository;
    private final IngestOutboxEventRepository outboxRepository;
    private final MinioStorageService minioStorageService;
    private final UploadQuotaProperties properties;
    private final UploadIntentCleanupService intentCleanup;
    private final UploadQuotaReconciliationPersistenceService persistence;
    private final AbandonedUploadCleanupService abandonedUploads;

    @Scheduled(cron = "${dupi.upload-quota.reconciliation-cron:0 */5 * * * *}")
    public void reconcileStalePendingReservationsOnSchedule() {
        int reconciled = reconcileStalePendingReservationsInternal();
        reconciled += abandonedUploads.cleanup(Math.max(1, properties.getReconciliationBatchSize()));
        if (reconciled > 0) {
            log.info("Reconciled {} stale upload quota reservation(s)", reconciled);
        }
    }

    public int reconcileStalePendingReservations() {
        return reconcileStalePendingReservationsInternal();
    }

    private int reconcileStalePendingReservationsInternal() {
        int limit = Math.max(1, properties.getReconciliationBatchSize());
        List<UploadQuotaAttemptCandidate> reservations = reservationRepository
                .findStalePendingAttempts(Instant.now(), PageRequest.of(0, limit));
        int reconciled = 0;
        for (UploadQuotaAttemptCandidate reservation : reservations) {
            if (reservation.attemptId() == null) {
                continue;
            }
            if (reconcile(reservation)) {
                reconciled++;
            }
        }
        return reconciled;
    }

    private boolean reconcile(UploadQuotaAttemptCandidate reservation) {
        Optional<Document> maybeDoc = documentRepository.findById(reservation.attemptId());
        if (maybeDoc.isEmpty()) {
            return persistence.releaseMissing(
                    reservation, "Released stale upload attempt without durable document");
        }

        Document doc = maybeDoc.get();
        if (doc.getImportJobId() != null || doc.getStatus() == DocumentStatus.IMPORTING) {
            return false;
        }
        IngestJob job = ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(doc.getId()).orElse(null);
        if (job != null && job.getStatus() == IngestJobStatus.UPLOAD_INTENT) {
            UploadIntentCleanupDecision decision = intentCleanup.claim(reservation, Instant.now());
            if (decision.state() == UploadIntentCleanupDecision.State.PUBLISHED) {
                return persistence.commitPublished(reservation, doc.getId(), job.getId());
            }
            if (decision.state() != UploadIntentCleanupDecision.State.CLAIMED) {
                return false;
            }
            if (!cleanupObject(decision.claim().objectKey(), reservation)) {
                return false;
            }
            intentCleanup.complete(decision.claim(),
                    "Upload attempt expired after object cleanup");
            return true;
        }
        if (job != null && hasPublishedOutbox(job, doc)) {
            return persistence.commitPublished(reservation, doc.getId(), job.getId());
        }

        Optional<UploadLegacyCleanupClaim> claim = persistence.claimLegacyCleanup(
                reservation, doc.getId(), job == null ? null : job.getId(), Instant.now());
        if (claim.isEmpty()) {
            return false;
        }
        if (!cleanupObject(claim.get().objectKey(), reservation)) {
            return false;
        }
        return persistence.completeLegacyCleanup(
                claim.get(), "Released stale upload attempt after cleanup");
    }

    private boolean hasPublishedOutbox(IngestJob job, Document doc) {
        if (job.getStatus() != IngestJobStatus.PENDING
                || job.getStage() != IngestStage.QUEUED
                || doc.getStatus() != DocumentStatus.PENDING) {
            return false;
        }
        List<IngestOutboxEvent> outboxEvents = outboxRepository.findByJobId(job.getId());
        return outboxEvents.stream().anyMatch(event -> event.getStatus() == IngestOutboxStatus.PENDING
                || event.getStatus() == IngestOutboxStatus.FAILED);
    }

    private boolean cleanupObject(String objectKey, UploadQuotaAttemptCandidate reservation) {
        if (objectKey == null || objectKey.isBlank()) {
            return true;
        }
        boolean deleted = minioStorageService.delete(objectKey);
        if (!deleted) {
            log.warn("Retaining stale upload reservation {} because object cleanup failed for {}",
                    reservation.reservationId(), objectKey);
        }
        return deleted;
    }

}
