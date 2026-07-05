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
import com.dupi.rag.dto.RetrieveResponse;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private static final Pattern QUERY_SPLIT_PATTERN = Pattern.compile("[\\s\\p{Punct}，。！？；：、（）【】《》]+");
    private static final int MIN_CJK_KEYWORD_LENGTH = 2;
    private static final Set<String> WEAK_QUESTION_TERMS = Set.of(
            "这个", "是什么", "怎么", "什么", "相关", "资料", "文档", "讲了", "讲的", "一下", "如何", "以及"
    );
    private static final List<String> KNOWN_CJK_KEYWORDS = List.of(
            "虚拟环境", "类型注解", "异步编程", "知识库", "创建", "安装", "配置", "依赖", "协程", "任务", "事件循环"
    );
    private static final List<String> OVERVIEW_QUERY_MARKERS = List.of(
            "知识库", "讲了什么", "有哪些内容", "主要内容", "总结", "概览", "介绍"
    );

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
        int topK = clampTopK(request.getTopK() != null ? request.getTopK() : kb.getTopK());

        if (kb.getRetrievalMode() == RetrievalMode.HYBRID || Boolean.TRUE.equals(request.getUseRerank())) {
            return hybridRetrieve(kb, request.getQuery(), topK, Boolean.TRUE.equals(request.getUseRerank()));
        }

        List<Float> vector = llmClient.embed(request.getQuery(), kb.getEmbeddingModel());
        try {
            List<MilvusVectorService.SearchResult> results = milvusVectorService.search(kbId, vector, topK);
            return RetrieveResponse.builder()
                    .query(request.getQuery())
                    .retrievalMode("vector")
                    .hits(mapHits(kbId, results))
                    .build();
        } catch (IllegalStateException ex) {
            if (!isMilvusUnavailable(ex)) {
                throw ex;
            }
            log.warn("Milvus vector retrieval unavailable; falling back to local chunk text search for kb {}", kbId, ex);
            return RetrieveResponse.builder()
                    .query(request.getQuery())
                    .retrievalMode("local_text_fallback")
                    .hits(localTextFallback(kbId, request.getQuery(), topK))
                    .build();
        }
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

    private int clampTopK(Integer topK) {
        if (topK == null) {
            return ragProperties.getDefaultTopK();
        }
        return Math.max(1, Math.min(50, topK));
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

    private boolean isMilvusUnavailable(IllegalStateException ex) {
        String message = ex.getMessage();
        return message != null && message.contains("Milvus collection is not ready for search");
    }

    private List<RetrievalHit> localTextFallback(UUID kbId, String query, int topK) {
        Map<UUID, String> fileNames = documentRepository.findByKbIdOrderByCreatedAtDesc(kbId).stream()
                .collect(Collectors.toMap(Document::getId, Document::getFileName));
        List<String> terms = tokenizeQuery(query);

        List<Chunk> chunks = chunkRepository.findByKbIdOrderByChunkIndexAsc(kbId);
        // Milvus 不可用时使用已持久化的 chunk 文本做兜底召回。
        // 中文查询通常没有空格分词，所以 tokenizeQuery 会补充关键词和 bigram，避免问答拿到空上下文。
        List<RetrievalHit> scoredHits = chunks.stream()
                .map(chunk -> new LocalChunkScore(chunk, scoreChunk(chunk, terms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator
                        .comparingInt(LocalChunkScore::score).reversed()
                        .thenComparing(scored -> scored.chunk().getChunkIndex()))
                .limit(topK)
                .map(scored -> {
                    Chunk chunk = scored.chunk();
                    return RetrievalHit.builder()
                            .chunkId(chunk.getId())
                            .docId(chunk.getDocId())
                            .fileName(fileNames.getOrDefault(chunk.getDocId(), ""))
                            .content(chunk.getContent())
                            .score(scored.score())
                            .metadata(chunk.getMetadata() != null ? chunk.getMetadata() : Map.of())
                            .build();
                })
                .toList();
        if (!scoredHits.isEmpty() || !isOverviewQuery(query)) {
            return scoredHits;
        }

        // 概览类问题（例如“这个知识库讲了什么”）不一定包含文档正文里的精确术语。
        // 当向量库不可用且关键词召回为空时，退回到最早的几个 chunk，保证问答层仍能拿到可总结的上下文。
        return chunks.stream()
                .limit(topK)
                .map(chunk -> RetrievalHit.builder()
                        .chunkId(chunk.getId())
                        .docId(chunk.getDocId())
                        .fileName(fileNames.getOrDefault(chunk.getDocId(), ""))
                        .content(chunk.getContent())
                        .score(0.1)
                        .metadata(chunk.getMetadata() != null ? chunk.getMetadata() : Map.of())
                        .build())
                .toList();
    }

    private boolean isOverviewQuery(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        return OVERVIEW_QUERY_MARKERS.stream().anyMatch(normalized::contains);
    }

    private List<String> tokenizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        Arrays.stream(QUERY_SPLIT_PATTERN.split(normalized))
                .map(String::trim)
                .filter(term -> !term.isBlank())
                .forEach(term -> {
                    terms.add(term);
                    collectCjkKeywords(term, terms);
                });
        return terms.stream()
                .filter(term -> !WEAK_QUESTION_TERMS.contains(term))
                .distinct()
                .toList();
    }

    private void collectCjkKeywords(String term, Set<String> terms) {
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < term.length(); i++) {
            char ch = term.charAt(i);
            if (isCjk(ch)) {
                current.append(ch);
            } else {
                addCjkKeywordRun(current, terms);
            }
        }
        addCjkKeywordRun(current, terms);
    }

    private void addCjkKeywordRun(StringBuilder run, Set<String> terms) {
        if (run.length() < MIN_CJK_KEYWORD_LENGTH) {
            run.setLength(0);
            return;
        }
        String text = run.toString();
        KNOWN_CJK_KEYWORDS.stream()
                .filter(text::contains)
                .forEach(terms::add);
        for (int i = 0; i <= text.length() - MIN_CJK_KEYWORD_LENGTH; i++) {
            terms.add(text.substring(i, i + MIN_CJK_KEYWORD_LENGTH));
        }
        run.setLength(0);
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private int scoreChunk(Chunk chunk, List<String> terms) {
        if (terms.isEmpty() || chunk.getContent() == null || chunk.getContent().isBlank()) {
            return 0;
        }
        String content = chunk.getContent().toLowerCase(Locale.ROOT);
        String heading = String.valueOf(chunk.getMetadata() != null ? chunk.getMetadata().getOrDefault("heading", "") : "")
                .toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (content.contains(term)) {
                score += 2;
            }
            if (!heading.isBlank() && heading.contains(term)) {
                score += 1;
            }
        }
        return score;
    }

    private record LocalChunkScore(Chunk chunk, int score) {}

    public String buildContext(List<RetrievalHit> hits) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int i = 0; i < hits.size(); i++) {
            RetrievalHit hit = hits.get(i);
            Map<String, Object> meta = hit.getMetadata() != null ? hit.getMetadata() : Map.of();
            String section = String.valueOf(meta.getOrDefault("heading", ""));
            String blockType = String.valueOf(meta.getOrDefault("block_type", "prose"));
            String block = String.format(
                    "[%d] %s | section: %s | type: %s\n%s\n\n",
                    i + 1, hit.getFileName(), section, blockType, hit.getContent());
            if (total + block.length() > ragProperties.getMaxContextChars()) {
                break;
            }
            sb.append(block);
            total += block.length();
        }
        return sb.toString().trim();
    }
}
