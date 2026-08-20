package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
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

        var service = new DocumentUploadIntentService(knowledgeBases, documents, jobs);

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

        new DocumentUploadIntentService(knowledgeBases, documents, jobs)
                .prepare("tenant-a", document, job);

        var order = inOrder(knowledgeBases, documents, jobs);
        order.verify(knowledgeBases).findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a");
        order.verify(documents).save(document);
        order.verify(jobs).saveAndFlush(job);
        assertThat(ready.getIndexRevision()).isEqualTo(4L);
        verify(knowledgeBases).save(ready);
    }

    @Test
    void failedExternalUploadLeavesTruthfulFailedIntentForDeletionInventory() {
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        IngestJobRepository jobs = mock(IngestJobRepository.class);
        Document document = document(UUID.randomUUID());
        IngestJob job = job(document.getKbId());

        new DocumentUploadIntentService(knowledgeBases, documents, jobs)
                .fail(document, job, "storage unavailable");

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getObjectKey()).isNotBlank();
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("storage unavailable");
        verify(documents).save(document);
        verify(jobs).saveAndFlush(job);
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
