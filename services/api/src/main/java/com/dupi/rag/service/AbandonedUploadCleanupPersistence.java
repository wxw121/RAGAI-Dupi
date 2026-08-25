package com.dupi.rag.service;

import com.dupi.rag.domain.entity.DocumentTombstone;
import com.dupi.rag.repository.DocumentTombstoneRepository;
import com.dupi.rag.repository.UploadQuotaReservationRepository;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class AbandonedUploadCleanupPersistence {
    private final DocumentTombstoneRepository tombstones;
    private final UploadQuotaReservationRepository reservations;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean shouldReplayArmed(UUID documentId, String objectKey) {
        if (documentId == null || objectKey == null || objectKey.isBlank()) {
            return false;
        }
        var reservation = reservations.findByAttemptIdOrDocIdForUpdate(documentId).orElse(null);
        if (reservation == null) {
            return true;
        }
        if (reservation.getStatus() == UploadQuotaReservationStatus.COMMITTED
                && documentId.equals(reservation.getDocId())) {
            return false;
        }
        return reservation.getStatus() != UploadQuotaReservationStatus.PENDING
                || !documentId.equals(reservation.getAttemptId())
                || reservation.getReleaseReason() == null
                || !reservation.getReleaseReason().startsWith("upload-writer:")
                || reservation.getAttemptExpiresAt() == null
                || !reservation.getAttemptExpiresAt().isAfter(java.time.Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void complete(UUID documentId, String objectKey) {
        DocumentTombstone tombstone = tombstones.findByDocId(documentId).orElse(null);
        if (tombstone == null
                || !("UPLOAD_ABANDONED".equals(tombstone.getReason())
                || DocumentTombstoneService.UPLOAD_WRITE_ARMED.equals(tombstone.getReason()))
                || !java.util.Objects.equals(objectKey, tombstone.getObjectKey())) {
            return;
        }
        tombstone.setReason("UPLOAD_ABANDONED_CLEANED");
        tombstones.save(tombstone);
    }
}
