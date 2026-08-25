package com.dupi.rag.service;

import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.entity.DocumentTombstone;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import com.dupi.rag.repository.DocumentTombstoneRepository;
import com.dupi.rag.repository.UploadQuotaReservationRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class AbandonedUploadCleanupPersistenceTest {

    @Test
    void reclaimedArmedWriteBecomesTerminalOnlyAfterObjectDeletionCompletes() {
        DocumentTombstoneRepository tombstones = mock(DocumentTombstoneRepository.class);
        UploadQuotaReservationRepository reservations = mock(UploadQuotaReservationRepository.class);
        UUID documentId = UUID.randomUUID();
        DocumentTombstone armed = DocumentTombstone.builder().docId(documentId)
                .objectKey("objects/crashed.md").reason("UPLOAD_WRITE_ARMED").build();
        when(tombstones.findByDocId(documentId)).thenReturn(Optional.of(armed));

        new AbandonedUploadCleanupPersistence(tombstones, reservations)
                .complete(documentId, armed.getObjectKey());

        assertThat(armed.getReason()).isEqualTo("UPLOAD_ABANDONED_CLEANED");
        verify(tombstones).save(armed);
    }

    @Test
    void armedReplayWaitsForLiveWriterButRunsAfterOwnershipIsGone() {
        DocumentTombstoneRepository tombstones = mock(DocumentTombstoneRepository.class);
        UploadQuotaReservationRepository reservations = mock(UploadQuotaReservationRepository.class);
        UUID documentId = UUID.randomUUID();
        UploadQuotaReservation live = UploadQuotaReservation.builder()
                .attemptId(documentId).status(UploadQuotaReservationStatus.PENDING)
                .releaseReason("upload-writer:live")
                .attemptExpiresAt(Instant.now().plusSeconds(30)).build();
        when(reservations.findByAttemptIdOrDocIdForUpdate(documentId))
                .thenReturn(Optional.of(live), Optional.empty());
        AbandonedUploadCleanupPersistence persistence =
                new AbandonedUploadCleanupPersistence(tombstones, reservations);

        assertThat(persistence.shouldReplayArmed(documentId, "objects/late.md")).isFalse();
        assertThat(persistence.shouldReplayArmed(documentId, "objects/late.md")).isTrue();
    }

    @Test
    void committedPublicationWinsRaceAgainstStaleArmedSchedulerSnapshot() {
        DocumentTombstoneRepository tombstones = mock(DocumentTombstoneRepository.class);
        UploadQuotaReservationRepository reservations = mock(UploadQuotaReservationRepository.class);
        UUID documentId = UUID.randomUUID();
        UploadQuotaReservation committed = UploadQuotaReservation.builder()
                .docId(documentId).status(UploadQuotaReservationStatus.COMMITTED).build();
        when(reservations.findByAttemptIdOrDocIdForUpdate(documentId))
                .thenReturn(Optional.of(committed));

        assertThat(new AbandonedUploadCleanupPersistence(tombstones, reservations)
                .shouldReplayArmed(documentId, "objects/published.md")).isFalse();
    }
}
