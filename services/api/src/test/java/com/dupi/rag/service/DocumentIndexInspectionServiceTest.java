package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.dto.DocumentIndexDetailResponse;
import com.dupi.rag.dto.IngestJobResponse;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.IngestJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIndexInspectionServiceTest {

    @Mock DocumentService documentService;
    @Mock IngestJobService ingestJobService;
    @Mock IngestJobRepository ingestJobRepository;
    @Mock ChunkRepository chunkRepository;
    @Mock MinioStorageService minioStorageService;

    @Test
    void inspectReturnsDocumentJobChunkAndObjectState() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID firstChunkId = UUID.randomUUID();
        Document doc = document(kbId, docId, DocumentStatus.COMPLETED);
        IngestJob job = job(kbId, docId, jobId, IngestJobStatus.COMPLETED);
        Chunk first = chunk(kbId, docId, firstChunkId, 0, "first chunk content", Map.of("heading", "Intro"));
        Chunk second = chunk(kbId, docId, UUID.randomUUID(), 1, "second chunk content", Map.of());
        IngestJobResponse jobResponse = IngestJobResponse.builder()
                .id(jobId)
                .kbId(kbId)
                .docId(docId)
                .status(IngestJobStatus.COMPLETED)
                .stage(IngestStage.COMPLETED)
                .build();
        when(documentService.findOrThrow(kbId, docId)).thenReturn(doc);
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId)).thenReturn(Optional.of(job));
        when(ingestJobService.toResponse(job, doc)).thenReturn(jobResponse);
        when(chunkRepository.countByDocId(docId)).thenReturn(2L);
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(docId)).thenReturn(List.of(first, second));
        when(minioStorageService.download(doc.getObjectKey())).thenReturn(new ByteArrayInputStream("raw".getBytes()));

        DocumentIndexDetailResponse response = service().inspect(kbId, docId);

        assertThat(response.getDocument().getId()).isEqualTo(docId);
        assertThat(response.getLatestJob()).isSameAs(jobResponse);
        assertThat(response.getObjectKey()).isEqualTo("kb/doc.md");
        assertThat(response.isObjectAvailable()).isTrue();
        assertThat(response.isIndexReady()).isTrue();
        assertThat(response.getChunkCount()).isEqualTo(2);
        assertThat(response.getChunks()).hasSize(2);
        assertThat(response.getChunks().get(0).getId()).isEqualTo(firstChunkId);
        assertThat(response.getChunks().get(0).getChunkIndex()).isZero();
        assertThat(response.getChunks().get(0).getContentPreview()).isEqualTo("first chunk content");
        assertThat(response.getChunks().get(0).getMetadata()).containsEntry("heading", "Intro");
    }

    @Test
    void inspectMarksObjectUnavailableAndIndexNotReadyWhenStorageOrChunksAreMissing() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document doc = document(kbId, docId, DocumentStatus.FAILED);
        when(documentService.findOrThrow(kbId, docId)).thenReturn(doc);
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId)).thenReturn(Optional.empty());
        when(chunkRepository.countByDocId(docId)).thenReturn(0L);
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(docId)).thenReturn(List.of());
        when(minioStorageService.download(doc.getObjectKey())).thenThrow(new ResourceNotFoundException("missing"));

        DocumentIndexDetailResponse response = service().inspect(kbId, docId);

        assertThat(response.getLatestJob()).isNull();
        assertThat(response.isObjectAvailable()).isFalse();
        assertThat(response.isIndexReady()).isFalse();
        assertThat(response.getChunkCount()).isZero();
        assertThat(response.getChunks()).isEmpty();
    }

    @Test
    void inspectReturnsTotalCountButBoundsChunkPreview() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        Document doc = document(kbId, docId, DocumentStatus.COMPLETED);
        List<Chunk> chunks = IntStream.range(0, 25)
                .mapToObj(index -> chunk(kbId, docId, UUID.randomUUID(), index, "chunk " + index, Map.of()))
                .toList();
        when(documentService.findOrThrow(kbId, docId)).thenReturn(doc);
        when(ingestJobRepository.findTopByDocIdOrderByCreatedAtDesc(docId)).thenReturn(Optional.empty());
        when(chunkRepository.countByDocId(docId)).thenReturn(25L);
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(docId)).thenReturn(chunks.subList(0, 20));
        when(minioStorageService.download(doc.getObjectKey())).thenReturn(new ByteArrayInputStream("raw".getBytes()));

        DocumentIndexDetailResponse response = service().inspect(kbId, docId);

        assertThat(response.getChunkCount()).isEqualTo(25);
        assertThat(response.getChunks()).hasSize(20);
        assertThat(response.getChunks()).extracting(DocumentIndexDetailResponse.ChunkPreview::getChunkIndex)
                .containsExactlyElementsOf(IntStream.range(0, 20).boxed().toList());
    }

    private DocumentIndexInspectionService service() {
        return new DocumentIndexInspectionService(
                documentService,
                ingestJobService,
                ingestJobRepository,
                chunkRepository,
                minioStorageService
        );
    }

    private static Document document(UUID kbId, UUID docId, DocumentStatus status) {
        return Document.builder()
                .id(docId)
                .kbId(kbId)
                .fileName("doc.md")
                .objectKey("kb/doc.md")
                .mimeType("text/markdown")
                .fileSize(3L)
                .status(status)
                .createdAt(Instant.parse("2026-07-12T00:00:00Z"))
                .updatedAt(Instant.parse("2026-07-12T00:01:00Z"))
                .build();
    }

    private static IngestJob job(UUID kbId, UUID docId, UUID jobId, IngestJobStatus status) {
        return IngestJob.builder()
                .id(jobId)
                .kbId(kbId)
                .docId(docId)
                .status(status)
                .stage(IngestStage.COMPLETED)
                .retryCount(0)
                .createdAt(Instant.parse("2026-07-12T00:00:00Z"))
                .updatedAt(Instant.parse("2026-07-12T00:01:00Z"))
                .build();
    }

    private static Chunk chunk(UUID kbId, UUID docId, UUID chunkId, int index, String content, Map<String, Object> metadata) {
        return Chunk.builder()
                .id(chunkId)
                .kbId(kbId)
                .docId(docId)
                .chunkIndex(index)
                .content(content)
                .tokenCount(3)
                .metadata(metadata)
                .milvusId("milvus-" + index)
                .build();
    }
}
