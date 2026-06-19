package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.client.LlmClient;
import com.dupi.rag.config.RagProperties;
import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.RetrievalMode;
import com.dupi.rag.dto.RetrievalHit;
import com.dupi.rag.dto.RetrieveRequest;
import com.dupi.rag.dto.RetrieveResponse;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final LlmClient llmClient;
    private final MilvusVectorService milvusVectorService;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final RagProperties ragProperties;
    private final WebClient.Builder webClientBuilder;

    @Value("${dupi.worker.base-url:http://worker:8000}")
    private String workerBaseUrl;

    public RetrieveResponse retrieve(UUID kbId, RetrieveRequest request) {
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        int topK = request.getTopK() != null ? request.getTopK() : kb.getTopK();

        if (kb.getRetrievalMode() == RetrievalMode.HYBRID || Boolean.TRUE.equals(request.getUseRerank())) {
            return hybridRetrieve(kb, request.getQuery(), topK, Boolean.TRUE.equals(request.getUseRerank()));
        }

        List<Float> vector = llmClient.embed(request.getQuery(), kb.getEmbeddingModel());
        List<MilvusVectorService.SearchResult> results = milvusVectorService.search(kbId, vector, topK);
        List<RetrievalHit> hits = mapHits(kbId, results);

        return RetrieveResponse.builder()
                .query(request.getQuery())
                .retrievalMode("vector")
                .hits(hits)
                .build();
    }

    private RetrieveResponse hybridRetrieve(KnowledgeBase kb, String query, int topK, boolean useRerank) {
        Map<String, Object> body = Map.of(
                "kb_id", kb.getId().toString(),
                "query", query,
                "top_k", topK,
                "use_rerank", useRerank,
                "embedding_model", kb.getEmbeddingModel(),
                "embedding_dimension", kb.getEmbeddingDimension()
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> response = webClientBuilder.build()
                .post()
                .uri(workerBaseUrl + "/api/v1/retrieve/hybrid")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null || !response.containsKey("hits")) {
            throw new IllegalStateException("Hybrid retrieval failed");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawHits = (List<Map<String, Object>>) response.get("hits");
        List<RetrievalHit> hits = rawHits.stream().map(h -> RetrievalHit.builder()
                .chunkId(UUID.fromString(String.valueOf(h.get("chunk_id"))))
                .docId(UUID.fromString(String.valueOf(h.get("doc_id"))))
                .fileName(String.valueOf(h.getOrDefault("file_name", "")))
                .content(String.valueOf(h.get("content")))
                .score(((Number) h.getOrDefault("score", 0)).doubleValue())
                .metadata(h.get("metadata") instanceof Map ? (Map<String, Object>) h.get("metadata") : Map.of())
                .build()).toList();

        return RetrieveResponse.builder()
                .query(query)
                .retrievalMode(useRerank ? "hybrid_rerank" : "hybrid")
                .hits(hits)
                .build();
    }

    private List<RetrievalHit> mapHits(UUID kbId, List<MilvusVectorService.SearchResult> results) {
        Map<UUID, String> fileNames = documentRepository.findByKbIdOrderByCreatedAtDesc(kbId).stream()
                .collect(Collectors.toMap(Document::getId, Document::getFileName));

        List<RetrievalHit> hits = new ArrayList<>();
        for (MilvusVectorService.SearchResult r : results) {
            UUID chunkId = UUID.fromString(r.chunkId());
            UUID docId = UUID.fromString(r.docId());
            Chunk chunk = chunkRepository.findById(chunkId).orElse(null);
            hits.add(RetrievalHit.builder()
                    .chunkId(chunkId)
                    .docId(docId)
                    .fileName(fileNames.getOrDefault(docId, ""))
                    .content(r.content())
                    .score(r.score())
                    .metadata(chunk != null ? chunk.getMetadata() : Map.of())
                    .build());
        }
        return hits;
    }

    public String buildContext(List<RetrievalHit> hits) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int i = 0; i < hits.size(); i++) {
            RetrievalHit hit = hits.get(i);
            String block = String.format("[%d] %s (source: %s)\n%s\n\n",
                    i + 1, hit.getFileName(), hit.getDocId(), hit.getContent());
            if (total + block.length() > ragProperties.getMaxContextChars()) {
                break;
            }
            sb.append(block);
            total += block.length();
        }
        return sb.toString().trim();
    }
}
