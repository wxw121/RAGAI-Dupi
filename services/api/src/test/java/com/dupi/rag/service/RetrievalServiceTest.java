package com.dupi.rag.service;

import com.dupi.rag.client.LlmClient;
import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.config.RagProperties;
import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.RetrievalMode;
import com.dupi.rag.dto.RetrievalHit;
import com.dupi.rag.dto.RetrieveRequest;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock LlmClient llmClient;
    @Mock MilvusVectorService milvusVectorService;
    @Mock ChunkRepository chunkRepository;
    @Mock DocumentRepository documentRepository;

    RetrievalService service(RagProperties properties) {
        return service(properties, WebClient.builder());
    }

    RetrievalService service(RagProperties properties, WebClient.Builder builder) {
        return new RetrievalService(
                knowledgeBaseService,
                llmClient,
                milvusVectorService,
                chunkRepository,
                documentRepository,
                properties,
                builder
        );
    }

    @Test
    void retrieveUsesVectorSearchAndClampsTopK() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(5)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("hello", "embed")).thenReturn(List.of(0.1f, 0.2f));
        when(milvusVectorService.search(eq(kbId), anyList(), eq(50)))
                .thenReturn(List.of(new MilvusVectorService.SearchResult(chunkId.toString(), docId.toString(), "chunk", 0.9)));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findById(chunkId)).thenReturn(Optional.of(Chunk.builder()
                .id(chunkId).kbId(kbId).docId(docId).chunkIndex(0).content("chunk").metadata(Map.of("heading", "H")).build()));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("hello");
        request.setTopK(500);

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getRetrievalMode()).isEqualTo("vector");
        assertThat(response.getHits()).hasSize(1);
        assertThat(response.getHits().get(0).getFileName()).isEqualTo("doc.md");
        assertThat(response.getHits().get(0).getMetadata()).containsEntry("heading", "H");
    }

    @Test
    void retrieveUsesDefaultTopKAndEmptyFileNameWhenChunkMetadataIsMissing() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(null)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(7);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("hello", "embed")).thenReturn(List.of(0.1f));
        when(milvusVectorService.search(eq(kbId), anyList(), eq(7)))
                .thenReturn(List.of(new MilvusVectorService.SearchResult(chunkId.toString(), docId.toString(), "chunk", 0.9)));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of());
        when(chunkRepository.findById(chunkId)).thenReturn(Optional.empty());
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("hello");

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getHits()).hasSize(1);
        assertThat(response.getHits().get(0).getFileName()).isEmpty();
        assertThat(response.getHits().get(0).getMetadata()).isEmpty();
    }

    @Test
    void retrieveClampsTopKToMinimumOne() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(5)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("hello", "embed")).thenReturn(List.of(0.1f));
        when(milvusVectorService.search(eq(kbId), anyList(), eq(1)))
                .thenReturn(List.of(new MilvusVectorService.SearchResult(chunkId.toString(), docId.toString(), "chunk", 0.9)));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findById(chunkId)).thenReturn(Optional.empty());
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("hello");
        request.setTopK(-5);

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getHits()).hasSize(1);
        verify(milvusVectorService).search(eq(kbId), anyList(), eq(1));
    }

    @Test
    void buildContextStopsBeforeMaxContextLengthAndHandlesMissingMetadata() {
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(85);
        RetrievalService retrievalService = service(rag);

        String context = retrievalService.buildContext(List.of(
                RetrievalHit.builder().fileName("a.md").content("short").metadata(Map.of("heading", "A", "block_type", "table")).build(),
                RetrievalHit.builder().fileName("b.md").content("this second block is intentionally too long for limit").metadata(null).build()
        ));

        assertThat(context).contains("a.md").contains("type: table");
        assertThat(context).doesNotContain("b.md");
    }

    @Test
    void buildContextReturnsEmptyStringWhenFirstBlockExceedsLimit() {
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(10);

        String context = service(rag).buildContext(List.of(
                RetrievalHit.builder().fileName("a.md").content("too long").metadata(Map.of()).build()
        ));

        assertThat(context).isEmpty();
    }

    @Test
    void retrieveUsesHybridResponseAndRerankFlagFromRequest() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(3)
                .embeddingModel("embed")
                .embeddingDimension(3)
                .retrievalMode(RetrievalMode.VECTOR)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(1000);
        ExchangeFunction exchange = request -> {
            String json = """
                    {"hits":[{"chunk_id":"%s","doc_id":"%s","file_name":"doc.md","content":"hybrid hit","score":0.7,"metadata":{"heading":"H"}}]}
                    """.formatted(chunkId, docId);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(json)
                    .build());
        };
        RetrievalService retrievalService = service(rag, WebClient.builder().exchangeFunction(exchange));
        ReflectionTestUtils.setField(retrievalService, "workerBaseUrl", "http://worker");
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("hello");
        request.setUseRerank(true);

        var response = retrievalService.retrieve(kbId, request);

        assertThat(response.getRetrievalMode()).isEqualTo("hybrid_rerank");
        assertThat(response.getHits()).singleElement().satisfies(hit -> {
            assertThat(hit.getChunkId()).isEqualTo(chunkId);
            assertThat(hit.getDocId()).isEqualTo(docId);
            assertThat(hit.getFileName()).isEqualTo("doc.md");
            assertThat(hit.getMetadata()).containsEntry("heading", "H");
        });
        verifyNoInteractions(llmClient, milvusVectorService);
    }

    @Test
    void hybridRetrieveRejectsMalformedWorkerResponse() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(3)
                .embeddingModel("embed")
                .embeddingDimension(3)
                .retrievalMode(RetrievalMode.HYBRID)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(1000);
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{}")
                .build());
        RetrievalService retrievalService = service(rag, WebClient.builder().exchangeFunction(exchange));
        ReflectionTestUtils.setField(retrievalService, "workerBaseUrl", "http://worker");
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("hello");

        assertThatThrownBy(() -> retrievalService.retrieve(kbId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Hybrid retrieval failed");
    }

    @Test
    void retrieveRoutesToHybridWhenKnowledgeBaseRequiresIt() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(3)
                .embeddingModel("embed")
                .embeddingDimension(3)
                .retrievalMode(RetrievalMode.HYBRID)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(1000);
        RetrievalService retrievalService = service(rag);
        ReflectionTestUtils.setField(retrievalService, "workerBaseUrl", "http://127.0.0.1:1");
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("hello");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> retrievalService.retrieve(kbId, request))
                .isInstanceOf(Exception.class);
        verifyNoInteractions(llmClient, milvusVectorService);
    }

    private static Document doc(UUID kbId, UUID docId) {
        return Document.builder()
                .id(docId)
                .kbId(kbId)
                .fileName("doc.md")
                .objectKey("key")
                .mimeType("text/markdown")
                .fileSize(1L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
