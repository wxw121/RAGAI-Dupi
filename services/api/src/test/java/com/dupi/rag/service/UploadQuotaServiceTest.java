package com.dupi.rag.service;

import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.config.UploadQuotaProperties;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import com.dupi.rag.exception.UploadIdempotencyConflictException;
import com.dupi.rag.exception.UploadPayloadTooLargeException;
import com.dupi.rag.exception.UploadQuotaExceededException;
import com.dupi.rag.repository.UploadQuotaReservationRepository;
import com.dupi.rag.repository.UploadWindowEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadQuotaServiceTest {

    @Mock UploadQuotaReservationRepository reservationRepository;
    @Mock UploadWindowEventRepository windowEventRepository;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        SecurityContext.clear();
    }

    @Test
    void reserveForUploadCreatesPendingReservationAndRecordsWindowBytes() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        TenantContext.setTenantId("tenant-a");
        SecurityContext.set("alice", "USER");
        when(reservationRepository.findByTenantIdAndUserIdAndKbIdAndIdempotencyKey(
                "tenant-a", "alice", kbId, "key-1")).thenReturn(Optional.empty());
        when(reservationRepository.sumActiveReservedBytes("tenant-a", "alice")).thenReturn(20L);
        when(reservationRepository.countActiveReservedDocuments("tenant-a", "alice")).thenReturn(2L);
        when(windowEventRepository.sumBytesSince(eq("tenant-a"), eq("alice"), any(Instant.class))).thenReturn(30L);
        when(reservationRepository.save(any(UploadQuotaReservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UploadQuotaReservation reservation = service().reserveForUpload(
                kbId, docId, "key-1", "a.md", "text/markdown", 10L, "sha256:file-a");

        assertThat(reservation.getTenantId()).isEqualTo("tenant-a");
        assertThat(reservation.getUserId()).isEqualTo("alice");
        assertThat(reservation.getDocId()).isNull();
        assertThat(reservation.getAttemptId()).isEqualTo(docId);
        assertThat(reservation.getStatus()).isEqualTo(UploadQuotaReservationStatus.PENDING);
        assertThat(reservation.getFileFingerprint()).isEqualTo("sha256:file-a");
        verify(reservationRepository).lockTenantUserScope("tenant-a", "alice");
        verify(windowEventRepository).save(argThat(event -> event.getBytes() == 10L && event.getIdempotencyKey().equals("key-1")));
    }

    @Test
    void reserveForUploadCountsPendingReservationsAsRetainedCapacity() {
        UUID kbId = UUID.randomUUID();
        when(reservationRepository.sumActiveReservedBytes("default", "anonymous")).thenReturn(96L);

        assertThatThrownBy(() -> service().reserveForUpload(
                kbId, UUID.randomUUID(), null, "pending.md", "text/markdown", 5L, "sha256:pending"))
                .isInstanceOf(UploadQuotaExceededException.class)
                .hasMessageContaining("retained bytes");
    }

    @Test
    void reserveForUploadReturnsCommittedReservationForIdempotentReplay() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UploadQuotaReservation committed = reservation(kbId, docId, "key-1", "a.md:10:text/markdown");
        committed.setStatus(UploadQuotaReservationStatus.COMMITTED);
        when(reservationRepository.findByTenantIdAndUserIdAndKbIdAndIdempotencyKey(
                "default", "anonymous", kbId, "key-1")).thenReturn(Optional.of(committed));

        UploadQuotaReservation result = service().reserveForUpload(
                kbId, UUID.randomUUID(), "key-1", "a.md", "text/markdown", 10L,
                "a.md:10:text/markdown");

        assertThat(result).isSameAs(committed);
        verify(reservationRepository, never()).save(any());
        verify(windowEventRepository, never()).save(any());
    }

    @Test
    void reserveForUploadRejectsMatchingPendingReservationOwnedByAnotherAttempt() {
        UUID kbId = UUID.randomUUID();
        UUID activeAttemptId = UUID.randomUUID();
        UploadQuotaReservation pending = reservation(
                kbId, activeAttemptId, "key-1", "a.md:10:text/markdown");
        when(reservationRepository.findByTenantIdAndUserIdAndKbIdAndIdempotencyKey(
                "default", "anonymous", kbId, "key-1")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service().reserveForUpload(
                kbId, UUID.randomUUID(), "key-1", "a.md", "text/markdown", 10L,
                "a.md:10:text/markdown"))
                .isInstanceOf(UploadIdempotencyConflictException.class)
                .hasMessageContaining("in progress");

        verify(reservationRepository, never()).save(any());
        verify(windowEventRepository, never()).save(any());
    }

    @Test
    void reserveForUploadRejectsConflictingIdempotencyKey() {
        UUID kbId = UUID.randomUUID();
        UploadQuotaReservation existing = reservation(kbId, UUID.randomUUID(), "key-1", "a.md:10:text/markdown");
        when(reservationRepository.findByTenantIdAndUserIdAndKbIdAndIdempotencyKey(
                "default", "anonymous", kbId, "key-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service().reserveForUpload(
                kbId, UUID.randomUUID(), "key-1", "b.md", "text/markdown", 10L,
                "sha256:different"))
                .isInstanceOf(UploadIdempotencyConflictException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void reserveForUploadRejectsRetainedAndWindowQuotaExhaustion() {
        UUID kbId = UUID.randomUUID();
        when(reservationRepository.sumActiveReservedBytes("default", "anonymous")).thenReturn(95L);
        when(reservationRepository.countActiveReservedDocuments("default", "anonymous")).thenReturn(1L);
        assertThatThrownBy(() -> service().reserveForUpload(
                kbId, UUID.randomUUID(), null, "big.md", "text/markdown", 10L, "sha256:big"))
                .isInstanceOf(UploadQuotaExceededException.class)
                .hasMessageContaining("retained bytes");

        when(reservationRepository.sumActiveReservedBytes("default", "anonymous")).thenReturn(0L);
        when(windowEventRepository.sumBytesSince(eq("default"), eq("anonymous"), any(Instant.class))).thenReturn(96L);

        assertThatThrownBy(() -> service().reserveForUpload(
                kbId, UUID.randomUUID(), null, "burst.md", "text/markdown", 10L, "sha256:burst"))
                .isInstanceOf(UploadQuotaExceededException.class)
                .hasMessageContaining("upload window");
    }

    @Test
    void commitMutatesReservationOnlyForOwningAttempt() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UploadQuotaReservation reservation = reservation(kbId, null, "key-1", "a.md:10:text/markdown");
        reservation.setAttemptId(docId);
        Document doc = Document.builder().id(docId).kbId(kbId).fileSize(10L).build();
        lenient().when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        UploadQuotaService service = service();
        service.commit(reservation, doc);

        assertThat(reservation.getStatus()).isEqualTo(UploadQuotaReservationStatus.COMMITTED);
        assertThat(reservation.getDocId()).isEqualTo(docId);
        assertThat(reservation.getAttemptId()).isNull();
        verify(reservationRepository).save(reservation);
    }

    @Test
    void releaseMutatesReservationOnlyForOwningAttempt() {
        UUID attemptId = UUID.randomUUID();
        UploadQuotaReservation reservation = reservation(
                UUID.randomUUID(), null, "key-1", "a.md:10:text/markdown");
        reservation.setAttemptId(attemptId);
        lenient().when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));

        UploadQuotaService service = service();
        service.release(reservation, "failed");

        assertThat(reservation.getStatus()).isEqualTo(UploadQuotaReservationStatus.RELEASED);
        assertThat(reservation.getAttemptId()).isNull();
        verify(reservationRepository).save(reservation);
    }

    @Test
    void staleAttemptCannotCommitReservationOwnedByNewAttempt() {
        UUID reservationId = UUID.randomUUID();
        UUID staleAttemptId = UUID.randomUUID();
        UUID currentAttemptId = UUID.randomUUID();
        UploadQuotaReservation stale = reservation(
                UUID.randomUUID(), null, "key-1", "sha256:file-a");
        stale.setId(reservationId);
        stale.setAttemptId(staleAttemptId);
        UploadQuotaReservation current = reservation(
                stale.getKbId(), null, "key-1", "sha256:file-a");
        current.setId(reservationId);
        current.setAttemptId(currentAttemptId);
        lenient().when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service().commit(
                stale, Document.builder().id(staleAttemptId).kbId(stale.getKbId()).build()))
                .isInstanceOf(UploadIdempotencyConflictException.class)
                .hasMessageContaining("no longer owns");

        assertThat(current.getAttemptId()).isEqualTo(currentAttemptId);
        assertThat(current.getStatus()).isEqualTo(UploadQuotaReservationStatus.PENDING);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void staleAttemptCannotReleaseReservationOwnedByNewAttempt() {
        UUID reservationId = UUID.randomUUID();
        UUID staleAttemptId = UUID.randomUUID();
        UUID currentAttemptId = UUID.randomUUID();
        UploadQuotaReservation stale = reservation(
                UUID.randomUUID(), null, "key-1", "sha256:file-a");
        stale.setId(reservationId);
        stale.setAttemptId(staleAttemptId);
        UploadQuotaReservation current = reservation(
                stale.getKbId(), null, "key-1", "sha256:file-a");
        current.setId(reservationId);
        current.setAttemptId(currentAttemptId);
        lenient().when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(current));

        service().release(stale, "stale failure");

        assertThat(current.getAttemptId()).isEqualTo(currentAttemptId);
        assertThat(current.getStatus()).isEqualTo(UploadQuotaReservationStatus.PENDING);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void owningAttemptCanRollBackAlreadyCommittedReservation() {
        UUID reservationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UploadQuotaReservation attempt = reservation(
                UUID.randomUUID(), null, "key-1", "sha256:file-a");
        attempt.setId(reservationId);
        attempt.setAttemptId(attemptId);
        UploadQuotaReservation committed = reservation(
                attempt.getKbId(), attemptId, "key-1", "sha256:file-a");
        committed.setId(reservationId);
        committed.setAttemptId(null);
        committed.setStatus(UploadQuotaReservationStatus.COMMITTED);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(committed));

        service().release(attempt, "upload compensation");

        assertThat(committed.getStatus()).isEqualTo(UploadQuotaReservationStatus.RELEASED);
        assertThat(committed.getDocId()).isNull();
        assertThat(committed.getReleaseReason()).isEqualTo("upload compensation");
        verify(reservationRepository).save(committed);
    }

    @Test
    void refreshAttemptLeaseExtendsOnlyCurrentPendingAttempt() {
        UUID reservationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UploadQuotaReservation attempt = reservation(UUID.randomUUID(), null, "key-1", "sha256:file-a");
        attempt.setId(reservationId);
        attempt.setAttemptId(attemptId);
        attempt.setAttemptExpiresAt(Instant.now().minusSeconds(1));
        UploadQuotaReservation current = reservation(attempt.getKbId(), null, "key-1", "sha256:file-a");
        current.setId(reservationId);
        current.setAttemptId(attemptId);
        current.setAttemptExpiresAt(Instant.now().minusSeconds(1));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(current));

        service().refreshAttemptLease(attempt);

        assertThat(current.getAttemptExpiresAt()).isAfter(Instant.now());
        assertThat(attempt.getAttemptExpiresAt()).isEqualTo(current.getAttemptExpiresAt());
        verify(reservationRepository).save(current);
    }

    @Test
    void refreshAttemptLeaseSkipsNullAndStaleAttempts() {
        UUID reservationId = UUID.randomUUID();
        UploadQuotaReservation stale = reservation(UUID.randomUUID(), null, "key-1", "sha256:file-a");
        stale.setId(reservationId);
        stale.setAttemptId(UUID.randomUUID());
        UploadQuotaReservation current = reservation(stale.getKbId(), null, "key-1", "sha256:file-a");
        current.setId(reservationId);
        current.setAttemptId(UUID.randomUUID());
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(current));

        UploadQuotaService service = service();
        service.refreshAttemptLease(null);
        service.refreshAttemptLease(reservation(stale.getKbId(), null, "key-2", "sha256:file-b"));
        service.refreshAttemptLease(stale);

        assertThat(current.getStatus()).isEqualTo(UploadQuotaReservationStatus.PENDING);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void snapshotReportsRetainedAndWindowUsage() {
        TenantContext.setTenantId("tenant-a");
        SecurityContext.set("alice", "USER");
        when(reservationRepository.sumActiveReservedBytes("tenant-a", "alice")).thenReturn(40L);
        when(reservationRepository.countActiveReservedDocuments("tenant-a", "alice")).thenReturn(4L);
        when(windowEventRepository.sumBytesSince(eq("tenant-a"), eq("alice"), any(Instant.class))).thenReturn(20L);

        var response = service().snapshot();

        assertThat(response.getTenantId()).isEqualTo("tenant-a");
        assertThat(response.getUserId()).isEqualTo("alice");
        assertThat(response.getRetainedBytesUsed()).isEqualTo(40L);
        assertThat(response.getRetainedDocumentsUsed()).isEqualTo(4L);
        assertThat(response.getWindowBytesUsed()).isEqualTo(20L);
    }

    @Test
    void reserveForUploadReactivatesReleasedIdempotentReservationWithoutChargingWindowTwice() {
        UUID kbId = UUID.randomUUID();
        UUID oldDocId = UUID.randomUUID();
        UUID newDocId = UUID.randomUUID();
        UploadQuotaReservation released = reservation(
                kbId, oldDocId, "key-1", "unknown:7:application/octet-stream");
        released.setStatus(UploadQuotaReservationStatus.RELEASED);
        released.setReleaseReason("previous failure");
        when(reservationRepository.findByTenantIdAndUserIdAndKbIdAndIdempotencyKey(
                "default", "anonymous", kbId, "key-1")).thenReturn(Optional.of(released));
        when(reservationRepository.save(released)).thenReturn(released);

        UploadQuotaReservation result = service().reserveForUpload(
                kbId, newDocId, " key-1 ", null, " ", 7L,
                "unknown:7:application/octet-stream");

        assertThat(result).isSameAs(released);
        assertThat(result.getDocId()).isNull();
        assertThat(result.getStatus()).isEqualTo(UploadQuotaReservationStatus.PENDING);
        assertThat(result.getReleaseReason()).isNull();
        assertThat(result.getReservedBytes()).isEqualTo(7L);
        verify(windowEventRepository, never()).save(any());
    }

    @Test
    void reserveForUploadRechecksQuotaBeforeReactivatingReleasedReservation() {
        UUID kbId = UUID.randomUUID();
        UUID previousAttemptId = UUID.randomUUID();
        UploadQuotaReservation released = reservation(
                kbId, null, "key-1", "sha256:file-a");
        released.setAttemptId(null);
        released.setStatus(UploadQuotaReservationStatus.RELEASED);
        released.setReleaseReason("previous failure");
        when(reservationRepository.findByTenantIdAndUserIdAndKbIdAndIdempotencyKey(
                "default", "anonymous", kbId, "key-1")).thenReturn(Optional.of(released));
        when(reservationRepository.sumActiveReservedBytes("default", "anonymous")).thenReturn(95L);

        assertThatThrownBy(() -> service().reserveForUpload(
                kbId, previousAttemptId, "key-1", "a.md", "text/markdown", 10L,
                "sha256:file-a"))
                .isInstanceOf(UploadQuotaExceededException.class)
                .hasMessageContaining("retained bytes");

        assertThat(released.getStatus()).isEqualTo(UploadQuotaReservationStatus.RELEASED);
        assertThat(released.getAttemptId()).isNull();
        verify(reservationRepository, never()).save(any());
        verify(windowEventRepository, never()).save(any());
    }

    @Test
    void reserveForUploadSkipsLimitsWhenDisabledAndUsesAnonymousFallbacks() {
        UploadQuotaProperties properties = new UploadQuotaProperties();
        properties.setEnabled(false);
        SecurityContext.set(" ", "USER");
        when(reservationRepository.save(any(UploadQuotaReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        UploadQuotaService service = new UploadQuotaService(
                reservationRepository, windowEventRepository, properties);

        UploadQuotaReservation result = service.reserveForUpload(
                UUID.randomUUID(), UUID.randomUUID(), " ", null, null, Long.MAX_VALUE,
                "sha256:disabled");

        assertThat(result.getUserId()).isEqualTo("anonymous");
        assertThat(result.getIdempotencyKey()).isNull();
        assertThat(result.getFileFingerprint()).isEqualTo("sha256:disabled");
        verify(reservationRepository, never()).sumCommittedBytes(any(), any());
    }

    @Test
    void nullMutationsAreNoOpsAndReleaseCommittedLoadsReservation() {
        UploadQuotaService service = service();
        UUID reservationId = UUID.randomUUID();
        UploadQuotaReservation reservation = reservation(
                UUID.randomUUID(), UUID.randomUUID(), "key-1", "a.md:10:text/markdown");
        reservation.setStatus(UploadQuotaReservationStatus.COMMITTED);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        service.commit(null, Document.builder().id(UUID.randomUUID()).build());
        service.commit(reservation, null);
        service.release(null, "ignored");
        service.releaseCommitted(null, "ignored");
        service.releaseCommitted(reservationId, "deleted");

        assertThat(reservation.getStatus()).isEqualTo(UploadQuotaReservationStatus.RELEASED);
        assertThat(reservation.getReleaseReason()).isEqualTo("deleted");
        verify(reservationRepository).save(reservation);
    }

    @Test
    void createCommittedReservationPersistsRecoveryUsageAndHandlesNullDocument() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document doc = Document.builder()
                .id(docId)
                .kbId(kbId)
                .fileName(null)
                .mimeType(null)
                .fileSize(null)
                .build();
        when(reservationRepository.save(any(UploadQuotaReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UploadQuotaService service = service();
        assertThat(service.createCommittedReservation("tenant-a", "admin", kbId, null, "Recovery restore"))
                .isNull();
        UploadQuotaReservation reservation = service.createCommittedReservation(
                "tenant-a", "admin", kbId, doc, "Recovery restore");

        assertThat(reservation.getTenantId()).isEqualTo("tenant-a");
        assertThat(reservation.getUserId()).isEqualTo("admin");
        assertThat(reservation.getKbId()).isEqualTo(kbId);
        assertThat(reservation.getDocId()).isEqualTo(docId);
        assertThat(reservation.getReservedBytes()).isZero();
        assertThat(reservation.getStatus()).isEqualTo(UploadQuotaReservationStatus.COMMITTED);
        assertThat(reservation.getFileFingerprint()).isEqualTo("unknown:0:application/octet-stream");
        assertThat(reservation.getReleaseReason()).isEqualTo("Recovery restore");
    }

    @Test
    void createCommittedReservationDefaultsBlankTenantAndUser() {
        TenantContext.setTenantId("tenant-default");
        Document doc = Document.builder()
                .id(UUID.randomUUID())
                .kbId(UUID.randomUUID())
                .fileName("a.md")
                .mimeType("text/markdown")
                .fileSize(7L)
                .build();
        when(reservationRepository.save(any(UploadQuotaReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UploadQuotaReservation reservation = service().createCommittedReservation(
                " ", null, doc.getKbId(), doc, null);

        assertThat(reservation.getTenantId()).isEqualTo("tenant-default");
        assertThat(reservation.getUserId()).isEqualTo("anonymous");
        assertThat(reservation.getFileFingerprint()).isEqualTo("a.md:7:text/markdown");
    }

    @Test
    void reserveForUploadRejectsSingleFileAndDocumentCountLimits() {
        UUID kbId = UUID.randomUUID();
        assertThatThrownBy(() -> service().reserveForUpload(
                kbId, UUID.randomUUID(), null, "huge.md", "text/markdown", 101L, "sha256:huge"))
                .isInstanceOf(UploadPayloadTooLargeException.class)
                .hasMessageContaining("file exceeds");

        when(reservationRepository.sumActiveReservedBytes("default", "anonymous")).thenReturn(0L);
        when(reservationRepository.countActiveReservedDocuments("default", "anonymous")).thenReturn(10L);
        assertThatThrownBy(() -> service().reserveForUpload(
                kbId, UUID.randomUUID(), null, "one-more.md", "text/markdown", 1L,
                "sha256:one-more"))
                .isInstanceOf(UploadQuotaExceededException.class)
                .hasMessageContaining("document quota");
    }

    @Test
    void snapshotReportsRetryAfterWhenWindowIsExhausted() {
        when(reservationRepository.sumActiveReservedBytes("default", "anonymous")).thenReturn(10L);
        when(reservationRepository.countActiveReservedDocuments("default", "anonymous")).thenReturn(1L);
        when(windowEventRepository.sumBytesSince(eq("default"), eq("anonymous"), any(Instant.class)))
                .thenReturn(100L);

        var response = service().snapshot();

        assertThat(response.getRetainedBytesLimit()).isEqualTo(100L);
        assertThat(response.getRetainedDocumentsLimit()).isEqualTo(10L);
        assertThat(response.getWindowBytesLimit()).isEqualTo(100L);
        assertThat(response.getWindowSeconds()).isEqualTo(60L);
        assertThat(response.getRetryAfter()).isAfter(Instant.now());
    }

    private UploadQuotaService service() {
        UploadQuotaProperties properties = new UploadQuotaProperties();
        properties.setRetainedBytesLimit(100L);
        properties.setRetainedDocumentsLimit(10L);
        properties.setWindowBytesLimit(100L);
        properties.setWindowSeconds(60L);
        return new UploadQuotaService(reservationRepository, windowEventRepository, properties);
    }

    private static UploadQuotaReservation reservation(UUID kbId, UUID docId, String idempotencyKey, String fingerprint) {
        return UploadQuotaReservation.builder()
                .id(UUID.randomUUID())
                .tenantId("default")
                .userId("anonymous")
                .kbId(kbId)
                .docId(docId)
                .idempotencyKey(idempotencyKey)
                .fileFingerprint(fingerprint)
                .reservedBytes(10L)
                .status(UploadQuotaReservationStatus.PENDING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
