package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.dto.IngestStatusUpdate;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestJobServiceTest {

    @Mock IngestJobRepository ingestJobRepository;
    @Mock DocumentRepository documentRepository;
    @Mock ChunkRepository chunkRepository;
    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock IngestJobProducer ingestJobProducer;

    IngestJobService service() {
        return new IngestJobService(ingestJobRepository, documentRepository, chunkRepository, knowledgeBaseService, ingestJobProducer);
    }

    @Test
    void handleCompletedUpdateReplacesChunksAndMarksDocumentComplete() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        Document doc = doc(kbId, docId);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        IngestStatusUpdate update = IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .status("completed")
                .stage("indexing")
                .chunks(List.of(new IngestStatusUpdate.ChunkPayload(
                        chunkId.toString(), 0, "content", 3, Map.of("heading", "H"), "milvus-1")))
                .build();

        service().handleStatusUpdate(update);

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.COMPLETED);
        assertThat(job.getStage()).isEqualTo(IngestStage.COMPLETED);
        verify(chunkRepository).deleteByDocId(docId);
        verify(chunkRepository).save(argThat(c -> c.getId().equals(chunkId) && c.getMetadata().get("heading").equals("H")));
    }

    @Test
    void handleFailedUpdateStoresErrorOnDocumentAndJob() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        Document doc = doc(kbId, docId);
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(docId.toString())
                .status("failed")
                .errorMessage("bad pdf")
                .build());

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(doc.getErrorMessage()).isEqualTo("bad pdf");
        assertThat(job.getStage()).isEqualTo(IngestStage.FAILED);
    }

    @Test
    void handleProcessingUpdateRejectsMismatchedDocument() {
        UUID kbId = UUID.randomUUID();
        UUID jobDocId = UUID.randomUUID();
        UUID payloadDocId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job(kbId, jobDocId, jobId)));
        when(documentRepository.findById(payloadDocId)).thenReturn(Optional.of(doc(kbId, payloadDocId)));

        assertThatThrownBy(() -> service().handleStatusUpdate(IngestStatusUpdate.builder()
                .jobId(jobId.toString())
                .docId(payloadDocId.toString())
                .status("processing")
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void retryResetsJobAndEnqueuesAgainUntilRetryLimit() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, jobId);
        job.setRetryCount(2);
        Document doc = doc(kbId, docId);
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        when(ingestJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);

        var response = service().retry(jobId);

        assertThat(response.getRetryCount()).isEqualTo(3);
        assertThat(response.getStatus()).isEqualTo(IngestJobStatus.PENDING);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        verify(ingestJobProducer).enqueue(job, kb, doc.getObjectKey(), doc.getFileName(), doc.getMimeType());

        job.setRetryCount(3);
        assertThatThrownBy(() -> service().retry(jobId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listAndGetLatestMapJobsToResponses() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        IngestJob job = job(kbId, docId, UUID.randomUUID());
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId)).thenReturn(Optional.of(job));
        when(ingestJobRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(job));

        IngestJobService service = service();

        assertThat(service.getLatestByDoc(docId).getDocId()).isEqualTo(docId);
        assertThat(service.listByKb(kbId)).hasSize(1);
        verify(knowledgeBaseService).findOrThrow(kbId);
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
}
