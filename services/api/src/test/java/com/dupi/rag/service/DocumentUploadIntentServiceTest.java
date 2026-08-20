package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DocumentUploadIntentServiceTest {

    @Test
    void deletionFirstRejectsIntentBeforeAnyUploadMetadataIsCreated() {
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        IngestJobRepository jobs = mock(IngestJobRepository.class);
        UUID kbId = UUID.randomUUID();
        KnowledgeBase deleting = KnowledgeBase.builder().id(kbId).tenantId("tenant-a")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.DELETING).build();
        when(knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a"))
                .thenReturn(Optional.of(deleting));

        var service = new DocumentUploadIntentService(
                knowledgeBases, documents, jobs, mock(UploadQuotaService.class), mock(IngestOutboxService.class));

        assertThatThrownBy(() -> service.prepare("tenant-a", document(kbId), job(kbId)))
                .isInstanceOf(OperationConflictException.class)
                .hasMessageContaining("deletion");
        verifyNoInteractions(documents, jobs);
    }

    @Test
    void uploadFirstPersistsPendingJobUnderReadyKnowledgeBaseLock() {
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        IngestJobRepository jobs = mock(IngestJobRepository.class);
        UUID kbId = UUID.randomUUID();
        KnowledgeBase ready = KnowledgeBase.builder().id(kbId).tenantId("tenant-a")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.READY).indexRevision(3L).build();
        Document document = document(kbId);
        IngestJob job = job(kbId);
        when(knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a"))
                .thenReturn(Optional.of(ready));

        new DocumentUploadIntentService(knowledgeBases, documents, jobs, null, null)
                .prepare("tenant-a", document, job);

        var order = inOrder(knowledgeBases, documents, jobs);
        order.verify(knowledgeBases).findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a");
        order.verify(documents).save(document);
        order.verify(jobs).saveAndFlush(job);
        assertThat(ready.getIndexRevision()).isEqualTo(4L);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.UPLOADING);
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.UPLOAD_INTENT);
        assertThat(job.getStage()).isEqualTo(com.dupi.rag.domain.enums.IngestStage.UPLOAD_PENDING);
        verify(knowledgeBases).save(ready);
    }

    @Test
    void failedExternalUploadLeavesTruthfulFailedIntentForDeletionInventory() {
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        IngestJobRepository jobs = mock(IngestJobRepository.class);
        Document document = document(UUID.randomUUID());
        IngestJob job = job(document.getKbId());

        IngestOutboxService outbox = mock(IngestOutboxService.class);
        new DocumentUploadIntentService(knowledgeBases, documents, jobs, mock(UploadQuotaService.class), outbox)
                .fail(document, job, "storage unavailable");

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getObjectKey()).isNotBlank();
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("storage unavailable");
        verify(outbox).cancelPendingForJob(job.getId(), "Upload publication failed");
        verify(documents).save(document);
        verify(jobs).saveAndFlush(job);
    }

    @Test
    void publishMakesQuotaDocumentJobAndOutboxRunnableUnderTheKnowledgeBaseLock() {
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        IngestJobRepository jobs = mock(IngestJobRepository.class);
        UploadQuotaService quota = mock(UploadQuotaService.class);
        IngestOutboxService outbox = mock(IngestOutboxService.class);
        UUID kbId = UUID.randomUUID();
        KnowledgeBase ready = KnowledgeBase.builder().id(kbId).tenantId("tenant-a")
                .lifecycleStatus(KnowledgeBaseLifecycleStatus.READY).build();
        Document document = document(kbId);
        document.setStatus(DocumentStatus.UPLOADING);
        IngestJob job = IngestJob.builder().id(UUID.randomUUID()).kbId(kbId).docId(document.getId())
                .status(IngestJobStatus.UPLOAD_INTENT).stage(IngestStage.UPLOAD_PENDING).build();
        UploadQuotaReservation reservation = UploadQuotaReservation.builder().id(UUID.randomUUID()).build();
        when(knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a"))
                .thenReturn(Optional.of(ready));
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));

        DocumentUploadPublication publication = new DocumentUploadIntentService(
                knowledgeBases, documents, jobs, quota, outbox)
                .publish("tenant-a", document, job, reservation);

        var order = inOrder(knowledgeBases, jobs, documents, quota, outbox);
        order.verify(knowledgeBases).findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a");
        order.verify(jobs).findByIdForUpdate(job.getId());
        order.verify(documents).findById(document.getId());
        order.verify(quota).commitInCurrentTransaction(reservation, document);
        order.verify(documents).save(document);
        order.verify(jobs).save(job);
        order.verify(outbox).record(job, ready, document.getObjectKey(), document.getFileName(), document.getMimeType());
        order.verify(jobs).flush();
        assertThat(publication.document().getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(publication.job().getStatus()).isEqualTo(IngestJobStatus.PENDING);
        assertThat(publication.job().getStage()).isEqualTo(IngestStage.QUEUED);
    }

    private Document document(UUID kbId) {
        UUID id = UUID.randomUUID();
        return Document.builder().id(id).kbId(kbId).objectKey(kbId + "/" + id + "/a.md")
                .fileName("a.md").mimeType("text/markdown").fileSize(1L)
                .status(DocumentStatus.PENDING).build();
    }

    private IngestJob job(UUID kbId) {
        return IngestJob.builder().id(UUID.randomUUID()).kbId(kbId).docId(UUID.randomUUID())
                .status(IngestJobStatus.PENDING).build();
    }
}
