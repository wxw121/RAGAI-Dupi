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
import com.dupi.rag.dto.RetrieveResponse;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private final RetrievalProfileService retrievalProfileService;

    @Value("${dupi.worker.base-url:http://worker:8000}")
    private String workerBaseUrl;

    public RetrieveResponse retrieve(UUID kbId, RetrieveRequest request) {
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        RetrievalProfile profile = retrievalProfileService.resolveActive(kb);
        return retrieve(kb, request, profile);
    }

    public RetrieveResponse retrieveForProfile(UUID kbId, RetrieveRequest request, UUID profileId) {
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        return retrieve(kb, request, retrievalProfileService.find(kbId, profileId));
    }

    private RetrieveResponse retrieve(KnowledgeBase kb, RetrieveRequest request, RetrievalProfile profile) {
        UUID kbId = kb.getId();
        int topK = profile == null
                ? clampTopK(request.getTopK() != null ? request.getTopK() : kb.getTopK())
                : clampTopK(profile.getFinalTopK());

        if (profile != null || kb.getRetrievalMode() == RetrievalMode.HYBRID || Boolean.TRUE.equals(request.getUseRerank())) {
            boolean useRerank = profile == null
                    ? Boolean.TRUE.equals(request.getUseRerank())
                    : Boolean.TRUE.equals(profile.getRerankEnabled());
            return hybridRetrieve(kb, request.getQuery(), topK, useRerank, profile);
        }

        List<Float> vector;
        try {
            vector = llmClient.embed(request.getQuery(), kb.getEmbeddingModel());
        } catch (RuntimeException ex) {
            if (!isEmbeddingUnavailable(ex)) {
                throw ex;
            }
            log.warn("Embedding retrieval unavailable; falling back to local chunk text search for kb {}", kbId, ex);
            return localTextFallbackResponse(kb, request.getQuery(), topK, "embedding_unavailable");
        }
        try {
            List<MilvusVectorService.SearchResult> results = milvusVectorService.search(kbId, vector, topK);
            List<RetrievalHit> hits = mapHits(kbId, results);
            return RetrieveResponse.builder()
                    .query(request.getQuery())
                    .retrievalMode("vector")
                    .hits(hits)
                    .diagnostics(diagnostics(kb, "vector", topK, hits.size(), null))
                    .build();
        } catch (IllegalStateException ex) {
            if (!isMilvusUnavailable(ex)) {
                throw ex;
            }
            log.warn("Milvus vector retrieval unavailable; falling back to local chunk text search for kb {}", kbId, ex);
            return localTextFallbackResponse(kb, request.getQuery(), topK, "milvus_unavailable");
        }
    }

    private RetrieveResponse localTextFallbackResponse(KnowledgeBase kb, String query, int topK, String fallbackReason) {
        List<RetrievalHit> hits = localTextFallback(kb.getId(), query, topK);
        return RetrieveResponse.builder()
                .query(query)
                .retrievalMode("local_text_fallback")
                .hits(hits)
                .diagnostics(diagnostics(kb, "local_text_fallback", topK, hits.size(), fallbackReason))
                .build();
    }

    private RetrieveResponse hybridRetrieve(KnowledgeBase kb, String query, int topK, boolean useRerank,
                                            RetrievalProfile profile) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kb_id", kb.getId().toString());
        body.put("query", query);
        body.put("top_k", topK);
        body.put("use_rerank", useRerank);
        body.put("embedding_model", kb.getEmbeddingModel());
        body.put("embedding_dimension", kb.getEmbeddingDimension());
        if (profile != null) {
            body.put("profile_version", profile.getVersion());
            body.put("vector_candidate_count", profile.getVectorCandidateCount());
            body.put("sparse_candidate_count", profile.getSparseCandidateCount());
            body.put("rrf_constant", profile.getRrfConstant());
            body.put("sparse_index_params", profile.getSparseIndexParams());
            body.put("sparse_search_params", profile.getSparseSearchParams());
            body.put("rerank_candidate_limit", profile.getRerankCandidateLimit());
            body.put("final_top_k", profile.getFinalTopK());
        }

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
        if (profile != null && (!(response.get("profile_version") instanceof Number workerVersion)
                || workerVersion.intValue() != profile.getVersion())) {
            throw new IllegalStateException("Worker retrieval profile version does not match the active profile");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawHits = (List<Map<String, Object>>) response.get("hits");
        List<RetrievalHit> hits = rawHits.stream().map(h -> {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (h.get("metadata") instanceof Map<?, ?> rawMetadata) {
                metadata.putAll(sanitizeMap(rawMetadata));
            }
            Map<String, Object> stages = stageDiagnostics(h);
            if (!stages.isEmpty()) metadata.put("retrievalStages", stages);
            return RetrievalHit.builder()
                    .chunkId(UUID.fromString(String.valueOf(h.get("chunk_id"))))
                    .docId(UUID.fromString(String.valueOf(h.get("doc_id"))))
                    .fileName(String.valueOf(h.getOrDefault("file_name", "")))
                    .content(String.valueOf(h.get("content")))
                    .score(((Number) h.getOrDefault("score", 0)).doubleValue())
                    .metadata(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata)))
                    .build();
        }).toList();

        Map<String, Object> diagnostics = diagnostics(kb, useRerank ? "hybrid_rerank" : "hybrid",
                topK, hits.size(), stringValue(response.get("fallback_reason")));
        if (profile != null) diagnostics.put("profileVersion", profile.getVersion());
        if (!rawHits.isEmpty()) diagnostics.putAll(stageDiagnostics(rawHits.get(0)));

        return RetrieveResponse.builder()
                .query(query)
                .retrievalMode(useRerank ? "hybrid_rerank" : "hybrid")
                .hits(hits)
                .diagnostics(diagnostics)
                .build();
    }

    private Map<String, Object> stageDiagnostics(Map<String, Object> hit) {
        Map<String, Object> stages = new LinkedHashMap<>();
        copyStage(hit, stages, "vector_rank", "vectorRank");
        copyStage(hit, stages, "sparse_rank", "sparseRank");
        copyStage(hit, stages, "fusion_score", "fusionScore");
        copyStage(hit, stages, "fusion_rank", "fusionRank");
        copyStage(hit, stages, "rerank_score", "rerankScore");
        copyStage(hit, stages, "rerank_rank", "rerankRank");
        return stages;
    }

    private void copyStage(Map<String, Object> source, Map<String, Object> target, String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value instanceof Number) target.put(targetKey, value);
    }

    private String stringValue(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private Map<String, Object> sanitizeMap(Map<?, ?> source) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        source.forEach((rawKey, rawValue) -> {
            String key = String.valueOf(rawKey);
            String normalized = key.toLowerCase(Locale.ROOT);
            if (isSensitiveMetadataKey(normalized)) {
                return;
            }
            sanitized.put(key, sanitizeValue(rawValue));
        });
        return sanitized;
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) return sanitizeMap(map);
        if (value instanceof List<?> list) return list.stream().map(this::sanitizeValue).toList();
        return value;
    }

    private boolean isSensitiveMetadataKey(String key) {
        return key.contains("embedding") || key.contains("vector") || key.contains("secret")
                || key.contains("credential") || key.contains("password")
                || key.contains("api_key") || key.contains("apikey") || key.contains("access_key")
                || key.contains("accesskey") || key.contains("authorization") || key.contains("token")
                || key.contains("url") || key.contains("endpoint");
    }

    private Map<String, Object> diagnostics(
            KnowledgeBase kb,
            String retrievalMode,
            int topK,
            int hitCount,
            String fallbackReason
    ) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("retrievalMode", retrievalMode);
        diagnostics.put("topK", topK);
        diagnostics.put("hitCount", hitCount);
        diagnostics.put("embeddingModel", kb.getEmbeddingModel());
        diagnostics.put("embeddingDimension", kb.getEmbeddingDimension());
        if (fallbackReason != null) {
            diagnostics.put("fallbackReason", fallbackReason);
        }
        return diagnostics;
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
        return message != null && (
                message.contains("Milvus collection is not ready for search")
                        || message.contains("Milvus search failed")
                        || message.contains("Timestamp lag too large")
                        || message.contains("fail to search on QueryNode")
        );
    }

    private boolean isEmbeddingUnavailable(RuntimeException ex) {
        if (ex instanceof WebClientRequestException) {
            return true;
        }
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof WebClientRequestException) {
                return true;
            }
            cause = cause.getCause();
        }
        String message = ex.getMessage();
        return message != null && (
                message.contains("Failed to resolve")
                        || message.contains("Query failed with SERVFAIL")
                        || message.contains("Connection refused")
                        || message.contains("Connection timed out")
        );
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
                    collectAsciiKeywords(term, terms);
                    collectCjkKeywords(term, terms);
                });
        return terms.stream()
                .filter(term -> !WEAK_QUESTION_TERMS.contains(term))
                .distinct()
                .toList();
    }

    private void collectAsciiKeywords(String term, Set<String> terms) {
        Arrays.stream(term.split("[^a-z0-9_.-]+"))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .forEach(terms::add);
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
