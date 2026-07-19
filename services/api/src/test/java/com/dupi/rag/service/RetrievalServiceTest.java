package com.dupi.rag.service;

import com.dupi.rag.client.LlmClient;
import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.config.RagProperties;
import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.RetrievalMode;
import com.dupi.rag.domain.enums.RetrievalProfile;
import com.dupi.rag.dto.RetrievalHit;
import com.dupi.rag.dto.RetrieveRequest;
import com.dupi.rag.exception.RetrievalProfileConflictException;
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
    @Mock ProfileIndexStateService profileIndexStateService;
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
                profileIndexStateService,
                new WeightedRrfFusion(),
                retrievalProfileService,
                sparseMigrationRepository
        );
    }

    @Test
    void nonClassicProfileRequiresReadyV2Index() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(5)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .retrievalProfile(RetrievalProfile.PARENT_CHILD)
                .build();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(false);
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("query");

        assertThatThrownBy(() -> service(new RagProperties()).retrieve(kbId, request))
                .isInstanceOf(RetrievalProfileConflictException.class)
                .hasMessageContaining("not ready");
        verifyNoInteractions(llmClient, milvusVectorService);
    }

    @Test
    void activatedV2IndexRemainsUsableWhileANewDocumentIsPending() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(1)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .retrievalProfile(RetrievalProfile.PARENT_CHILD)
                .build();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(profileIndexStateService.isV2Activated(kbId)).thenReturn(true);
        when(llmClient.embed("query", "embed")).thenReturn(List.of(0.1f));
        when(milvusVectorService.searchProfile(
                eq(kbId), anyList(), eq(1), eq(RetrievalProfile.PARENT_CHILD), eq("child")))
                .thenReturn(List.of(new MilvusVectorService.SearchResult(
                        chunkId.toString(), docId.toString(), "child", 0.9)));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("query");

        var response = service(new RagProperties()).retrieve(kbId, request);

        assertThat(response.getHits()).hasSize(1);
        verify(milvusVectorService).searchProfile(
                eq(kbId), anyList(), eq(1), eq(RetrievalProfile.PARENT_CHILD), eq("child"));
        verify(milvusVectorService, never()).searchLegacy(any(), anyList(), anyInt());
    }

    @Test
    void retrieveRequestProfileOverridesKnowledgeBaseDefaultInDiagnostics() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(5)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .retrievalProfile(RetrievalProfile.CLASSIC)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("hello", "embed")).thenReturn(List.of(0.1f, 0.2f));
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);
        when(milvusVectorService.searchProfile(
                eq(kbId), anyList(), eq(5), eq(RetrievalProfile.PARENT_CHILD), eq("child")))
                .thenReturn(List.of(new MilvusVectorService.SearchResult(chunkId.toString(), docId.toString(), "chunk", 0.9)));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findById(chunkId)).thenReturn(Optional.empty());
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("hello");
        request.setRetrievalProfile(RetrievalProfile.PARENT_CHILD);

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getDiagnostics()).containsEntry("retrievalProfile", "parent-child");
    }

    @Test
    void parentChildProfileExpandsChildHitToParentContext() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(5)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .retrievalProfile(RetrievalProfile.PARENT_CHILD)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("child query", "embed")).thenReturn(List.of(0.1f, 0.2f));
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);
        when(milvusVectorService.searchProfile(
                eq(kbId), anyList(), eq(5), eq(RetrievalProfile.PARENT_CHILD), eq("child")))
                .thenReturn(List.of(new MilvusVectorService.SearchResult(childId.toString(), docId.toString(), "child snippet", 0.9)));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findById(childId)).thenReturn(Optional.of(Chunk.builder()
                .id(childId)
                .kbId(kbId)
                .docId(docId)
                .chunkIndex(1)
                .content("child snippet")
                .metadata(Map.of("parent_chunk_id", parentId.toString(), "heading", "Child"))
                .build()));
        lenient().when(chunkRepository.findById(parentId)).thenReturn(Optional.of(Chunk.builder()
                .id(parentId)
                .kbId(kbId)
                .docId(docId)
                .chunkIndex(0)
                .content("full parent paragraph with complete semantic context")
                .metadata(Map.of("heading", "Parent"))
                .build()));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("child query");

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getHits()).singleElement().satisfies(hit -> {
            assertThat(hit.getChunkId()).isEqualTo(parentId);
            assertThat(hit.getContent()).isEqualTo("full parent paragraph with complete semantic context");
            assertThat(hit.getMetadata()).containsEntry("matched_child_chunk_id", childId.toString())
                    .containsEntry("parent_chunk_id", parentId.toString())
                    .containsEntry("expanded_from_child", true);
        });
    }

    @Test
    void parentChildProfileDoesNotExpandParentFromAnotherKnowledgeBase() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(5)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .retrievalProfile(RetrievalProfile.PARENT_CHILD)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("child query", "embed")).thenReturn(List.of(0.1f, 0.2f));
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);
        when(milvusVectorService.searchProfile(
                eq(kbId), anyList(), eq(5), eq(RetrievalProfile.PARENT_CHILD), eq("child")))
                .thenReturn(List.of(new MilvusVectorService.SearchResult(childId.toString(), docId.toString(), "child snippet", 0.9)));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findById(childId)).thenReturn(Optional.of(Chunk.builder()
                .id(childId)
                .kbId(kbId)
                .docId(docId)
                .chunkIndex(1)
                .content("child snippet")
                .metadata(Map.of("parent_chunk_id", parentId.toString()))
                .build()));
        lenient().when(chunkRepository.findById(parentId)).thenReturn(Optional.of(Chunk.builder()
                .id(parentId)
                .kbId(UUID.randomUUID())
                .docId(docId)
                .chunkIndex(0)
                .content("foreign parent context")
                .build()));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("child query");

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getHits()).singleElement().satisfies(hit -> {
            assertThat(hit.getChunkId()).isEqualTo(childId);
            assertThat(hit.getContent()).isEqualTo("child snippet");
        });
    }

    @Test
    void parentChildProfileDeduplicatesChildrenExpandedToSameParent() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID firstChildId = UUID.randomUUID();
        UUID secondChildId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(5)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .retrievalProfile(RetrievalProfile.PARENT_CHILD)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("shared parent", "embed")).thenReturn(List.of(0.1f));
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);
        when(milvusVectorService.searchProfile(
                eq(kbId), anyList(), eq(5), eq(RetrievalProfile.PARENT_CHILD), eq("child"))).thenReturn(List.of(
                new MilvusVectorService.SearchResult(
                        firstChildId.toString(), docId.toString(), "first child", 0.9),
                new MilvusVectorService.SearchResult(
                        secondChildId.toString(), docId.toString(), "second child", 0.8)
        ));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findById(firstChildId)).thenReturn(Optional.of(Chunk.builder()
                .id(firstChildId)
                .kbId(kbId)
                .docId(docId)
                .content("first child")
                .metadata(Map.of("parent_chunk_id", parentId.toString()))
                .build()));
        when(chunkRepository.findById(secondChildId)).thenReturn(Optional.of(Chunk.builder()
                .id(secondChildId)
                .kbId(kbId)
                .docId(docId)
                .content("second child")
                .metadata(Map.of("parent_chunk_id", parentId.toString()))
                .build()));
        when(chunkRepository.findById(parentId)).thenReturn(Optional.of(Chunk.builder()
                .id(parentId)
                .kbId(kbId)
                .docId(docId)
                .content("shared parent context")
                .metadata(Map.of())
                .build()));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("shared parent");

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getHits()).singleElement().satisfies(hit -> {
            assertThat(hit.getChunkId()).isEqualTo(parentId);
            assertThat(hit.getScore()).isEqualTo(0.9);
            assertThat(hit.getMetadata()).containsEntry("matched_child_chunk_id", firstChildId.toString());
        });
    }

    @Test
    void qaAssistedProfileExpandsQaHitToSourceContextWithProvenance() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID qaId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(5)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .retrievalProfile(RetrievalProfile.QA_ASSISTED)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("install query", "embed")).thenReturn(List.of(0.1f, 0.2f));
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);
        when(milvusVectorService.searchProfile(
                eq(kbId), anyList(), eq(5), eq(RetrievalProfile.QA_ASSISTED), isNull()))
                .thenReturn(List.of(new MilvusVectorService.SearchResult(
                        qaId.toString(), docId.toString(), "Question: How?\nAnswer: Use the command.", 0.9)));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findById(qaId)).thenReturn(Optional.of(Chunk.builder()
                .id(qaId)
                .kbId(kbId)
                .docId(docId)
                .chunkIndex(1)
                .content("Question: How?\nAnswer: Use the command.")
                .metadata(Map.of(
                        "chunk_role", "qa",
                        "source_chunk_id", sourceId.toString(),
                        "qa_question", "How?",
                        "qa_answer", "Use the command."
                ))
                .build()));
        when(chunkRepository.findById(sourceId)).thenReturn(Optional.of(Chunk.builder()
                .id(sourceId)
                .kbId(kbId)
                .docId(docId)
                .chunkIndex(0)
                .content("Run the documented installation command in the project directory.")
                .metadata(Map.of("heading", "Installation"))
                .build()));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("install query");

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getHits()).singleElement().satisfies(hit -> {
            assertThat(hit.getChunkId()).isEqualTo(sourceId);
            assertThat(hit.getContent()).isEqualTo("Run the documented installation command in the project directory.");
            assertThat(hit.getMetadata()).containsEntry("heading", "Installation")
                    .containsEntry("matched_qa_chunk_id", qaId.toString())
                    .containsEntry("qa_source_chunk_id", sourceId.toString())
                    .containsEntry("qa_question", "How?")
                    .containsEntry("qa_answer", "Use the command.")
                    .containsEntry("profile", "qa-assisted");
        });
    }

    @Test
    void combinedProfileExpandsQaSourceChildToParentContext() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID qaId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(5)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .retrievalProfile(RetrievalProfile.COMBINED)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("combined query", "embed")).thenReturn(List.of(0.1f, 0.2f));
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);
        when(milvusVectorService.searchProfile(
                eq(kbId), anyList(), eq(5), eq(RetrievalProfile.COMBINED), eq("child")))
                .thenReturn(List.of());
        when(milvusVectorService.searchProfile(
                eq(kbId), anyList(), eq(5), eq(RetrievalProfile.COMBINED), eq("qa")))
                .thenReturn(List.of(new MilvusVectorService.SearchResult(
                        qaId.toString(), docId.toString(), "Question: Why?\nAnswer: Because.", 0.9)));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findById(qaId)).thenReturn(Optional.of(Chunk.builder()
                .id(qaId)
                .kbId(kbId)
                .docId(docId)
                .chunkIndex(2)
                .content("Question: Why?\nAnswer: Because.")
                .metadata(Map.of(
                        "chunk_role", "qa",
                        "source_chunk_id", childId.toString(),
                        "qa_question", "Why?",
                        "qa_answer", "Because."
                ))
                .build()));
        when(chunkRepository.findById(childId)).thenReturn(Optional.of(Chunk.builder()
                .id(childId)
                .kbId(kbId)
                .docId(docId)
                .chunkIndex(1)
                .content("child snippet")
                .metadata(Map.of("chunk_role", "child", "parent_chunk_id", parentId.toString()))
                .build()));
        when(chunkRepository.findById(parentId)).thenReturn(Optional.of(Chunk.builder()
                .id(parentId)
                .kbId(kbId)
                .docId(docId)
                .chunkIndex(0)
                .content("complete parent context")
                .metadata(Map.of("chunk_role", "parent"))
                .build()));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("combined query");

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getHits()).singleElement().satisfies(hit -> {
            assertThat(hit.getChunkId()).isEqualTo(parentId);
            assertThat(hit.getContent()).isEqualTo("complete parent context");
            assertThat(hit.getMetadata()).containsEntry("matched_qa_chunk_id", qaId.toString())
                    .containsEntry("qa_source_chunk_id", childId.toString())
                    .containsEntry("matched_child_chunk_id", childId.toString())
                    .containsEntry("parent_chunk_id", parentId.toString())
                    .containsEntry("profile", "combined");
        });
    }

    @Test
    void combinedVectorProfileTruncatesFusedRoutesToTopK() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID qaId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(1)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .retrievalProfile(RetrievalProfile.COMBINED)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(1);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("combined limit", "embed")).thenReturn(List.of(0.1f));
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);
        when(milvusVectorService.searchProfile(
                eq(kbId), anyList(), eq(1), eq(RetrievalProfile.COMBINED), eq("child")))
                .thenReturn(List.of(new MilvusVectorService.SearchResult(
                        childId.toString(), docId.toString(), "child", 0.9)));
        when(milvusVectorService.searchProfile(
                eq(kbId), anyList(), eq(1), eq(RetrievalProfile.COMBINED), eq("qa")))
                .thenReturn(List.of(new MilvusVectorService.SearchResult(
                        qaId.toString(), docId.toString(), "qa", 0.8)));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("combined limit");

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getHits()).hasSize(1);
    }

    @Test
    void qaAssistedProfileDoesNotExpandSourceFromAnotherDocument() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID foreignDocId = UUID.randomUUID();
        UUID qaId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(5)
                .embeddingModel("embed")
                .retrievalMode(RetrievalMode.VECTOR)
                .retrievalProfile(RetrievalProfile.QA_ASSISTED)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(2000);
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(llmClient.embed("foreign source", "embed")).thenReturn(List.of(0.1f));
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);
        when(milvusVectorService.searchProfile(
                eq(kbId), anyList(), eq(5), eq(RetrievalProfile.QA_ASSISTED), isNull()))
                .thenReturn(List.of(new MilvusVectorService.SearchResult(
                        qaId.toString(), docId.toString(), "Question: Q?\nAnswer: A.", 0.9)));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findById(qaId)).thenReturn(Optional.of(Chunk.builder()
                .id(qaId)
                .kbId(kbId)
                .docId(docId)
                .chunkIndex(1)
                .content("Question: Q?\nAnswer: A.")
                .metadata(Map.of("chunk_role", "qa", "source_chunk_id", sourceId.toString()))
                .build()));
        lenient().when(chunkRepository.findById(sourceId)).thenReturn(Optional.of(Chunk.builder()
                .id(sourceId)
                .kbId(kbId)
                .docId(foreignDocId)
                .chunkIndex(0)
                .content("foreign document content")
                .metadata(Map.of())
                .build()));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("foreign source");

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getHits()).singleElement().satisfies(hit -> {
            assertThat(hit.getChunkId()).isEqualTo(qaId);
            assertThat(hit.getContent()).isEqualTo("Question: Q?\nAnswer: A.");
            assertThat(hit.getMetadata()).doesNotContainKey("matched_qa_chunk_id");
        });
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
        when(milvusVectorService.searchLegacy(eq(kbId), anyList(), eq(50)))
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
        when(milvusVectorService.searchLegacy(eq(kbId), anyList(), eq(7)))
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
        when(milvusVectorService.searchLegacy(eq(kbId), anyList(), eq(1)))
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
        when(milvusVectorService.searchLegacy(eq(kbId), anyList(), eq(1)))
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
        when(milvusVectorService.searchLegacy(eq(kbId), anyList(), eq(3)))
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
        when(milvusVectorService.searchLegacy(eq(kbId), anyList(), eq(2)))
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
        when(milvusVectorService.searchLegacy(eq(kbId), anyList(), eq(1)))
                .thenReturn(List.of(new MilvusVectorService.SearchResult(chunkId.toString(), docId.toString(), "chunk", 0.9)));
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(doc(kbId, docId)));
        when(chunkRepository.findById(chunkId)).thenReturn(Optional.empty());
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("hello");
        request.setTopK(-5);

        var response = service(rag).retrieve(kbId, request);

        assertThat(response.getHits()).hasSize(1);
        verify(milvusVectorService).searchLegacy(eq(kbId), anyList(), eq(1));
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
    void hybridRetrieveHydratesMissingWorkerMetadataBeforeQaExpansion() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID qaId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .topK(1)
                .embeddingModel("embed")
                .embeddingDimension(3)
                .retrievalMode(RetrievalMode.HYBRID)
                .retrievalProfile(RetrievalProfile.QA_ASSISTED)
                .build();
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(1);
        rag.setMaxContextChars(1000);
        ExchangeFunction exchange = request -> {
            String json = """
                    {"hits":[{"chunk_id":"%s","doc_id":"%s","file_name":"doc.md","content":"generated qa","score":0.7}]}
                    """.formatted(qaId, docId);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(json)
                    .build());
        };
        RetrievalService retrievalService = service(rag, WebClient.builder().exchangeFunction(exchange));
        ReflectionTestUtils.setField(retrievalService, "workerBaseUrl", "http://worker");
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(profileIndexStateService.isV2Activated(kbId)).thenReturn(true);
        when(chunkRepository.findById(qaId)).thenReturn(Optional.of(Chunk.builder()
                .id(qaId).kbId(kbId).docId(docId).content("generated qa")
                .metadata(Map.of("chunk_role", "qa", "source_chunk_id", sourceId.toString()))
                .build()));
        when(chunkRepository.findById(sourceId)).thenReturn(Optional.of(Chunk.builder()
                .id(sourceId).kbId(kbId).docId(docId).content("source context")
                .metadata(Map.of("heading", "Source"))
                .build()));
        RetrieveRequest request = new RetrieveRequest();
        request.setQuery("hello");

        var response = retrievalService.retrieve(kbId, request);

        assertThat(response.getHits()).singleElement().satisfies(hit -> {
            assertThat(hit.getChunkId()).isEqualTo(sourceId);
            assertThat(hit.getContent()).isEqualTo("source context");
            assertThat(hit.getMetadata()).containsEntry("matched_qa_chunk_id", qaId.toString());
        });
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
