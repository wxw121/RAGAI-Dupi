package com.dupi.rag.service;

import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import com.dupi.rag.repository.UploadQuotaReservationRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadQuotaReconciliationPersistenceServiceTest {

    @Test
    void secondReconcilerCannotReleaseReservationAfterIdempotentReopen() {
        UploadQuotaReservationRepository reservations = mock(UploadQuotaReservationRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        UUID reservationId = UUID.randomUUID();
        UUID expiredAttempt = UUID.randomUUID();
        UUID reopenedAttempt = UUID.randomUUID();
        UploadQuotaAttemptCandidate candidate = new UploadQuotaAttemptCandidate(
                reservationId, expiredAttempt, "upload-writer:expired");
        UploadQuotaReservation current = UploadQuotaReservation.builder().id(reservationId)
                .attemptId(expiredAttempt).releaseReason("upload-writer:expired")
                .attemptExpiresAt(Instant.now().minusSeconds(1))
                .status(UploadQuotaReservationStatus.PENDING).build();
        when(reservations.findById(reservationId)).thenReturn(Optional.of(current));
        when(documents.findById(expiredAttempt)).thenReturn(Optional.empty());
        UploadQuotaReconciliationPersistenceService service = service(reservations, documents);

        assertThat(service.releaseMissing(candidate, "expired")).isTrue();
        current.setStatus(UploadQuotaReservationStatus.PENDING);
        current.setAttemptId(reopenedAttempt);
        current.setReleaseReason("upload-writer:new");

        assertThat(service.releaseMissing(candidate, "stale second scheduler")).isFalse();
        assertThat(current.getStatus()).isEqualTo(UploadQuotaReservationStatus.PENDING);
        assertThat(current.getAttemptId()).isEqualTo(reopenedAttempt);
        verify(reservations).save(current);
    }

    @Test
    void staleLegacyCleanupClaimCannotMergeOverNewAttempt() {
        UploadQuotaReservationRepository reservations = mock(UploadQuotaReservationRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        UUID reservationId = UUID.randomUUID();
        UUID oldAttempt = UUID.randomUUID();
        UploadQuotaAttemptCandidate candidate = new UploadQuotaAttemptCandidate(
                reservationId, oldAttempt, "upload-writer:old");
        UploadQuotaReservation reopened = UploadQuotaReservation.builder().id(reservationId)
                .attemptId(UUID.randomUUID()).releaseReason("upload-writer:new")
                .attemptExpiresAt(Instant.now().plusSeconds(30))
                .status(UploadQuotaReservationStatus.PENDING).build();
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reopened));
        when(documents.findByIdForUpdate(oldAttempt)).thenReturn(Optional.of(
                Document.builder().id(oldAttempt).objectKey("objects/old").build()));

        assertThat(service(reservations, documents).claimLegacyCleanup(
                candidate, oldAttempt, null, Instant.now())).isEmpty();

        verify(reservations, never()).save(reopened);
    }

    @Test
    void legacyCleanupCannotClaimDocumentWhoseExternalDeletionAlreadyStarted() {
        UploadQuotaReservationRepository reservations = mock(UploadQuotaReservationRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        UUID reservationId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UploadQuotaAttemptCandidate candidate = new UploadQuotaAttemptCandidate(
                reservationId, documentId, "upload-writer:expired");
        UploadQuotaReservation current = UploadQuotaReservation.builder().id(reservationId)
                .attemptId(documentId).releaseReason("upload-writer:expired")
                .attemptExpiresAt(Instant.now().minusSeconds(1))
                .status(UploadQuotaReservationStatus.PENDING).build();
        Document deleting = Document.builder().id(documentId).objectKey("objects/doc")
                .status(DocumentStatus.DELETING).build();
        when(documents.findByIdForUpdate(documentId)).thenReturn(Optional.of(deleting));

        assertThat(service(reservations, documents).claimLegacyCleanup(
                candidate, documentId, null, Instant.now())).isEmpty();

        verify(reservations, never()).findById(reservationId);
        verify(reservations, never()).save(current);
    }

    private UploadQuotaReconciliationPersistenceService service(
            UploadQuotaReservationRepository reservations, DocumentRepository documents) {
        return new UploadQuotaReconciliationPersistenceService(
                reservations,
                documents,
                mock(IngestJobRepository.class),
                mock(IngestOutboxEventRepository.class),
                60L);
    }
}
