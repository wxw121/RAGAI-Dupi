package com.dupi.rag.service;

import com.dupi.rag.config.UploadQuotaProperties;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.IngestOutboxEvent;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import com.dupi.rag.repository.UploadQuotaReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadQuotaReconciliationServiceTest {

    @Mock UploadQuotaReservationRepository reservationRepository;
    @Mock DocumentRepository documentRepository;
    @Mock IngestJobRepository ingestJobRepository;
    @Mock IngestOutboxEventRepository outboxRepository;
    @Mock MinioStorageService minioStorageService;
    @Mock UploadIntentCleanupService intentCleanup;

    @Test
    void activeAttemptLeaseIsNotClaimedByStaleReconciler() {
        when(reservationRepository.findStalePendingAttempts(any(Instant.class), anyInt()))
                .thenReturn(List.of());

        assertThat(service().reconcileStalePendingReservations()).isZero();

        verifyNoInteractions(documentRepository, ingestJobRepository, outboxRepository, minioStorageService);
    }

    @Test
    void stalePendingReservationWithoutDocumentIsReleased() {
        UUID attemptId = UUID.randomUUID();
        UploadQuotaReservation reservation = pendingReservation(attemptId);
        when(reservationRepository.findStalePendingAttempts(any(Instant.class), anyInt()))
                .thenReturn(List.of(reservation));
        when(documentRepository.findById(attemptId)).thenReturn(Optional.empty());

        assertThat(service().reconcileStalePendingReservations()).isEqualTo(1);

        assertThat(reservation.getStatus()).isEqualTo(UploadQuotaReservationStatus.RELEASED);
        assertThat(reservation.getAttemptId()).isNull();
        assertThat(reservation.getReleaseReason()).contains("stale upload attempt");
        verify(reservationRepository).save(reservation);
        verifyNoInteractions(minioStorageService);
    }

    @Test
    void scheduledReconcileProcessesStalePendingReservation() {
        UUID attemptId = UUID.randomUUID();
        UploadQuotaReservation reservation = pendingReservation(attemptId);
        when(reservationRepository.findStalePendingAttempts(any(Instant.class), anyInt()))
                .thenReturn(List.of(reservation));
        when(documentRepository.findById(attemptId)).thenReturn(Optional.empty());

        service().reconcileStalePendingReservationsOnSchedule();

        assertThat(reservation.getStatus()).isEqualTo(UploadQuotaReservationStatus.RELEASED);
        verify(reservationRepository).save(reservation);
    }

    @Test
    void durableDocumentJobAndOutboxPromotesStalePendingReservationToCommitted() {
        UUID attemptId = UUID.randomUUID();
        UploadQuotaReservation reservation = pendingReservation(attemptId);
        Document doc = document(reservation.getKbId(), attemptId);
        IngestJob job = job(reservation.getKbId(), attemptId);
        IngestOutboxEvent outbox = outbox(job.getId(), reservation.getKbId(), attemptId, IngestOutboxStatus.PENDING);
        when(reservationRepository.findStalePendingAttempts(any(Instant.class), anyInt()))
                .thenReturn(List.of(reservation));
        when(documentRepository.findById(attemptId)).thenReturn(Optional.of(doc));
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(attemptId)).thenReturn(Optional.of(job));
        when(outboxRepository.findByJobId(job.getId())).thenReturn(List.of(outbox));

        assertThat(service().reconcileStalePendingReservations()).isEqualTo(1);

        assertThat(reservation.getStatus()).isEqualTo(UploadQuotaReservationStatus.COMMITTED);
        assertThat(reservation.getDocId()).isEqualTo(attemptId);
        assertThat(reservation.getAttemptId()).isNull();
        verify(reservationRepository).save(reservation);
        verifyNoInteractions(minioStorageService);
        verify(ingestJobRepository, never()).delete(any());
    }

    @Test
    void partialDocumentAndJobAreCompensatedBeforeReservationRelease() {
        UUID attemptId = UUID.randomUUID();
        UploadQuotaReservation reservation = pendingReservation(attemptId);
        Document doc = document(reservation.getKbId(), attemptId);
        IngestJob job = job(reservation.getKbId(), attemptId);
        when(reservationRepository.findStalePendingAttempts(any(Instant.class), anyInt()))
                .thenReturn(List.of(reservation));
        when(documentRepository.findById(attemptId)).thenReturn(Optional.of(doc));
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(attemptId)).thenReturn(Optional.of(job));
        when(outboxRepository.findByJobId(job.getId())).thenReturn(List.of());
        when(minioStorageService.delete(doc.getObjectKey())).thenReturn(true);

        assertThat(service().reconcileStalePendingReservations()).isEqualTo(1);

        verify(outboxRepository).deleteByJobId(job.getId());
        verify(ingestJobRepository).delete(job);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(doc.getQuotaReservationId()).isNull();
        verify(documentRepository).save(doc);
        assertThat(reservation.getStatus()).isEqualTo(UploadQuotaReservationStatus.RELEASED);
        assertThat(reservation.getDocId()).isNull();
        assertThat(reservation.getAttemptId()).isNull();
        verify(reservationRepository).save(reservation);
    }

    @Test
    void objectCleanupFailureRetainsPendingReservationForRetry() {
        UUID attemptId = UUID.randomUUID();
        UploadQuotaReservation reservation = pendingReservation(attemptId);
        Document doc = document(reservation.getKbId(), attemptId);
        IngestJob job = job(reservation.getKbId(), attemptId);
        when(reservationRepository.findStalePendingAttempts(any(Instant.class), anyInt()))
                .thenReturn(List.of(reservation));
        when(documentRepository.findById(attemptId)).thenReturn(Optional.of(doc));
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(attemptId)).thenReturn(Optional.of(job));
        when(outboxRepository.findByJobId(job.getId())).thenReturn(List.of());
        when(minioStorageService.delete(doc.getObjectKey())).thenReturn(false);

        assertThat(service().reconcileStalePendingReservations()).isZero();

        assertThat(reservation.getStatus()).isEqualTo(UploadQuotaReservationStatus.PENDING);
        assertThat(reservation.getAttemptId()).isEqualTo(attemptId);
        assertThat(reservation.getReleaseReason()).contains("object cleanup failed");
        verify(reservationRepository).save(reservation);
        verify(outboxRepository, never()).deleteByJobId(any());
        verify(ingestJobRepository, never()).delete(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void crashedPreObjectUploadIntentIsCleanedWithoutEverPublishingToRecovery() {
        UUID attemptId = UUID.randomUUID();
        UploadQuotaReservation reservation = pendingReservation(attemptId);
        Document doc = document(reservation.getKbId(), attemptId);
        doc.setStatus(DocumentStatus.UPLOADING);
        IngestJob job = job(reservation.getKbId(), attemptId);
        job.setStatus(IngestJobStatus.UPLOAD_INTENT);
        job.setStage(IngestStage.UPLOAD_PENDING);
        when(reservationRepository.findStalePendingAttempts(any(Instant.class), anyInt()))
                .thenReturn(List.of(reservation));
        when(documentRepository.findById(attemptId)).thenReturn(Optional.of(doc));
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(attemptId)).thenReturn(Optional.of(job));
        UploadIntentCleanupClaim claim = new UploadIntentCleanupClaim(
                reservation.getId(), doc.getId(), job.getId(), doc.getObjectKey(), "upload-cleanup:test");
        when(intentCleanup.claim(eq(reservation), any(Instant.class)))
                .thenReturn(UploadIntentCleanupDecision.claimed(claim));
        when(minioStorageService.delete(doc.getObjectKey())).thenReturn(true);

        assertThat(service().reconcileStalePendingReservations()).isEqualTo(1);

        verify(intentCleanup).complete(claim, "Upload attempt expired after object cleanup");
        verify(outboxRepository, never()).deleteByJobId(job.getId());
        verify(ingestJobRepository, never()).delete(job);
    }

    @Test
    void terminalJobWithStalePendingOutboxIsCompensatedInsteadOfPromoted() {
        UUID attemptId = UUID.randomUUID();
        UploadQuotaReservation reservation = pendingReservation(attemptId);
        Document doc = document(reservation.getKbId(), attemptId);
        doc.setStatus(DocumentStatus.FAILED);
        IngestJob job = job(reservation.getKbId(), attemptId);
        job.setStatus(IngestJobStatus.FAILED);
        job.setStage(IngestStage.FAILED);
        when(reservationRepository.findStalePendingAttempts(any(Instant.class), anyInt()))
                .thenReturn(List.of(reservation));
        when(documentRepository.findById(attemptId)).thenReturn(Optional.of(doc));
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(attemptId)).thenReturn(Optional.of(job));
        when(minioStorageService.delete(doc.getObjectKey())).thenReturn(true);

        assertThat(service().reconcileStalePendingReservations()).isEqualTo(1);

        assertThat(reservation.getStatus()).isEqualTo(UploadQuotaReservationStatus.RELEASED);
        verify(outboxRepository).deleteByJobId(job.getId());
        verify(ingestJobRepository).delete(job);
    }

    @Test
    void expiredReservationOwnedByActiveImportIsNotReconciledOrExposed() {
        UUID attemptId = UUID.randomUUID();
        UploadQuotaReservation reservation = pendingReservation(attemptId);
        Document doc = document(reservation.getKbId(), attemptId);
        doc.setImportJobId(UUID.randomUUID());
        doc.setStatus(DocumentStatus.IMPORTING);
        when(reservationRepository.findStalePendingAttempts(any(Instant.class), anyInt()))
                .thenReturn(List.of(reservation));
        when(documentRepository.findById(attemptId)).thenReturn(Optional.of(doc));

        assertThat(service().reconcileStalePendingReservations()).isZero();

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.IMPORTING);
        assertThat(doc.getQuotaReservationId()).isNotNull();
        assertThat(reservation.getStatus()).isEqualTo(UploadQuotaReservationStatus.PENDING);
        verifyNoInteractions(ingestJobRepository, outboxRepository, minioStorageService);
        verify(documentRepository, never()).save(any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void staleDiscoveryQueryIsBoundedAndDoesNotHoldReservationLocksAcrossCleanup() throws Exception {
        Method method = UploadQuotaReservationRepository.class.getMethod(
                "findStalePendingAttempts", Instant.class, int.class);
        Query query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value().toLowerCase())
                .contains("attempt_expires_at")
                .contains("import_job_id")
                .doesNotContain("for update")
                .contains("limit");
    }

    private UploadQuotaReconciliationService service() {
        UploadQuotaProperties properties = new UploadQuotaProperties();
        properties.setReconciliationBatchSize(10);
        return new UploadQuotaReconciliationService(
                reservationRepository,
                documentRepository,
                ingestJobRepository,
                outboxRepository,
                minioStorageService,
                properties,
                intentCleanup);
    }

    private static UploadQuotaReservation pendingReservation(UUID attemptId) {
        return UploadQuotaReservation.builder()
                .id(UUID.randomUUID())
                .tenantId("default")
                .userId("anonymous")
                .kbId(UUID.randomUUID())
                .docId(null)
                .attemptId(attemptId)
                .idempotencyKey("key-1")
                .fileFingerprint("sha256:file-a")
                .reservedBytes(10L)
                .status(UploadQuotaReservationStatus.PENDING)
                .attemptExpiresAt(Instant.now().minusSeconds(1))
                .createdAt(Instant.now().minusSeconds(120))
                .updatedAt(Instant.now().minusSeconds(60))
                .build();
    }

    private static Document document(UUID kbId, UUID docId) {
        return Document.builder()
                .id(docId)
                .kbId(kbId)
                .fileName("a.md")
                .objectKey(kbId + "/" + docId + "/a.md")
                .mimeType("text/markdown")
                .fileSize(10L)
                .quotaReservationId(UUID.randomUUID())
                .status(DocumentStatus.PENDING)
                .build();
    }

    private static IngestJob job(UUID kbId, UUID docId) {
        return IngestJob.builder()
                .id(UUID.randomUUID())
                .kbId(kbId)
                .docId(docId)
                .status(IngestJobStatus.PENDING)
                .stage(IngestStage.QUEUED)
                .build();
    }

    private static IngestOutboxEvent outbox(UUID jobId, UUID kbId, UUID docId, IngestOutboxStatus status) {
        return IngestOutboxEvent.builder()
                .id(UUID.randomUUID())
                .jobId(jobId)
                .kbId(kbId)
                .docId(docId)
                .objectKey(kbId + "/" + docId + "/a.md")
                .fileName("a.md")
                .mimeType("text/markdown")
                .status(status)
                .build();
    }
}
