package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.IngestOutboxEvent;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestOutboxServiceTest {

    @Mock IngestOutboxEventRepository outboxRepository;
    @Mock IngestJobRepository ingestJobRepository;
    @Mock DocumentRepository documentRepository;
    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock IngestJobProducer ingestJobProducer;
    @Mock DocumentTombstoneService documentTombstoneService;

    @Test
    void recordPersistsPendingEventWithQueuePayloadSnapshot() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        IngestJob job = job(kbId, docId, jobId);

        service().record(job, kb, "kb/doc/a.md", "a.md", "text/markdown");

        ArgumentCaptor<IngestOutboxEvent> captor = ArgumentCaptor.forClass(IngestOutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        IngestOutboxEvent event = captor.getValue();
        assertThat(event.getJobId()).isEqualTo(jobId);
        assertThat(event.getKbId()).isEqualTo(kbId);
        assertThat(event.getDocId()).isEqualTo(docId);
        assertThat(event.getObjectKey()).isEqualTo("kb/doc/a.md");
        assertThat(event.getFileName()).isEqualTo("a.md");
        assertThat(event.getMimeType()).isEqualTo("text/markdown");
        assertThat(event.getStatus()).isEqualTo(IngestOutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isZero();
    }

    @Test
    void dispatchDueEventsPublishesToRedisAndMarksDocumentProcessing() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        Document doc = doc(kbId, docId);
        IngestJob job = job(kbId, docId, jobId);
        IngestOutboxEvent event = event(kbId, docId, jobId);
        when(outboxRepository.findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(List.of(IngestOutboxStatus.PENDING, IngestOutboxStatus.FAILED)),
                any(Instant.class)
        )).thenReturn(List.of(event));
        when(documentTombstoneService.isDeleted(docId)).thenReturn(false);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(kb);

        int dispatched = service().dispatchPending();

        assertThat(dispatched).isEqualTo(1);
        verify(ingestJobProducer).enqueue(job, kb, event.getObjectKey(), event.getFileName(), event.getMimeType());
        assertThat(event.getStatus()).isEqualTo(IngestOutboxStatus.SENT);
        assertThat(event.getLastError()).isNull();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(doc.getErrorMessage()).isNull();
        verify(outboxRepository).save(event);
        verify(documentRepository).save(doc);
        verify(ingestJobRepository).save(job);
    }

    @Test
    void dispatchDueEventsKeepsEventRetryableWhenRedisFails() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        Document doc = doc(kbId, docId);
        IngestJob job = job(kbId, docId, jobId);
        IngestOutboxEvent event = event(kbId, docId, jobId);
        Instant before = Instant.now();
        when(outboxRepository.findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(List.of(IngestOutboxStatus.PENDING, IngestOutboxStatus.FAILED)),
                any(Instant.class)
        )).thenReturn(List.of(event));
        when(documentTombstoneService.isDeleted(docId)).thenReturn(false);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(kb);
        doThrow(new IllegalStateException("redis down"))
                .when(ingestJobProducer).enqueue(job, kb, event.getObjectKey(), event.getFileName(), event.getMimeType());

        int dispatched = service().dispatchPending();

        assertThat(dispatched).isZero();
        assertThat(event.getStatus()).isEqualTo(IngestOutboxStatus.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).contains("redis down");
        assertThat(event.getNextAttemptAt()).isAfter(before);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(job.getStage()).isEqualTo(IngestStage.QUEUED);
        verify(outboxRepository).save(event);
        verify(documentRepository).save(doc);
        verify(ingestJobRepository).save(job);
    }

    @Test
    void dispatchDueEventsCancelsTombstonedDocumentWithoutPublishing() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestOutboxEvent event = event(kbId, docId, jobId);
        when(outboxRepository.findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(List.of(IngestOutboxStatus.PENDING, IngestOutboxStatus.FAILED)),
                any(Instant.class)
        )).thenReturn(List.of(event));
        when(documentTombstoneService.isDeleted(docId)).thenReturn(true);

        int dispatched = service().dispatchPending();

        assertThat(dispatched).isZero();
        assertThat(event.getStatus()).isEqualTo(IngestOutboxStatus.CANCELLED);
        assertThat(event.getLastError()).contains("deleted");
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
        verify(outboxRepository).save(event);
    }

    @Test
    void dispatchDueEventsCancelsMissingJobOrDocumentWithoutPublishing() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestOutboxEvent event = event(kbId, docId, jobId);
        when(outboxRepository.findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(List.of(IngestOutboxStatus.PENDING, IngestOutboxStatus.FAILED)),
                any(Instant.class)
        )).thenReturn(List.of(event));
        when(documentTombstoneService.isDeleted(docId)).thenReturn(false);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.empty());
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc(kbId, docId)));

        int dispatched = service().dispatchPending();

        assertThat(dispatched).isZero();
        assertThat(event.getStatus()).isEqualTo(IngestOutboxStatus.CANCELLED);
        assertThat(event.getLastError()).contains("no longer exists");
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
        verify(outboxRepository).save(event);
    }

    @Test
    void dispatchDueEventsUsesExceptionClassNameWhenRedisFailureMessageIsBlank() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        Document doc = doc(kbId, docId);
        IngestJob job = job(kbId, docId, jobId);
        IngestOutboxEvent event = event(kbId, docId, jobId);
        event.setAttemptCount(2);
        when(outboxRepository.findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                eq(List.of(IngestOutboxStatus.PENDING, IngestOutboxStatus.FAILED)),
                any(Instant.class)
        )).thenReturn(List.of(event));
        when(documentTombstoneService.isDeleted(docId)).thenReturn(false);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(kb);
        doThrow(new IllegalStateException(" "))
                .when(ingestJobProducer).enqueue(job, kb, event.getObjectKey(), event.getFileName(), event.getMimeType());

        int dispatched = service().dispatchPending();

        assertThat(dispatched).isZero();
        assertThat(event.getStatus()).isEqualTo(IngestOutboxStatus.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(3);
        assertThat(event.getLastError()).contains("IllegalStateException");
        assertThat(job.getErrorMessage()).contains("IllegalStateException");
        assertThat(doc.getErrorMessage()).contains("IllegalStateException");
        verify(outboxRepository).save(event);
        verify(documentRepository).save(doc);
        verify(ingestJobRepository).save(job);
    }

    private IngestOutboxService service() {
        return new IngestOutboxService(
                outboxRepository,
                ingestJobRepository,
                documentRepository,
                knowledgeBaseService,
                ingestJobProducer,
                documentTombstoneService
        );
    }

    private static IngestJob job(UUID kbId, UUID docId, UUID jobId) {
        return IngestJob.builder()
                .id(jobId)
                .kbId(kbId)
                .docId(docId)
                .status(IngestJobStatus.PENDING)
                .stage(IngestStage.QUEUED)
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private static Document doc(UUID kbId, UUID docId) {
        return Document.builder()
                .id(docId)
                .kbId(kbId)
                .fileName("a.md")
                .objectKey("kb/a.md")
                .mimeType("text/markdown")
                .fileSize(1L)
                .status(DocumentStatus.PENDING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private static IngestOutboxEvent event(UUID kbId, UUID docId, UUID jobId) {
        return IngestOutboxEvent.builder()
                .id(UUID.randomUUID())
                .jobId(jobId)
                .kbId(kbId)
                .docId(docId)
                .objectKey("kb/doc/a.md")
                .fileName("a.md")
                .mimeType("text/markdown")
                .status(IngestOutboxStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
