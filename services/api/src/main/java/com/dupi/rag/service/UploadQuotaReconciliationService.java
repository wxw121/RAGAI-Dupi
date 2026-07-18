package com.dupi.rag.service;

import com.dupi.rag.config.UploadQuotaProperties;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.IngestOutboxEvent;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import com.dupi.rag.repository.UploadQuotaReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Scheduled(cron = "${dupi.upload-quota.reconciliation-cron:0 */5 * * * *}")
    @Transactional
    public void reconcileStalePendingReservationsOnSchedule() {
        int reconciled = reconcileStalePendingReservationsInternal();
        if (reconciled > 0) {
            log.info("Reconciled {} stale upload quota reservation(s)", reconciled);
        }
    }

    @Transactional
    public int reconcileStalePendingReservations() {
        return reconcileStalePendingReservationsInternal();
    }

    private int reconcileStalePendingReservationsInternal() {
        int limit = Math.max(1, properties.getReconciliationBatchSize());
        List<UploadQuotaReservation> reservations = reservationRepository
                .findStalePendingAttemptsForUpdate(Instant.now(), limit);
        int reconciled = 0;
        for (UploadQuotaReservation reservation : reservations) {
            if (reservation.getAttemptId() == null
                    || reservation.getStatus() != UploadQuotaReservationStatus.PENDING) {
                continue;
            }
            if (reconcile(reservation)) {
                reconciled++;
            }
        }
        return reconciled;
    }

    private boolean reconcile(UploadQuotaReservation reservation) {
        Optional<Document> maybeDoc = documentRepository.findById(reservation.getAttemptId());
        if (maybeDoc.isEmpty()) {
            release(reservation, "Released stale upload attempt without durable document");
            return true;
        }

        Document doc = maybeDoc.get();
        IngestJob job = ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(doc.getId()).orElse(null);
        if (job != null && hasDurableOutbox(job)) {
            commit(reservation, doc);
            return true;
        }

        if (!cleanupObject(doc, reservation)) {
            reservation.setReleaseReason("stale upload attempt object cleanup failed; retaining reservation");
            reservationRepository.save(reservation);
            return false;
        }

        if (job != null) {
            outboxRepository.deleteByJobId(job.getId());
            ingestJobRepository.delete(job);
        }
        doc.setStatus(DocumentStatus.FAILED);
        doc.setErrorMessage("Upload attempt expired before ingest dispatch became durable");
        doc.setQuotaReservationId(null);
        documentRepository.save(doc);
        release(reservation, "Released stale upload attempt after cleanup");
        return true;
    }

    private boolean hasDurableOutbox(IngestJob job) {
        List<IngestOutboxEvent> outboxEvents = outboxRepository.findByJobId(job.getId());
        return !outboxEvents.isEmpty();
    }

    private boolean cleanupObject(Document doc, UploadQuotaReservation reservation) {
        String objectKey = doc.getObjectKey();
        if (objectKey == null || objectKey.isBlank()) {
            return true;
        }
        boolean deleted = minioStorageService.delete(objectKey);
        if (!deleted) {
            log.warn("Retaining stale upload reservation {} because object cleanup failed for {}",
                    reservation.getId(), objectKey);
        }
        return deleted;
    }

    private void commit(UploadQuotaReservation reservation, Document doc) {
        reservation.setDocId(doc.getId());
        reservation.setAttemptId(null);
        reservation.setAttemptExpiresAt(null);
        reservation.setStatus(UploadQuotaReservationStatus.COMMITTED);
        reservation.setReleaseReason(null);
        reservationRepository.save(reservation);
    }

    private void release(UploadQuotaReservation reservation, String reason) {
        reservation.setDocId(null);
        reservation.setAttemptId(null);
        reservation.setAttemptExpiresAt(null);
        reservation.setStatus(UploadQuotaReservationStatus.RELEASED);
        reservation.setReleaseReason(reason);
        reservationRepository.save(reservation);
    }
}
