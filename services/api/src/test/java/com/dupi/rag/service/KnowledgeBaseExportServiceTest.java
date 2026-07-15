package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.dto.CreateKnowledgeBaseRequest;
import com.dupi.rag.dto.KnowledgeBaseExportResponse;
import com.dupi.rag.dto.KnowledgeBaseImportRequest;
import com.dupi.rag.dto.KnowledgeBaseResponse;
import com.dupi.rag.dto.RagEvalCaseRequest;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.RagEvalCaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseExportServiceTest {

    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock DocumentRepository documentRepository;
    @Mock ChunkRepository chunkRepository;
    @Mock RagEvalCaseRepository ragEvalCaseRepository;
    @Mock RagEvalService ragEvalService;

    @Test
    void exportIncludesKnowledgeBaseDocumentsChunksAndEvalCases() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb(kbId));
        when(documentRepository.findTop1001ByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findTop10001ByKbIdOrderByChunkIndexAsc(kbId)).thenReturn(List.of(chunk(kbId, docId, chunkId)));
        when(ragEvalCaseRepository.findByKbIdOrderByCreatedAtAsc(kbId)).thenReturn(List.of(evalCase(kbId)));

        KnowledgeBaseExportResponse response = service().exportKnowledgeBase(kbId);

        assertThat(response.getKnowledgeBase().getName()).isEqualTo("KB");
        assertThat(response.getDocuments()).singleElement().satisfies(document -> {
            assertThat(document.getOriginalId()).isEqualTo(docId);
            assertThat(document.getFileName()).isEqualTo("doc.md");
        });
        assertThat(response.getChunks()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.getOriginalId()).isEqualTo(chunkId);
            assertThat(snapshot.getOriginalDocId()).isEqualTo(docId);
            assertThat(snapshot.getContent()).isEqualTo("chunk text");
        });
        assertThat(response.getEvalCases()).singleElement().satisfies(eval -> {
            assertThat(eval.getCaseKey()).isEqualTo("install");
            assertThat(eval.getMustContainAny()).contains("install");
        });
    }

    @Test
    void restoreUsesNormalCreationPathsAndDoesNotCreateFakeDocumentOrChunkRows() {
        UUID restoredKbId = UUID.randomUUID();
        KnowledgeBaseResponse restoredResponse = KnowledgeBaseResponse.builder()
                .id(restoredKbId)
                .tenantId("target-tenant")
                .name("Restored KB")
                .build();
        when(knowledgeBaseService.create(any(CreateKnowledgeBaseRequest.class))).thenReturn(restoredResponse);
        KnowledgeBaseImportRequest request = importRequest();

        var restored = service().restore(request);

        assertThat(restored).isSameAs(restoredResponse);
        ArgumentCaptor<CreateKnowledgeBaseRequest> kbCaptor = ArgumentCaptor.forClass(CreateKnowledgeBaseRequest.class);
        ArgumentCaptor<RagEvalCaseRequest> caseCaptor = ArgumentCaptor.forClass(RagEvalCaseRequest.class);
        verify(knowledgeBaseService).create(kbCaptor.capture());
        verify(ragEvalService).createCase(org.mockito.ArgumentMatchers.eq(restoredKbId), caseCaptor.capture());
        verify(documentRepository, never()).save(any(Document.class));
        verify(chunkRepository, never()).save(any(Chunk.class));
        assertThat(kbCaptor.getValue().getName()).isEqualTo("Restored KB");
        assertThat(kbCaptor.getValue().getChunkSize()).isEqualTo(768);
        assertThat(caseCaptor.getValue().getCaseKey()).isEqualTo("restore-case");
    }

    @Test
    void restoreRejectsMissingKnowledgeBaseSnapshot() {
        assertThatThrownBy(() -> service().restore(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("knowledge base snapshot");

        assertThatThrownBy(() -> service().restore(new KnowledgeBaseImportRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("knowledge base snapshot");
    }

    @Test
    void exportRejectsKnowledgeBasesAboveTheChunkSnapshotLimit() {
        UUID kbId = UUID.randomUUID();
        Chunk chunk = chunk(kbId, UUID.randomUUID(), UUID.randomUUID());
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb(kbId));
        when(chunkRepository.findTop10001ByKbIdOrderByChunkIndexAsc(kbId))
                .thenReturn(Collections.nCopies(10_001, chunk));

        assertThatThrownBy(() -> service().exportKnowledgeBase(kbId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10000");
    }

    @Test
    void exportRejectsKnowledgeBasesAboveTheDocumentSnapshotLimit() {
        UUID kbId = UUID.randomUUID();
        Document document = doc(kbId, UUID.randomUUID());
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb(kbId));
        when(chunkRepository.findTop10001ByKbIdOrderByChunkIndexAsc(kbId)).thenReturn(List.of());
        when(documentRepository.findTop1001ByKbIdOrderByCreatedAtDesc(kbId))
                .thenReturn(Collections.nCopies(1_001, document));

        assertThatThrownBy(() -> service().exportKnowledgeBase(kbId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1000");
    }

    private KnowledgeBaseExportService service() {
        return new KnowledgeBaseExportService(
                knowledgeBaseService,
                documentRepository,
                chunkRepository,
                ragEvalCaseRepository,
                ragEvalService
        );
    }

    private static KnowledgeBaseImportRequest importRequest() {
        KnowledgeBaseImportRequest request = new KnowledgeBaseImportRequest();
        KnowledgeBaseImportRequest.KnowledgeBaseSnapshot snapshot =
                new KnowledgeBaseImportRequest.KnowledgeBaseSnapshot();
        snapshot.setName("Restored KB");
        snapshot.setDescription("restored description");
        snapshot.setChunkSize(768);
        snapshot.setChunkOverlap(96);
        snapshot.setTopK(8);
        request.setKnowledgeBase(snapshot);
        RagEvalCaseRequest evalCase = new RagEvalCaseRequest();
        evalCase.setCaseKey("restore-case");
        evalCase.setQuery("restore query");
        request.setEvalCases(List.of(evalCase));
        return request;
    }

    private static KnowledgeBase kb(UUID kbId) {
        return KnowledgeBase.builder()
                .id(kbId)
                .tenantId("default")
                .name("KB")
                .description("desc")
                .chunkSize(512)
                .chunkOverlap(64)
                .topK(5)
                .embeddingModel("embed")
                .embeddingDimension(1024)
                .createdAt(Instant.parse("2026-07-12T00:00:00Z"))
                .updatedAt(Instant.parse("2026-07-12T00:00:00Z"))
                .build();
    }

    private static Document doc(UUID kbId, UUID docId) {
        return Document.builder()
                .id(docId)
                .kbId(kbId)
                .fileName("doc.md")
                .objectKey("source/doc.md")
                .mimeType("text/markdown")
                .fileSize(10L)
                .status(DocumentStatus.COMPLETED)
                .createdAt(Instant.parse("2026-07-12T00:00:00Z"))
                .updatedAt(Instant.parse("2026-07-12T00:00:00Z"))
                .build();
    }

    private static Chunk chunk(UUID kbId, UUID docId, UUID chunkId) {
        return Chunk.builder()
                .id(chunkId)
                .kbId(kbId)
                .docId(docId)
                .chunkIndex(0)
                .content("chunk text")
                .tokenCount(2)
                .metadata(Map.of("heading", "Intro"))
                .milvusId("m1")
                .build();
    }

    private static RagEvalCase evalCase(UUID kbId) {
        return RagEvalCase.builder()
                .id(UUID.randomUUID())
                .kbId(kbId)
                .caseKey("install")
                .query("How to install?")
                .minHits(1)
                .topK(5)
                .expectedFileName("doc.md")
                .mustContainAny(List.of("install"))
                .createdAt(Instant.parse("2026-07-12T00:00:00Z"))
                .updatedAt(Instant.parse("2026-07-12T00:00:00Z"))
                .build();
    }
}
