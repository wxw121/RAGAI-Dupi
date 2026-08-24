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
    private static final String WRITER_OWNER_PREFIX = "upload-writer:";
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
        commitOwned(reservation, doc);
    }

    /** Joins upload publication so quota, document, ingest job, and outbox become visible atomically. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void commitInCurrentTransaction(UploadQuotaReservation reservation, Document doc) {
        commitOwned(reservation, doc);
    }

    private void commitOwned(UploadQuotaReservation reservation, Document doc) {
        if (reservation == null || reservation.getId() == null || doc == null || doc.getId() == null) {
            return;
        }
        UploadQuotaReservation current = reservationRepository.findById(reservation.getId())
                .orElseThrow(() -> new UploadIdempotencyConflictException(
                        "Upload attempt no longer owns its quota reservation"));
        if (current.getStatus() != UploadQuotaReservationStatus.PENDING
                || !doc.getId().equals(current.getAttemptId())
                || reservation.getReleaseReason() == null
                || !reservation.getReleaseReason().equals(current.getReleaseReason())
                || current.getAttemptExpiresAt() == null
                || !current.getAttemptExpiresAt().isAfter(Instant.now())) {
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

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseCommittedInCurrentTransaction(UUID reservationId, String reason) {
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
    public UploadAttemptLease acquireWriterLease(UploadQuotaReservation reservation) {
        if (reservation == null || reservation.getId() == null || reservation.getAttemptId() == null) {
            throw new UploadIdempotencyConflictException("Upload writer reservation is missing");
        }
        UploadQuotaReservation current = reservationRepository.findById(reservation.getId())
                .orElseThrow(() -> new UploadIdempotencyConflictException(
                        "Upload attempt no longer owns its quota reservation"));
        if (current.getStatus() != UploadQuotaReservationStatus.PENDING
                || !reservation.getAttemptId().equals(current.getAttemptId())) {
            throw new UploadIdempotencyConflictException(
                    "Upload attempt no longer owns its quota reservation");
        }
        String owner = WRITER_OWNER_PREFIX + UUID.randomUUID();
        current.setReleaseReason(owner);
        current.setAttemptExpiresAt(attemptExpiresAt());
        reservation.setReleaseReason(owner);
        reservation.setAttemptExpiresAt(current.getAttemptExpiresAt());
        reservationRepository.save(current);
        return new UploadAttemptLease(current.getId(), current.getAttemptId(), owner);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renewWriterLease(UploadAttemptLease lease) {
        UploadQuotaReservation current = ownedWriterReservation(lease);
        if (current == null) {
            return false;
        }
        current.setAttemptExpiresAt(attemptExpiresAt());
        reservationRepository.save(current);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean ownsWriterLease(UploadAttemptLease lease) {
        return ownedWriterReservation(lease) != null;
    }

    private UploadQuotaReservation ownedWriterReservation(UploadAttemptLease lease) {
        if (lease == null || lease.reservationId() == null || lease.attemptId() == null
                || lease.ownerToken() == null) {
            return null;
        }
        return reservationRepository.findById(lease.reservationId())
                .filter(current -> current.getStatus() == UploadQuotaReservationStatus.PENDING)
                .filter(current -> lease.attemptId().equals(current.getAttemptId()))
                .filter(current -> lease.ownerToken().equals(current.getReleaseReason()))
                .filter(current -> current.getAttemptExpiresAt() != null
                        && current.getAttemptExpiresAt().isAfter(Instant.now()))
                .orElse(null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimCleanupInCurrentTransaction(
            UploadQuotaAttemptCandidate candidate, UUID documentId, String owner,
            Instant expiresAt, Instant now) {
        if (candidate == null || candidate.reservationId() == null || candidate.attemptId() == null) {
            return false;
        }
        UploadQuotaReservation current = reservationRepository.findById(candidate.reservationId()).orElse(null);
        if (current == null
                || current.getStatus() != UploadQuotaReservationStatus.PENDING
                || !documentId.equals(current.getAttemptId())
                || !candidate.attemptId().equals(current.getAttemptId())
                || !java.util.Objects.equals(candidate.ownerToken(), current.getReleaseReason())
                || current.getAttemptExpiresAt() == null
                || current.getAttemptExpiresAt().isAfter(now)) {
            return false;
        }
        current.setAttemptExpiresAt(expiresAt);
        current.setReleaseReason(owner);
        reservationRepository.save(current);
        return true;
    }


    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseCleanupInCurrentTransaction(
            UploadQuotaReservation reservation, UUID documentId, String owner, String reason) {
        UploadQuotaReservation current = reservationRepository.findById(reservation.getId())
                .orElseThrow(() -> new UploadIdempotencyConflictException("Upload cleanup reservation is missing"));
        if (current.getStatus() != UploadQuotaReservationStatus.PENDING
                || !documentId.equals(current.getAttemptId())
                || !owner.equals(current.getReleaseReason())) {
            throw new UploadIdempotencyConflictException("Upload cleanup claim is stale");
        }
        current.setStatus(UploadQuotaReservationStatus.RELEASED);
        current.setDocId(null);
        current.setAttemptId(null);
        current.setAttemptExpiresAt(null);
        current.setReleaseReason(reason);
        reservationRepository.save(current);
    }

    /** Joins a fenced domain transaction; Markdown import publication must not cross REQUIRES_NEW. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UploadQuotaReservation reserveForImport(UUID reservationId, String tenantId, String userId,
                                                    UUID kbId, UUID docId,
                                                    String idempotencyKey, long fileSize, String fingerprint) {
        requireImportOwner(tenantId, userId);
        UploadQuotaReservation existing = reservationRepository.findById(reservationId).orElse(null);
        if (existing != null) {
            if (!tenantId.equals(existing.getTenantId()) || !userId.equals(existing.getUserId())
                    || !kbId.equals(existing.getKbId()) || !docId.equals(existing.getAttemptId())
                    || !fingerprint.equals(existing.getFileFingerprint())
                    || existing.getReservedBytes() != fileSize) {
                throw new UploadIdempotencyConflictException("Markdown import quota reservation conflicts with its plan");
            }
            return existing;
        }
        if (properties.isEnabled()) reservationRepository.lockTenantUserScope(tenantId, userId);
        enforceLimits(tenantId, userId, fileSize, true);
        UploadQuotaReservation reservation = UploadQuotaReservation.builder().id(reservationId)
                .tenantId(tenantId).userId(userId).kbId(kbId).attemptId(docId)
                .attemptExpiresAt(attemptExpiresAt()).idempotencyKey(idempotencyKey)
                .fileFingerprint(fingerprint).reservedBytes(fileSize)
                .status(UploadQuotaReservationStatus.PENDING).build();
        UploadQuotaReservation saved = reservationRepository.save(reservation);
        recordWindowEvent(tenantId, userId, idempotencyKey, fileSize);
        return saved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void commitForImport(UUID reservationId, String tenantId, String userId, UUID docId) {
        requireImportOwner(tenantId, userId);
        UploadQuotaReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new UploadIdempotencyConflictException("Markdown import quota reservation is missing"));
        if (!tenantId.equals(reservation.getTenantId()) || !userId.equals(reservation.getUserId())) {
            throw new UploadIdempotencyConflictException("Markdown import quota owner conflicts with its operation");
        }
        if (reservation.getStatus() == UploadQuotaReservationStatus.COMMITTED
                && docId.equals(reservation.getDocId())) return;
        if (reservation.getStatus() != UploadQuotaReservationStatus.PENDING
                || !docId.equals(reservation.getAttemptId())) {
            throw new UploadIdempotencyConflictException("Markdown import no longer owns its quota reservation");
        }
        reservation.setStatus(UploadQuotaReservationStatus.COMMITTED);
        reservation.setDocId(docId); reservation.setAttemptId(null); reservation.setAttemptExpiresAt(null);
        reservation.setReleaseReason(null); reservationRepository.save(reservation);
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void verifyImportReservation(UUID reservationId, String tenantId, String userId,
                                        UUID kbId, UUID docId, String idempotencyKey,
                                        long fileSize, String fingerprint) {
        requireImportOwner(tenantId, userId);
        UploadQuotaReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new UploadIdempotencyConflictException("Markdown import quota reservation is missing"));
        if (!tenantId.equals(reservation.getTenantId()) || !userId.equals(reservation.getUserId())
                || !kbId.equals(reservation.getKbId()) || !docId.equals(reservation.getAttemptId())
                || reservation.getDocId() != null || !idempotencyKey.equals(reservation.getIdempotencyKey())
                || !fingerprint.equals(reservation.getFileFingerprint())
                || reservation.getReservedBytes() == null || reservation.getReservedBytes() != fileSize
                || reservation.getStatus() != UploadQuotaReservationStatus.PENDING) {
            throw new UploadIdempotencyConflictException("Markdown import quota reservation conflicts with its plan");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseForImport(UUID reservationId, String tenantId, String userId, UUID docId, String reason) {
        requireImportOwner(tenantId, userId);
        reservationRepository.findById(reservationId).ifPresent(reservation -> {
            if (!tenantId.equals(reservation.getTenantId()) || !userId.equals(reservation.getUserId())) {
                throw new UploadIdempotencyConflictException("Markdown import quota owner conflicts with its operation");
            }
            boolean owns = reservation.getStatus() == UploadQuotaReservationStatus.PENDING
                    && docId.equals(reservation.getAttemptId());
            if (!owns) return;
            reservation.setStatus(UploadQuotaReservationStatus.RELEASED);
            reservation.setAttemptId(null); reservation.setAttemptExpiresAt(null); reservation.setReleaseReason(reason);
            reservationRepository.save(reservation);
        });
    }

    private void requireImportOwner(String tenantId, String userId) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            throw new UploadIdempotencyConflictException("Markdown import quota owner is missing");
        }
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
