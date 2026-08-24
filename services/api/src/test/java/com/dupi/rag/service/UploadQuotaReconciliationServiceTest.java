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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadQuotaReconciliationServiceTest {
    @Mock UploadQuotaReservationRepository reservations;
    @Mock DocumentRepository documents;
    @Mock IngestJobRepository jobs;
    @Mock IngestOutboxEventRepository outbox;
    @Mock MinioStorageService storage;
    @Mock UploadIntentCleanupService intentCleanup;
    @Mock UploadQuotaReconciliationPersistenceService persistence;
    @Mock AbandonedUploadCleanupService abandonedUploads;

    @Test
    void activeWriterLeaseProducesNoDiscoveryCandidate() {
        when(reservations.findStalePendingAttempts(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service().reconcileStalePendingReservations()).isZero();

        verifyNoInteractions(documents, jobs, outbox, storage, persistence);
    }

    @Test
    void missingDocumentUsesFencedShortTransaction() {
        UploadQuotaAttemptCandidate candidate = candidate();
        when(reservations.findStalePendingAttempts(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(documents.findById(candidate.attemptId())).thenReturn(Optional.empty());
        when(persistence.releaseMissing(candidate,
                "Released stale upload attempt without durable document")).thenReturn(true);

        assertThat(service().reconcileStalePendingReservations()).isEqualTo(1);

        verify(persistence).releaseMissing(candidate,
                "Released stale upload attempt without durable document");
        verifyNoInteractions(storage);
    }

    @Test
    void publishedUploadUsesFencedPersistenceBoundary() {
        UploadQuotaAttemptCandidate candidate = candidate();
        Document document = document(candidate.attemptId(), DocumentStatus.PENDING);
        IngestJob job = job(candidate.attemptId(), IngestJobStatus.PENDING, IngestStage.QUEUED);
        when(reservations.findStalePendingAttempts(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(documents.findById(candidate.attemptId())).thenReturn(Optional.of(document));
        when(jobs.findTopByDocIdOrderByCreatedAtDesc(candidate.attemptId())).thenReturn(Optional.of(job));
        when(outbox.findByJobId(job.getId())).thenReturn(List.of(IngestOutboxEvent.builder()
                .jobId(job.getId()).status(IngestOutboxStatus.PENDING).build()));
        when(persistence.commitPublished(candidate, document.getId(), job.getId())).thenReturn(true);

        assertThat(service().reconcileStalePendingReservations()).isEqualTo(1);

        verify(persistence).commitPublished(candidate, document.getId(), job.getId());
        verifyNoInteractions(storage);
    }

    @Test
    void legacyPartialUploadClaimsBeforeStorageAndFinalizesAfterDeletion() {
        UploadQuotaAttemptCandidate candidate = candidate();
        Document document = document(candidate.attemptId(), DocumentStatus.FAILED);
        IngestJob job = job(candidate.attemptId(), IngestJobStatus.FAILED, IngestStage.FAILED);
        UploadLegacyCleanupClaim claim = new UploadLegacyCleanupClaim(
                candidate.reservationId(), candidate.attemptId(), document.getId(), job.getId(),
                document.getObjectKey(), "upload-reconciliation:test");
        stubCandidate(candidate, document, job);
        when(persistence.claimLegacyCleanup(any(), any(), any(), any(Instant.class)))
                .thenReturn(Optional.of(claim));
        when(storage.delete(document.getObjectKey())).thenReturn(true);
        when(persistence.completeLegacyCleanup(claim,
                "Released stale upload attempt after cleanup")).thenReturn(true);

        assertThat(service().reconcileStalePendingReservations()).isEqualTo(1);

        verify(storage).delete(document.getObjectKey());
        verify(persistence).completeLegacyCleanup(claim,
                "Released stale upload attempt after cleanup");
    }

    @Test
    void storageFailureLeavesClaimDurableForExpiryAndReplay() {
        UploadQuotaAttemptCandidate candidate = candidate();
        Document document = document(candidate.attemptId(), DocumentStatus.FAILED);
        IngestJob job = job(candidate.attemptId(), IngestJobStatus.FAILED, IngestStage.FAILED);
        UploadLegacyCleanupClaim claim = new UploadLegacyCleanupClaim(
                candidate.reservationId(), candidate.attemptId(), document.getId(), job.getId(),
                document.getObjectKey(), "upload-reconciliation:test");
        stubCandidate(candidate, document, job);
        when(persistence.claimLegacyCleanup(any(), any(), any(), any(Instant.class)))
                .thenReturn(Optional.of(claim));
        when(storage.delete(document.getObjectKey())).thenReturn(false);

        assertThat(service().reconcileStalePendingReservations()).isZero();

        verify(persistence, never()).completeLegacyCleanup(any(), any());
    }

    @Test
    void uploadIntentUsesJobAndQuotaFenceBeforeObjectCleanup() {
        UploadQuotaAttemptCandidate candidate = candidate();
        Document document = document(candidate.attemptId(), DocumentStatus.UPLOADING);
        IngestJob job = job(candidate.attemptId(), IngestJobStatus.UPLOAD_INTENT, IngestStage.UPLOAD_PENDING);
        UploadIntentCleanupClaim claim = new UploadIntentCleanupClaim(
                candidate.reservationId(), document.getId(), job.getId(), document.getObjectKey(),
                "upload-cleanup:test");
        stubCandidate(candidate, document, job);
        when(intentCleanup.claim(any(), any(Instant.class)))
                .thenReturn(UploadIntentCleanupDecision.claimed(claim));
        when(storage.delete(document.getObjectKey())).thenReturn(true);

        assertThat(service().reconcileStalePendingReservations()).isEqualTo(1);

        verify(intentCleanup).complete(claim, "Upload attempt expired after object cleanup");
    }

    @Test
    void importOwnedReservationRemainsOutsideOrdinaryReconciliation() {
        UploadQuotaAttemptCandidate candidate = candidate();
        Document document = document(candidate.attemptId(), DocumentStatus.IMPORTING);
        document.setImportJobId(UUID.randomUUID());
        when(reservations.findStalePendingAttempts(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(documents.findById(candidate.attemptId())).thenReturn(Optional.of(document));

        assertThat(service().reconcileStalePendingReservations()).isZero();

        verifyNoInteractions(jobs, outbox, storage, persistence);
    }

    @Test
    void scheduledPassAlsoReplaysAbandonedWriterObjects() {
        when(reservations.findStalePendingAttempts(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
        when(abandonedUploads.cleanup(10)).thenReturn(1);

        service().reconcileStalePendingReservationsOnSchedule();

        verify(abandonedUploads).cleanup(10);
    }

    @Test
    void discoveryReturnsImmutableFenceWithoutHoldingRowsAcrossIo() throws Exception {
        Method method = UploadQuotaReservationRepository.class.getMethod(
                "findStalePendingAttempts", Instant.class, Pageable.class);
        Query query = method.getAnnotation(Query.class);

        assertThat(method.getGenericReturnType().getTypeName())
                .contains(UploadQuotaAttemptCandidate.class.getName());
        assertThat(query).isNotNull();
        assertThat(query.value().toLowerCase())
                .contains("attemptid")
                .contains("releasereason")
                .doesNotContain("for update");
    }

    private UploadQuotaReconciliationService service() {
        UploadQuotaProperties properties = new UploadQuotaProperties();
        properties.setReconciliationBatchSize(10);
        return new UploadQuotaReconciliationService(
                reservations, documents, jobs, outbox, storage, properties,
                intentCleanup, persistence, abandonedUploads);
    }

    private void stubCandidate(
            UploadQuotaAttemptCandidate candidate, Document document, IngestJob job) {
        when(reservations.findStalePendingAttempts(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(documents.findById(candidate.attemptId())).thenReturn(Optional.of(document));
        when(jobs.findTopByDocIdOrderByCreatedAtDesc(candidate.attemptId())).thenReturn(Optional.of(job));
    }

    private UploadQuotaAttemptCandidate candidate() {
        return new UploadQuotaAttemptCandidate(
                UUID.randomUUID(), UUID.randomUUID(), "upload-writer:expired");
    }

    private Document document(UUID id, DocumentStatus status) {
        return Document.builder().id(id).kbId(UUID.randomUUID()).objectKey("objects/" + id)
                .status(status).build();
    }

    private IngestJob job(UUID documentId, IngestJobStatus status, IngestStage stage) {
        return IngestJob.builder().id(UUID.randomUUID()).docId(documentId)
                .status(status).stage(stage).build();
    }
}
