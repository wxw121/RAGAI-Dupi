package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import com.dupi.rag.dto.BatchDocumentUploadResult;
import com.dupi.rag.dto.DocumentResponse;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.RetrievalProfileRepository;
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
    @Mock RetrievalProfileRepository retrievalProfileRepository;
    @Mock KnowledgeBaseMaintenanceService maintenanceService;
    @Mock UploadQuotaService uploadQuotaService;
    @Mock ProfileIndexStateService profileIndexStateService;

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
                auditLogService,
                retrievalProfileRepository,
                maintenanceService,
                uploadQuotaService,
                profileIndexStateService
        );
    }

    @Test
    void fileFingerprintIncludesFileBytes() {
        MockMultipartFile first = new MockMultipartFile(
                "file", "same.md", "text/markdown", "abc".getBytes());
        MockMultipartFile second = new MockMultipartFile(
                "file", "same.md", "text/markdown", "xyz".getBytes());

        String firstFingerprint = service().fileFingerprint(first);
        String secondFingerprint = service().fileFingerprint(second);

        assertThat(firstFingerprint).startsWith("sha256:");
        assertThat(secondFingerprint).startsWith("sha256:");
        assertThat(firstFingerprint).isNotEqualTo(secondFingerprint);
    }

    @Test
    void uploadPassesContentFingerprintToQuotaReservation() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).name("KB").build();
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.md", "text/markdown", "hello".getBytes());
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(uploadQuotaService.reserveForUpload(
                eq(kbId), any(UUID.class), eq("key-1"), eq("a.md"),
                eq("text/markdown"), eq(5L), anyString()))
                .thenAnswer(invocation -> reservation(kbId, invocation.getArgument(1), "key-1", 5L));

        service().upload(kbId, file, "key-1");

        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        verify(uploadQuotaService).reserveForUpload(
                eq(kbId), any(UUID.class), eq("key-1"), eq("a.md"),
                eq("text/markdown"), eq(5L), fingerprint.capture());
        assertThat(fingerprint.getValue()).isEqualTo(service().fileFingerprint(file));
    }

    @Test
    void uploadStoresFileCreatesJobAndRecordsOutboxWithoutDirectRedisPush() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).name("KB").build();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(uploadQuotaService.reserveForUpload(eq(kbId), any(UUID.class), isNull(), eq("a.md"), eq("text/markdown"), eq(5L), anyString()))
                .thenAnswer(invocation -> reservation(kbId, invocation.getArgument(1), null, 5L));
        MockMultipartFile file = new MockMultipartFile("file", "a.md", "text/markdown", "hello".getBytes());

        var response = service().upload(kbId, file);

        assertThat(response.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(response.getCurrentJob()).isNotNull();
        assertThat(response.getCurrentJob().getStatus()).isEqualTo(IngestJobStatus.PENDING);
        verify(minioStorageService).upload(contains(kbId.toString()), any(), eq(5L), eq("text/markdown"));
        verify(ingestJobRepository).save(any(IngestJob.class));
        verify(ingestOutboxService).record(any(IngestJob.class), eq(kb), contains("a.md"), eq("a.md"), eq("text/markdown"));
        verify(uploadQuotaService).commit(any(UploadQuotaReservation.class), any(Document.class));
        verify(ingestJobProducer, never()).enqueue(any(), any(), any(), any(), any());
        verify(documentRepository, atLeast(2)).save(any(Document.class));
        var publishOrder = inOrder(ingestOutboxService, uploadQuotaService, documentRepository);
        publishOrder.verify(ingestOutboxService)
                .record(any(IngestJob.class), eq(kb), contains("a.md"), eq("a.md"), eq("text/markdown"));
        publishOrder.verify(uploadQuotaService).commit(any(UploadQuotaReservation.class), any(Document.class));
        publishOrder.verify(documentRepository).save(any(Document.class));
    }

    @Test
    void uploadReplaysCommittedIdempotencyKeyWithoutStorageOrOutbox() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = "upload-key-1";
        Document doc = doc(kbId, docId);
        IngestJob job = job(kbId, docId, jobId);
        UploadQuotaReservation committed = reservation(kbId, docId, idempotencyKey, doc.getFileSize());
        committed.setDocId(docId);
        committed.setAttemptId(null);
        committed.setStatus(UploadQuotaReservationStatus.COMMITTED);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).name("KB").build());
        when(uploadQuotaService.reserveForUpload(eq(kbId), any(UUID.class), eq(idempotencyKey), eq("a.md"), eq("text/markdown"), eq(1L), anyString()))
                .thenReturn(committed);
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId)).thenReturn(Optional.of(job));

        var response = service().upload(kbId, new MockMultipartFile("file", "a.md", "text/markdown", "x".getBytes()), idempotencyKey);

        assertThat(response.getId()).isEqualTo(docId);
        assertThat(response.getCurrentJob().getId()).isEqualTo(jobId);
        verifyNoInteractions(minioStorageService);
        verify(ingestJobRepository, never()).save(any());
        verify(ingestOutboxService, never()).record(any(), any(), any(), any(), any());
        verify(uploadQuotaService, never()).commit(any(), any());
    }

    @Test
    void uploadReleasesQuotaReservationWhenMinioUploadFails() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UploadQuotaReservation reservation = reservation(kbId, docId, "key", 5L);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(uploadQuotaService.reserveForUpload(eq(kbId), any(UUID.class), eq("key"), eq("a.md"), eq("text/markdown"), eq(5L), anyString()))
                .thenReturn(reservation);
        doThrow(new IllegalStateException("minio down"))
                .when(minioStorageService).upload(any(), any(), anyLong(), any());

        assertThatThrownBy(() -> service().upload(kbId, new MockMultipartFile("file", "a.md", "text/markdown", "hello".getBytes()), "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Upload failed");

        verify(uploadQuotaService).release(reservation, "Upload failed");
        verify(uploadQuotaService, never()).commit(any(), any());
        ArgumentCaptor<Document> savedDocument = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeast(2)).save(savedDocument.capture());
        assertThat(savedDocument.getAllValues().get(savedDocument.getAllValues().size() - 1)
                .getQuotaReservationId()).isNull();
    }

    @Test
    void uploadReleasesQuotaReservationWhenInitialDocumentSaveFails() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId))
                .thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(uploadQuotaService.reserveForUpload(
                eq(kbId), any(UUID.class), eq("key"), eq("a.md"),
                eq("text/markdown"), eq(5L), anyString()))
                .thenAnswer(invocation -> reservation(
                        kbId, invocation.getArgument(1), "key", 5L));
        doThrow(new IllegalStateException("document database down"))
                .when(documentRepository).save(any(Document.class));

        assertThatThrownBy(() -> service().upload(
                kbId,
                new MockMultipartFile("file", "a.md", "text/markdown", "hello".getBytes()),
                "key"))
                .isInstanceOf(IllegalStateException.class);

        verify(uploadQuotaService).release(any(UploadQuotaReservation.class), eq("Upload failed"));
        verifyNoInteractions(minioStorageService);
    }

    @Test
    void uploadDeletesStoredObjectWhenLaterPersistenceFails() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId))
                .thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(uploadQuotaService.reserveForUpload(
                eq(kbId), any(UUID.class), eq("key"), eq("a.md"),
                eq("text/markdown"), eq(5L), anyString()))
                .thenAnswer(invocation -> reservation(
                        kbId, invocation.getArgument(1), "key", 5L));
        doThrow(new IllegalStateException("job database down"))
                .when(ingestJobRepository).save(any(IngestJob.class));

        assertThatThrownBy(() -> service().upload(
                kbId,
                new MockMultipartFile("file", "a.md", "text/markdown", "hello".getBytes()),
                "key"))
                .isInstanceOf(IllegalStateException.class);

        verify(minioStorageService).upload(contains("a.md"), any(), eq(5L), eq("text/markdown"));
        verify(minioStorageService).delete(contains("a.md"));
        verify(uploadQuotaService).release(any(UploadQuotaReservation.class), eq("Upload failed"));
        verify(ingestOutboxService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void uploadStillReleasesQuotaWhenStoredObjectCleanupFails() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId))
                .thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(uploadQuotaService.reserveForUpload(
                eq(kbId), any(UUID.class), eq("key"), eq("a.md"),
                eq("text/markdown"), eq(5L), anyString()))
                .thenAnswer(invocation -> reservation(
                        kbId, invocation.getArgument(1), "key", 5L));
        doThrow(new IllegalStateException("job database down"))
                .when(ingestJobRepository).save(any(IngestJob.class));
        doThrow(new IllegalStateException("object cleanup down"))
                .when(minioStorageService).delete(anyString());

        assertThatThrownBy(() -> service().upload(
                kbId,
                new MockMultipartFile("file", "a.md", "text/markdown", "hello".getBytes()),
                "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database down");

        verify(minioStorageService).delete(contains("a.md"));
        verify(uploadQuotaService).release(any(UploadQuotaReservation.class), eq("Upload failed"));
    }

    @Test
    void uploadBatchStoresEveryFileAfterSingleKnowledgeBaseLookup() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).name("KB").build();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(uploadQuotaService.reserveForUpload(eq(kbId), any(UUID.class), isNull(), anyString(), eq("text/markdown"), eq(5L), anyString()))
                .thenAnswer(invocation -> reservation(kbId, invocation.getArgument(1), null, 5L));
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
        when(uploadQuotaService.reserveForUpload(eq(kbId), any(UUID.class), isNull(), eq("ok.md"), eq("text/markdown"), eq(5L), anyString()))
                .thenAnswer(invocation -> reservation(kbId, invocation.getArgument(1), null, 5L));
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
        when(uploadQuotaService.reserveForUpload(eq(kbId), any(UUID.class), isNull(), eq("a.md"), eq("text/markdown"), eq(5L), anyString()))
                .thenAnswer(invocation -> reservation(kbId, invocation.getArgument(1), null, 5L));
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
        when(uploadQuotaService.reserveForUpload(eq(kbId), any(UUID.class), isNull(), eq("a.md"), eq("text/markdown"), eq(5L), anyString()))
                .thenAnswer(invocation -> reservation(kbId, invocation.getArgument(1), null, 5L));
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
        UUID jobId = UUID.randomUUID();
        Document doc = doc(kbId, docId);
        IngestJob job = job(kbId, docId, jobId);
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId)).thenReturn(Optional.of(job));

        DocumentService service = service();

        assertThat(service.listByKb(kbId)).singleElement()
                .extracting(response -> response.getCurrentJob().getId())
                .isEqualTo(jobId);
        assertThat(service.get(kbId, docId).getCurrentJob().getId()).isEqualTo(jobId);
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
        verify(vectorCleanupTaskService).enqueueProfileDocument(docId);
        verify(vectorCleanupTaskService).enqueueLegacyDocument(docId);
        verify(milvusVectorService).deleteProfileByDocId(docId);
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

    private static UploadQuotaReservation reservation(UUID kbId, UUID docId, String idempotencyKey, long bytes) {
        return UploadQuotaReservation.builder()
                .id(UUID.randomUUID())
                .tenantId("default")
                .userId("anonymous")
                .kbId(kbId)
                .docId(null)
                .attemptId(docId)
                .idempotencyKey(idempotencyKey)
                .fileFingerprint("a.md:" + bytes + ":text/markdown")
                .reservedBytes(bytes)
                .status(UploadQuotaReservationStatus.PENDING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
