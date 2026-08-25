package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UploadIntentCleanupServiceTest {

    @Test
    void claimPersistsNonRunnableCleanupLeaseBeforeExternalObjectDeletion() {
        Fixture fixture = new Fixture();
        Instant now = Instant.now();
        UploadQuotaAttemptCandidate candidate = fixture.candidate();
        UploadIntentCleanupDecision decision = fixture.service.claim(candidate, now);

        assertThat(decision.state()).isEqualTo(UploadIntentCleanupDecision.State.CLAIMED);
        assertThat(fixture.job.getStatus()).isEqualTo(IngestJobStatus.UPLOAD_INTENT);
        assertThat(fixture.job.getStage()).isEqualTo(IngestStage.UPLOAD_CLEANUP);
        assertThat(fixture.job.getClaimedBy()).startsWith("upload-cleanup:");
        assertThat(fixture.job.getLeaseExpiresAt()).isAfter(Instant.now());
        var order = inOrder(fixture.jobs, fixture.documents, fixture.quota);
        order.verify(fixture.jobs).findByIdForUpdate(fixture.job.getId());
        order.verify(fixture.documents).findByIdForUpdate(fixture.document.getId());
        order.verify(fixture.quota).claimCleanupInCurrentTransaction(
                candidate, fixture.document.getId(), fixture.job.getClaimedBy(),
                fixture.job.getLeaseExpiresAt(), now);
        order.verify(fixture.jobs).saveAndFlush(fixture.job);
    }

    @Test
    void completeSerializesOnJobLockThenPersistsCleanupTruth() {
        Fixture fixture = new Fixture();
        UploadIntentCleanupDecision decision = fixture.service.claim(fixture.candidate(), Instant.now());
        reset(fixture.jobs, fixture.documents, fixture.quota, fixture.outbox);
        when(fixture.jobs.findByIdForUpdate(fixture.job.getId())).thenReturn(Optional.of(fixture.job));
        when(fixture.documents.findByIdForUpdate(fixture.document.getId()))
                .thenReturn(Optional.of(fixture.document));

        fixture.service.complete(decision.claim(), "Upload attempt expired after object cleanup");

        assertThat(fixture.job.getStatus()).isEqualTo(IngestJobStatus.FAILED);
        assertThat(fixture.job.getStage()).isEqualTo(IngestStage.FAILED);
        assertThat(fixture.job.getClaimedBy()).isNull();
        assertThat(fixture.document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(fixture.document.getQuotaReservationId()).isNull();
        var order = inOrder(fixture.jobs, fixture.documents, fixture.outbox, fixture.quota);
        order.verify(fixture.jobs).findByIdForUpdate(fixture.job.getId());
        order.verify(fixture.documents).findByIdForUpdate(fixture.document.getId());
        order.verify(fixture.outbox).cancelPendingForJob(fixture.job.getId(), "Upload attempt expired");
        order.verify(fixture.quota).releaseCleanupInCurrentTransaction(
                argThat(reservation -> fixture.reservation.getId().equals(reservation.getId())),
                eq(fixture.document.getId()), eq(decision.claim().owner()),
                eq("Upload attempt expired after object cleanup"));
        order.verify(fixture.documents).save(fixture.document);
        order.verify(fixture.jobs).saveAndFlush(fixture.job);
    }

    @Test
    void publishedOutboxWinsCleanupClaimWithoutChangingRunnableState() {
        Fixture fixture = new Fixture();
        fixture.document.setStatus(DocumentStatus.PENDING);
        fixture.job.setStatus(IngestJobStatus.PENDING);
        fixture.job.setStage(IngestStage.QUEUED);
        when(fixture.outbox.hasDurableRecord(fixture.job.getId())).thenReturn(true);

        UploadIntentCleanupDecision decision = fixture.service.claim(fixture.candidate(), Instant.now());

        assertThat(decision.state()).isEqualTo(UploadIntentCleanupDecision.State.PUBLISHED);
        assertThat(fixture.job.getStatus()).isEqualTo(IngestJobStatus.PENDING);
        assertThat(fixture.document.getStatus()).isEqualTo(DocumentStatus.PENDING);
        verify(fixture.quota, never()).claimCleanupInCurrentTransaction(
                any(), any(), anyString(), any(), any());
        verify(fixture.jobs, never()).saveAndFlush(any());
    }

    @Test
    void activeCleanupLeaseIsNotClaimedTwice() {
        Fixture fixture = new Fixture();
        fixture.job.setStage(IngestStage.UPLOAD_CLEANUP);
        fixture.job.setClaimedBy("upload-cleanup:first");
        fixture.job.setLeaseExpiresAt(Instant.now().plusSeconds(30));

        UploadIntentCleanupDecision decision = fixture.service.claim(fixture.candidate(), Instant.now());

        assertThat(decision.state()).isEqualTo(UploadIntentCleanupDecision.State.SKIPPED);
        assertThat(fixture.job.getClaimedBy()).isEqualTo("upload-cleanup:first");
        verify(fixture.quota, never()).claimCleanupInCurrentTransaction(
                any(), any(), anyString(), any(), any());
    }

    @Test
    void crashedCleanupIsReclaimedAfterLeaseExpiry() {
        Fixture fixture = new Fixture();
        fixture.job.setStage(IngestStage.UPLOAD_CLEANUP);
        fixture.job.setClaimedBy("upload-cleanup:crashed");
        fixture.job.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        fixture.reservation.setReleaseReason("upload-cleanup:crashed");

        UploadIntentCleanupDecision decision = fixture.service.claim(fixture.candidate(), Instant.now());

        assertThat(decision.state()).isEqualTo(UploadIntentCleanupDecision.State.CLAIMED);
        assertThat(decision.claim().owner()).isNotEqualTo("upload-cleanup:crashed");
        assertThat(fixture.job.getLeaseExpiresAt()).isAfter(Instant.now());
        verify(fixture.jobs).saveAndFlush(fixture.job);
    }

    private static class Fixture {
        final DocumentRepository documents = mock(DocumentRepository.class);
        final IngestJobRepository jobs = mock(IngestJobRepository.class);
        final UploadQuotaService quota = mock(UploadQuotaService.class);
        final IngestOutboxService outbox = mock(IngestOutboxService.class);
        final UUID kbId = UUID.randomUUID();
        final Document document = Document.builder().id(UUID.randomUUID()).kbId(kbId)
                .fileName("a.md").objectKey(kbId + "/a.md").status(DocumentStatus.UPLOADING)
                .quotaReservationId(UUID.randomUUID()).build();
        final IngestJob job = IngestJob.builder().id(UUID.randomUUID()).kbId(kbId).docId(document.getId())
                .status(IngestJobStatus.UPLOAD_INTENT).stage(IngestStage.UPLOAD_PENDING).build();
        final UploadQuotaReservation reservation = UploadQuotaReservation.builder()
                .id(document.getQuotaReservationId()).kbId(kbId).attemptId(document.getId())
                .status(UploadQuotaReservationStatus.PENDING).build();
        final UploadIntentCleanupService service = new UploadIntentCleanupService(documents, jobs, quota, outbox, 60);

        Fixture() {
            when(documents.findById(document.getId())).thenReturn(Optional.of(document));
            when(documents.findByIdForUpdate(document.getId())).thenReturn(Optional.of(document));
            when(jobs.findTopByDocIdOrderByCreatedAtDesc(document.getId())).thenReturn(Optional.of(job));
            when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
            when(quota.claimCleanupInCurrentTransaction(
                    any(), eq(document.getId()), anyString(), any(), any())).thenReturn(true);
        }

        UploadQuotaAttemptCandidate candidate() {
            return new UploadQuotaAttemptCandidate(
                    reservation.getId(), reservation.getAttemptId(), reservation.getReleaseReason());
        }
    }
}
