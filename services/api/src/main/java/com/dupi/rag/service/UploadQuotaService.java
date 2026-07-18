package com.dupi.rag.service;

import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.config.UploadQuotaProperties;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.entity.UploadWindowEvent;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import com.dupi.rag.dto.UploadQuotaResponse;
import com.dupi.rag.exception.UploadIdempotencyConflictException;
import com.dupi.rag.exception.UploadPayloadTooLargeException;
import com.dupi.rag.exception.UploadQuotaExceededException;
import com.dupi.rag.repository.UploadQuotaReservationRepository;
import com.dupi.rag.repository.UploadWindowEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UploadQuotaService {

    private static final String ANONYMOUS_USER = "anonymous";
    public static final String LEGACY_MIGRATION_USER = "legacy-migration";

    private final UploadQuotaReservationRepository reservationRepository;
    private final UploadWindowEventRepository windowEventRepository;
    private final UploadQuotaProperties properties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UploadQuotaReservation reserveForUpload(
            UUID kbId,
            UUID docId,
            String idempotencyKey,
            String fileName,
            String mimeType,
            long fileSize,
            String fileFingerprint
    ) {
        String tenantId = currentTenantId();
        String userId = currentUserId();
        String key = normalize(idempotencyKey);
        String fingerprint = normalize(fileFingerprint);
        if (fingerprint == null) {
            throw new IllegalArgumentException("File fingerprint is required");
        }
        if (properties.isEnabled() || key != null) {
            reservationRepository.lockTenantUserScope(tenantId, userId);
        }

        if (key != null) {
            var existing = reservationRepository
                    .findByTenantIdAndUserIdAndKbIdAndIdempotencyKey(tenantId, userId, kbId, key);
            if (existing.isPresent()) {
                UploadQuotaReservation reservation = existing.get();
                if (!fingerprint.equals(reservation.getFileFingerprint())) {
                    throw new UploadIdempotencyConflictException("Idempotency-Key already used for a different file");
                }
                if (reservation.getStatus() == UploadQuotaReservationStatus.COMMITTED && reservation.getDocId() != null) {
                    return reservation;
                }
                UUID activeAttemptId = reservation.getAttemptId() != null
                        ? reservation.getAttemptId()
                        : reservation.getDocId();
                if (reservation.getStatus() == UploadQuotaReservationStatus.PENDING
                        && activeAttemptId != null
                        && !activeAttemptId.equals(docId)) {
                    throw new UploadIdempotencyConflictException(
                            "Upload for this Idempotency-Key is already in progress");
                }
                if (reservation.getStatus() == UploadQuotaReservationStatus.PENDING) {
                    return reservation;
                }
                enforceLimits(tenantId, userId, fileSize, false);
                reservation.setDocId(null);
                reservation.setAttemptId(docId);
                reservation.setAttemptExpiresAt(attemptExpiresAt());
                reservation.setStatus(UploadQuotaReservationStatus.PENDING);
                reservation.setReleaseReason(null);
                reservation.setReservedBytes(fileSize);
                UploadQuotaReservation saved = reservationRepository.save(reservation);
                return saved;
            }
        }

        enforceLimits(tenantId, userId, fileSize, true);

        UploadQuotaReservation reservation = UploadQuotaReservation.builder()
                .tenantId(tenantId)
                .userId(userId)
                .kbId(kbId)
                .docId(null)
                .attemptId(docId)
                .attemptExpiresAt(attemptExpiresAt())
                .idempotencyKey(key)
                .fileFingerprint(fingerprint)
                .reservedBytes(fileSize)
                .status(UploadQuotaReservationStatus.PENDING)
                .build();
        UploadQuotaReservation saved = reservationRepository.save(reservation);
        recordWindowEvent(tenantId, userId, key, fileSize);
        return saved;
    }

    private void recordWindowEvent(String tenantId, String userId, String key, long fileSize) {
        windowEventRepository.save(UploadWindowEvent.builder()
                .tenantId(tenantId)
                .userId(userId)
                .bytes(fileSize)
                .idempotencyKey(key)
                .acceptedAt(Instant.now())
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commit(UploadQuotaReservation reservation, Document doc) {
        if (reservation == null || reservation.getId() == null || doc == null || doc.getId() == null) {
            return;
        }
        UploadQuotaReservation current = reservationRepository.findById(reservation.getId())
                .orElseThrow(() -> new UploadIdempotencyConflictException(
                        "Upload attempt no longer owns its quota reservation"));
        if (current.getStatus() != UploadQuotaReservationStatus.PENDING
                || !doc.getId().equals(current.getAttemptId())) {
            throw new UploadIdempotencyConflictException(
                    "Upload attempt no longer owns its quota reservation");
        }
        current.setDocId(doc.getId());
        current.setAttemptId(null);
        current.setAttemptExpiresAt(null);
        current.setStatus(UploadQuotaReservationStatus.COMMITTED);
        current.setReleaseReason(null);
        reservationRepository.save(current);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UploadQuotaReservation reservation, String reason) {
        if (reservation == null || reservation.getId() == null || reservation.getAttemptId() == null) {
            return;
        }
        UUID attemptId = reservation.getAttemptId();
        reservationRepository.findById(reservation.getId()).ifPresent(current -> {
            boolean ownsPending = current.getStatus() == UploadQuotaReservationStatus.PENDING
                    && attemptId.equals(current.getAttemptId());
            boolean ownsCommitted = current.getStatus() == UploadQuotaReservationStatus.COMMITTED
                    && attemptId.equals(current.getDocId());
            if (!ownsPending && !ownsCommitted) {
                return;
            }
            current.setStatus(UploadQuotaReservationStatus.RELEASED);
            current.setDocId(null);
            current.setAttemptId(null);
            current.setAttemptExpiresAt(null);
            current.setReleaseReason(reason);
            reservationRepository.save(current);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseCommitted(UUID reservationId, String reason) {
        if (reservationId == null) {
            return;
        }
        reservationRepository.findById(reservationId).ifPresent(reservation -> {
            if (reservation.getStatus() != UploadQuotaReservationStatus.COMMITTED) {
                return;
            }
            reservation.setStatus(UploadQuotaReservationStatus.RELEASED);
            reservation.setAttemptId(null);
            reservation.setAttemptExpiresAt(null);
            reservation.setReleaseReason(reason);
            reservationRepository.save(reservation);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshAttemptLease(UploadQuotaReservation reservation) {
        if (reservation == null || reservation.getId() == null || reservation.getAttemptId() == null) {
            return;
        }
        UUID attemptId = reservation.getAttemptId();
        reservationRepository.findById(reservation.getId()).ifPresent(current -> {
            if (current.getStatus() != UploadQuotaReservationStatus.PENDING
                    || !attemptId.equals(current.getAttemptId())) {
                return;
            }
            current.setAttemptExpiresAt(attemptExpiresAt());
            reservation.setAttemptExpiresAt(current.getAttemptExpiresAt());
            reservationRepository.save(current);
        });
    }

    @Transactional(readOnly = true)
    public UploadQuotaResponse snapshot() {
        String tenantId = currentTenantId();
        String userId = currentUserId();
        Instant since = Instant.now().minusSeconds(Math.max(1L, properties.getWindowSeconds()));
        long retainedBytes = reservationRepository.sumActiveReservedBytes(tenantId, userId);
        long retainedDocuments = reservationRepository.countActiveReservedDocuments(tenantId, userId);
        long windowBytes = windowEventRepository.sumBytesSince(tenantId, userId, since);
        Instant retryAfter = windowBytes >= properties.getWindowBytesLimit()
                ? Instant.now().plusSeconds(properties.getWindowSeconds())
                : null;
        return UploadQuotaResponse.builder()
                .tenantId(tenantId)
                .userId(userId)
                .retainedBytesUsed(retainedBytes)
                .retainedBytesLimit(properties.getRetainedBytesLimit())
                .retainedDocumentsUsed(retainedDocuments)
                .retainedDocumentsLimit(properties.getRetainedDocumentsLimit())
                .windowBytesUsed(windowBytes)
                .windowBytesLimit(properties.getWindowBytesLimit())
                .windowSeconds(properties.getWindowSeconds())
                .retryAfter(retryAfter)
                .build();
    }

    private void enforceLimits(String tenantId, String userId, long fileSize, boolean chargeWindowBytes) {
        if (!properties.isEnabled()) {
            return;
        }
        if (fileSize > properties.getRetainedBytesLimit()) {
            throw new UploadPayloadTooLargeException("file exceeds retained bytes limit");
        }
        long retainedBytes = reservationRepository.sumActiveReservedBytes(tenantId, userId);
        if (retainedBytes + fileSize > properties.getRetainedBytesLimit()) {
            throw new UploadQuotaExceededException("upload retained bytes quota exceeded");
        }
        long retainedDocuments = reservationRepository.countActiveReservedDocuments(tenantId, userId);
        if (retainedDocuments + 1 > properties.getRetainedDocumentsLimit()) {
            throw new UploadQuotaExceededException("upload retained document quota exceeded");
        }
        if (chargeWindowBytes) {
            Instant since = Instant.now().minusSeconds(Math.max(1L, properties.getWindowSeconds()));
            long windowBytes = windowEventRepository.sumBytesSince(tenantId, userId, since);
            if (windowBytes + fileSize > properties.getWindowBytesLimit()) {
                throw new UploadQuotaExceededException("upload window bytes quota exceeded", properties.getWindowSeconds());
            }
        }
    }

    private Instant attemptExpiresAt() {
        return Instant.now().plusSeconds(Math.max(1L, properties.getAttemptLeaseSeconds()));
    }

    private String currentTenantId() {
        return TenantContext.getTenantId();
    }

    private String currentUserId() {
        String principal = SecurityContext.getPrincipal();
        return principal == null || principal.isBlank() ? ANONYMOUS_USER : principal;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String fingerprint(String fileName, String mimeType, long fileSize) {
        String name = fileName == null || fileName.isBlank() ? "unknown" : fileName;
        String type = mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
        return name + ":" + fileSize + ":" + type;
    }

    @Transactional
    public UploadQuotaReservation createCommittedReservation(
            String tenantId,
            String userId,
            UUID kbId,
            Document doc,
            String reason
    ) {
        if (doc == null) {
            return null;
        }
        long fileSize = doc.getFileSize() == null ? 0L : doc.getFileSize();
        UploadQuotaReservation reservation = UploadQuotaReservation.builder()
                .tenantId(tenantId == null || tenantId.isBlank() ? currentTenantId() : tenantId)
                .userId(userId == null || userId.isBlank() ? ANONYMOUS_USER : userId)
                .kbId(kbId)
                .docId(doc.getId())
                .idempotencyKey(null)
                .fileFingerprint(fingerprint(doc.getFileName(), doc.getMimeType(), fileSize))
                .reservedBytes(fileSize)
                .status(UploadQuotaReservationStatus.COMMITTED)
                .releaseReason(reason)
                .build();
        return reservationRepository.save(reservation);
    }
}
