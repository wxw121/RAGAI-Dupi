package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.dto.BatchDocumentUploadResult;
import com.dupi.rag.dto.DocumentResponse;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock IngestJobRepository ingestJobRepository;
    @Mock ChunkRepository chunkRepository;
    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock MinioStorageService minioStorageService;
    @Mock MilvusVectorService milvusVectorService;
    @Mock IngestJobProducer ingestJobProducer;
    @Mock IngestOutboxService ingestOutboxService;
    @Mock DocumentTombstoneService documentTombstoneService;
    @Mock VectorCleanupTaskService vectorCleanupTaskService;
    @Mock AuditLogService auditLogService;

    DocumentService service() {
        return new DocumentService(
                documentRepository,
                ingestJobRepository,
                chunkRepository,
                knowledgeBaseService,
                minioStorageService,
                milvusVectorService,
                ingestJobProducer,
                ingestOutboxService,
                documentTombstoneService,
                vectorCleanupTaskService,
                auditLogService
        );
    }

    @Test
    void uploadStoresFileCreatesJobAndRecordsOutboxWithoutDirectRedisPush() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).name("KB").build();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        MockMultipartFile file = new MockMultipartFile("file", "a.md", "text/markdown", "hello".getBytes());

        var response = service().upload(kbId, file);

        assertThat(response.getStatus()).isEqualTo(DocumentStatus.PENDING);
        verify(minioStorageService).upload(contains(kbId.toString()), any(), eq(5L), eq("text/markdown"));
        verify(ingestJobRepository).save(any(IngestJob.class));
        verify(ingestOutboxService).record(any(IngestJob.class), eq(kb), contains("a.md"), eq("a.md"), eq("text/markdown"));
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
        verify(documentRepository, atLeast(2)).save(any(Document.class));
    }

    @Test
    void uploadBatchStoresEveryFileAfterSingleKnowledgeBaseLookup() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).name("KB").build();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        MockMultipartFile first = new MockMultipartFile("files", "a.md", "text/markdown", "hello".getBytes());
        MockMultipartFile second = new MockMultipartFile("files", "b.md", "text/markdown", "world".getBytes());

        var response = service().uploadBatch(kbId, List.of(first, second));

        assertThat(response.getTotal()).isEqualTo(2);
        assertThat(response.getSucceeded()).isEqualTo(2);
        assertThat(response.getFailed()).isZero();
        assertThat(response.getResults()).extracting(BatchDocumentUploadResult::isSuccess)
                .containsExactly(true, true);
        assertThat(response.getResults()).extracting(result -> result.getDocument().getStatus())
                .containsExactly(DocumentStatus.PENDING, DocumentStatus.PENDING);
        verify(knowledgeBaseService, times(1)).findOrThrow(kbId);
        verify(minioStorageService).upload(contains("a.md"), any(), eq(5L), eq("text/markdown"));
        verify(minioStorageService).upload(contains("b.md"), any(), eq(5L), eq("text/markdown"));
        verify(ingestOutboxService, times(2)).record(any(IngestJob.class), eq(kb), anyString(), anyString(), eq("text/markdown"));
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test
    void uploadBatchRejectsEmptyFileListBeforeStorageWork() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());

        assertThatThrownBy(() -> service().uploadBatch(kbId, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Files are empty");

        verifyNoInteractions(minioStorageService, ingestJobRepository, ingestJobProducer);
    }

    @Test
    void uploadBatchReturnsPerFileFailuresWithoutStoppingSuccessfulFiles() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).name("KB").build();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        MockMultipartFile valid = new MockMultipartFile("files", "ok.md", "text/markdown", "hello".getBytes());
        MockMultipartFile empty = new MockMultipartFile("files", "empty.md", "text/markdown", new byte[0]);

        var response = service().uploadBatch(kbId, List.of(valid, empty));

        assertThat(response.getTotal()).isEqualTo(2);
        assertThat(response.getSucceeded()).isEqualTo(1);
        assertThat(response.getFailed()).isEqualTo(1);
        assertThat(response.getResults()).extracting(BatchDocumentUploadResult::getFileName)
                .containsExactly("ok.md", "empty.md");
        assertThat(response.getResults().get(0).isSuccess()).isTrue();
        assertThat(response.getResults().get(0).getDocument().getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(response.getResults().get(1).isSuccess()).isFalse();
        assertThat(response.getResults().get(1).getDocument()).isNull();
        assertThat(response.getResults().get(1).getErrorMessage()).contains("File is empty");
        verify(minioStorageService, times(1)).upload(any(), any(), anyLong(), any());
    }

    @Test
    void uploadMarksDocumentFailedWhenMinioUploadFails() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        doThrow(new IllegalStateException("minio down"))
                .when(minioStorageService).upload(any(), any(), anyLong(), any());

        MockMultipartFile file = new MockMultipartFile("file", "a.md", "text/markdown", "hello".getBytes());

        assertThatThrownBy(() -> service().upload(kbId, file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Upload failed");

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeast(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getStatus())
                .isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    void uploadFailsBeforeReturningWhenOutboxRecordFails() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        doThrow(new IllegalStateException("database down"))
                .when(ingestOutboxService).record(any(), any(), any(), any(), any());
        MockMultipartFile file = new MockMultipartFile("file", "a.md", "text/markdown", "hello".getBytes());

        assertThatThrownBy(() -> service().upload(kbId, file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database down");

        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test
    void uploadFailsBeforeStorageWhenQueueIsAlreadyFull() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).name("KB").build();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        doThrow(new IllegalStateException("Ingest queue is full"))
                .when(ingestJobProducer).assertQueueAccepting();
        MockMultipartFile file = new MockMultipartFile("file", "a.md", "text/markdown", "hello".getBytes());

        assertThatThrownBy(() -> service().upload(kbId, file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ingest queue is full");

        verifyNoInteractions(minioStorageService, ingestJobRepository);
        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void uploadRejectsEmptyFileBeforeStorageWork() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> service().upload(kbId, empty)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(minioStorageService, ingestJobRepository, ingestJobProducer);
    }

    @Test
    void listAndGetValidateKnowledgeBaseAndDocumentOwnership() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document doc = doc(kbId, docId);
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        DocumentService service = service();

        assertThat(service.listByKb(kbId)).hasSize(1);
        assertThat(service.get(kbId, docId).getId()).isEqualTo(docId);
        verify(knowledgeBaseService).findOrThrow(kbId);
    }

    @Test
    void deleteContinuesWhenExternalStoresFail() throws IOException {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document doc = doc(kbId, docId);
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        doThrow(new IllegalStateException("milvus")).when(milvusVectorService).deleteByDocId(docId);
        doThrow(new IllegalStateException("minio")).when(minioStorageService).delete(doc.getObjectKey());

        service().delete(kbId, docId);

        verify(documentTombstoneService).recordDeleted(doc);
        verify(vectorCleanupTaskService).enqueueDocument(docId);
        verify(chunkRepository).deleteByDocId(docId);
        verify(documentRepository).delete(doc);
        verify(auditLogService).recordSuccess(
                eq("DOCUMENT_DELETE"),
                eq("DOCUMENT"),
                eq(docId),
                contains(doc.getFileName())
        );
    }

    private static Document doc(UUID kbId, UUID docId) {
        return Document.builder()
                .id(docId)
                .kbId(kbId)
                .fileName("a.md")
                .mimeType("text/markdown")
                .objectKey("k/a.md")
                .fileSize(1L)
                .status(DocumentStatus.PENDING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
