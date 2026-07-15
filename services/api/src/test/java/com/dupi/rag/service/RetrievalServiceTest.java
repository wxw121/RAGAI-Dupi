package com.dupi.rag.service;

import com.dupi.rag.client.LlmClient;
import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.config.RagProperties;
import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.enums.RetrievalMode;
import com.dupi.rag.dto.RetrievalHit;
import com.dupi.rag.dto.RetrieveRequest;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.SparseMigrationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.net.URI;
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
    @Mock RetrievalProfileService retrievalProfileService;
    @Mock SparseMigrationRepository sparseMigrationRepository;

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
                builder,
                retrievalProfileService,
                sparseMigrationRepository
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
        assertThat(response.getDiagnostics()).containsEntry("hitCount", 1)
                .containsEntry("topK", 50)
                .containsEntry("embeddingModel", "embed")
                .containsEntry("embeddingDimension", 1536);
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
    void retrieveFallsBackToLocalChunkTextWhenMilvusIsUnavailable() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID matchingChunkId = UUID.randomUUID();
        UUID weakChunkId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(1)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("asyncio 并发", "embed")).thenReturn(List.of(0.1f));
        when(milvusVectorService.search(eq(kbId), anyList(), eq(1)))
                .thenThrow(new IllegalStateException("Milvus collection is not ready for search"));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findByKbIdOrderByChunkIndexAsc(kbId)).thenReturn(List.of(
                Chunk.builder()
                        .id(weakChunkId)
                        .kbId(kbId)
                        .docId(docId)
                        .chunkIndex(0)
                        .content("虚拟环境用于隔离项目依赖")
                        .metadata(Map.of("heading", "venv"))
                        .build(),
                Chunk.builder()
                        .id(matchingChunkId)
                        .kbId(kbId)
                        .docId(docId)
                        .chunkIndex(1)
                        .content("asyncio 可以通过 async 和 await 管理并发 I/O 任务")
                        .metadata(Map.of("heading", "asyncio"))
                        .build()
        ));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("asyncio 并发");

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getRetrievalMode()).isEqualTo("local_text_fallback");
        assertThat(response.getDiagnostics()).containsEntry("fallbackReason", "milvus_unavailable")
                .containsEntry("hitCount", 1);
        assertThat(response.getHits()).singleElement().satisfies(hit -> {
            assertThat(hit.getChunkId()).isEqualTo(matchingChunkId);
            assertThat(hit.getFileName()).isEqualTo("doc.md");
            assertThat(hit.getContent()).contains("asyncio");
            assertThat(hit.getMetadata()).containsEntry("heading", "asyncio");
        });
    }

    @Test
    void retrieveFallsBackToLocalChunkTextWhenMilvusSearchHasTimestampLag() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(1)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("本地兜底检索", "embed")).thenReturn(List.of(0.1f));
        when(milvusVectorService.search(eq(kbId), anyList(), eq(1)))
                .thenThrow(new IllegalStateException("Milvus search failed: fail to search on QueryNode 7: Timestamp lag too large"));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findByKbIdOrderByChunkIndexAsc(kbId)).thenReturn(List.of(
                Chunk.builder()
                        .id(chunkId)
                        .kbId(kbId)
                        .docId(docId)
                        .chunkIndex(0)
                        .content("这是可用于本地兜底检索的知识库片段")
                        .metadata(Map.of())
                        .build()
        ));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("本地兜底检索");

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getRetrievalMode()).isEqualTo("local_text_fallback");
        assertThat(response.getDiagnostics()).containsEntry("fallbackReason", "milvus_unavailable");
        assertThat(response.getHits()).singleElement().satisfies(hit -> {
            assertThat(hit.getChunkId()).isEqualTo(chunkId);
            assertThat(hit.getContent()).contains("本地兜底检索");
        });
    }

    @Test
    void retrieveFallsBackToLocalChunkTextWhenEmbeddingProviderNetworkFails() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(3)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("venv是什么", "embed"))
                .thenThrow(new WebClientRequestException(
                        new RuntimeException("Query failed with SERVFAIL"),
                        HttpMethod.POST,
                        URI.create("https://open.bigmodel.cn/api/paas/v4/embeddings"),
                        HttpHeaders.EMPTY
                ));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findByKbIdOrderByChunkIndexAsc(kbId)).thenReturn(List.of(
                Chunk.builder()
                        .id(chunkId)
                        .kbId(kbId)
                        .docId(docId)
                        .chunkIndex(0)
                        .content("venv 是 Python 虚拟环境，用于隔离项目依赖。")
                        .metadata(Map.of("heading", "venv"))
                        .build()
        ));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("venv是什么");

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getRetrievalMode()).isEqualTo("local_text_fallback");
        assertThat(response.getDiagnostics()).containsEntry("fallbackReason", "embedding_unavailable")
                .containsEntry("hitCount", 1);
        assertThat(response.getHits()).singleElement().satisfies(hit -> {
            assertThat(hit.getChunkId()).isEqualTo(chunkId);
            assertThat(hit.getContent()).contains("Python 虚拟环境");
        });
        verifyNoInteractions(milvusVectorService);
    }

    @Test
    void retrieveLocalFallbackMatchesChineseQueryWithoutSpaces() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID matchingChunkId = UUID.randomUUID();
        UUID otherChunkId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(3)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("虚拟环境怎么创建", "embed")).thenReturn(List.of(0.1f));
        when(milvusVectorService.search(eq(kbId), anyList(), eq(3)))
                .thenThrow(new IllegalStateException("Milvus collection is not ready for search"));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findByKbIdOrderByChunkIndexAsc(kbId)).thenReturn(List.of(
                Chunk.builder()
                        .id(otherChunkId)
                        .kbId(kbId)
                        .docId(docId)
                        .chunkIndex(0)
                        .content("asyncio 使用事件循环调度协程任务")
                        .metadata(Map.of("heading", "asyncio"))
                        .build(),
                Chunk.builder()
                        .id(matchingChunkId)
                        .kbId(kbId)
                        .docId(docId)
                        .chunkIndex(1)
                        .content("可以通过 python -m venv .venv 创建虚拟环境，并隔离项目依赖")
                        .metadata(Map.of("heading", "虚拟环境"))
                        .build()
        ));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("虚拟环境怎么创建");

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getRetrievalMode()).isEqualTo("local_text_fallback");
        assertThat(response.getHits()).first().satisfies(hit -> {
            assertThat(hit.getChunkId()).isEqualTo(matchingChunkId);
            assertThat(hit.getContent()).contains("虚拟环境");
        });
    }

    @Test
    void retrieveLocalFallbackReturnsEarlyChunksForOverviewQueryWhenNoTermMatches() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID firstChunkId = UUID.randomUUID();
        UUID secondChunkId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(2)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("这个知识库讲了什么", "embed")).thenReturn(List.of(0.1f));
        when(milvusVectorService.search(eq(kbId), anyList(), eq(2)))
                .thenThrow(new IllegalStateException("Milvus collection is not ready for search"));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findByKbIdOrderByChunkIndexAsc(kbId)).thenReturn(List.of(
                Chunk.builder()
                        .id(firstChunkId)
                        .kbId(kbId)
                        .docId(docId)
                        .chunkIndex(0)
                        .content("venv isolates Python project dependencies.")
                        .metadata(Map.of("heading", "venv"))
                        .build(),
                Chunk.builder()
                        .id(secondChunkId)
                        .kbId(kbId)
                        .docId(docId)
                        .chunkIndex(1)
                        .content("async and await manage concurrent I/O.")
                        .metadata(Map.of("heading", "asyncio"))
                        .build()
        ));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("这个知识库讲了什么");

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getRetrievalMode()).isEqualTo("local_text_fallback");
        assertThat(response.getHits()).extracting(RetrievalHit::getChunkId)
                .containsExactly(firstChunkId, secondChunkId);
        assertThat(response.getHits()).extracting(RetrievalHit::getScore)
                .containsOnly(0.1);
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
                    {"rerank_applied":true,"hits":[{"chunk_id":"%s","doc_id":"%s","file_name":"doc.md","content":"hybrid hit","score":0.7,"metadata":{"heading":"H"}}]}
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
    void hybridRetrieveAppliesActiveProfileAndExposesRedactedStageRanks() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).topK(3).embeddingModel("embed")
                .embeddingDimension(3).retrievalMode(RetrievalMode.HYBRID)
                .activeRetrievalProfileId(UUID.randomUUID()).build();
        RetrievalProfile profile = RetrievalProfile.builder()
                .id(kb.getActiveRetrievalProfileId()).kbId(kbId).name("balanced").version(4)
                .vectorCandidateCount(30).sparseCandidateCount(20).rrfConstant(60)
                .rerankEnabled(true).rerankCandidateLimit(10).finalTopK(5).build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(1000);
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                        {"profile_version":4,"rerank_applied":false,"fallback_reason":"reranker_unavailable","hits":[{"chunk_id":"%s","doc_id":"%s","file_name":"doc.md",
                        "content":"hit","score":0.7,"metadata":{"embedding":[0.3],"provider_url":"secret",
                        "nested":[[{"base_url":"secret","authorization":"bearer","safe":"kept","nullable":null}]]},
                        "vector_rank":2,"sparse_rank":1,"fusion_rank":1,
                        "rerank_rank":1,"rerank_score":0.91,"embedding":[0.1,0.2]}]}
                        """.formatted(chunkId, docId)).build());
        RetrievalService retrievalService = service(rag, WebClient.builder().exchangeFunction(exchange));
        ReflectionTestUtils.setField(retrievalService, "workerBaseUrl", "http://worker");
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(retrievalProfileService.find(kbId, profile.getId())).thenReturn(profile);
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("hello");

        var response = retrievalService.retrieveForProfile(kbId, request, profile.getId());

        assertThat(response.getDiagnostics()).containsEntry("profileVersion", 4)
                .containsEntry("rerankApplied", false).containsEntry("fallbackReason", "reranker_unavailable")
                .containsEntry("vectorRank", 2).containsEntry("sparseRank", 1)
                .containsEntry("fusionRank", 1).doesNotContainKey("embedding");
        assertThat(response.getRetrievalMode()).isEqualTo("hybrid");
        assertThat(response.getHits()).singleElement().satisfies(hit ->
                assertThat(hit.getMetadata()).containsKey("retrievalStages")
                        .doesNotContainKeys("embedding", "provider_url")
                        .hasEntrySatisfying("nested", nested -> assertThat(String.valueOf(nested))
                                .contains("safe=kept", "nullable=null").doesNotContain("secret", "bearer")));
    }

    @Test
    void hybridRetrieveRejectsMismatchedWorkerProfileVersion() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).topK(3).embeddingModel("embed")
                .embeddingDimension(3).retrievalMode(RetrievalMode.HYBRID)
                .activeRetrievalProfileId(UUID.randomUUID()).build();
        RetrievalProfile profile = RetrievalProfile.builder()
                .id(kb.getActiveRetrievalProfileId()).kbId(kbId).name("balanced").version(4)
                .vectorCandidateCount(30).sparseCandidateCount(20).rrfConstant(60)
                .rerankEnabled(false).rerankCandidateLimit(10).finalTopK(5).build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(1000);
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"profile_version\":5,\"hits\":[]}").build());
        RetrievalService retrievalService = service(rag, WebClient.builder().exchangeFunction(exchange));
        ReflectionTestUtils.setField(retrievalService, "workerBaseUrl", "http://worker");
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(retrievalProfileService.resolveActive(kb)).thenReturn(profile);
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("hello");

        assertThatThrownBy(() -> retrievalService.retrieve(kbId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile version");
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
