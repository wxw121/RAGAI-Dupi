package com.dupi.rag.service;

import com.dupi.rag.client.LlmClient;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.dto.QaCandidate;
import com.dupi.rag.dto.QaCandidatesRequest;
import com.dupi.rag.dto.QaCandidatesResponse;
import com.dupi.rag.repository.DocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QaGenerationService {

    private static final int MAX_CANDIDATES_PER_SOURCE = 3;
    private static final String SYSTEM_PROMPT = """
            Generate retrieval question/answer candidates from the supplied source chunks.
            Return JSON only with this shape:
            {"candidates":[{"sourceChunkId":"uuid","question":"...","answer":"..."}]}
            Use only facts present in each source. Keep answers concise and preserve sourceChunkId exactly.
            """;

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentRepository documentRepository;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public QaCandidatesResponse generate(UUID kbId, QaCandidatesRequest request) {
        knowledgeBaseService.findSystemOrThrow(kbId);
        validateDocument(kbId, request);

        Map<UUID, QaCandidatesRequest.SourceChunk> sources = new LinkedHashMap<>();
        for (QaCandidatesRequest.SourceChunk source : request.getSources()) {
            if (source == null || source.getChunkId() == null || source.getContent() == null
                    || source.getContent().isBlank()) {
                throw new IllegalArgumentException("QA source chunk must include id and content");
            }
            sources.putIfAbsent(source.getChunkId(), source);
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("QA sources must not be empty");
        }

        String response = llmClient.chat(SYSTEM_PROMPT, buildUserPrompt(request.getDocId(), sources.values()));
        return QaCandidatesResponse.builder()
                .candidates(parseCandidates(response, sources.keySet()))
                .build();
    }

    private void validateDocument(UUID kbId, QaCandidatesRequest request) {
        if (request == null || request.getDocId() == null || request.getSources() == null) {
            throw new IllegalArgumentException("QA request must include document and sources");
        }
        Document document = documentRepository.findById(request.getDocId())
                .orElseThrow(() -> new IllegalArgumentException("QA document not found"));
        if (!kbId.equals(document.getKbId())) {
            throw new IllegalArgumentException("QA document does not belong to knowledge base");
        }
    }

    private String buildUserPrompt(UUID docId, Iterable<QaCandidatesRequest.SourceChunk> sources) {
        List<Map<String, Object>> payloadSources = new ArrayList<>();
        for (QaCandidatesRequest.SourceChunk source : sources) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceChunkId", source.getChunkId().toString());
            item.put("content", source.getContent());
            if (source.getMetadata() != null && !source.getMetadata().isEmpty()) {
                item.put("metadata", source.getMetadata());
            }
            payloadSources.add(item);
        }
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "docId", docId.toString(),
                    "sources", payloadSources
            ));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build QA candidate request", ex);
        }
    }

    private List<QaCandidate> parseCandidates(String rawResponse, Set<UUID> allowedSourceIds) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawResponse);
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid QA candidate response", ex);
        }
        JsonNode candidateNodes = root.path("candidates");
        if (!candidateNodes.isArray()) {
            throw new IllegalStateException("Invalid QA candidate response: candidates array missing");
        }

        List<QaCandidate> candidates = new ArrayList<>();
        Map<UUID, Integer> counts = new HashMap<>();
        Map<UUID, Set<String>> seenQuestions = new HashMap<>();
        for (JsonNode node : candidateNodes) {
            UUID sourceId = parseSourceId(node.path("sourceChunkId").asText());
            if (sourceId == null || !allowedSourceIds.contains(sourceId)
                    || counts.getOrDefault(sourceId, 0) >= MAX_CANDIDATES_PER_SOURCE) {
                continue;
            }
            String question = normalizeText(node.path("question").asText());
            String answer = normalizeText(node.path("answer").asText());
            if (question.isBlank() || answer.isBlank()) {
                continue;
            }
            String questionKey = question.toLowerCase(Locale.ROOT);
            if (!seenQuestions.computeIfAbsent(sourceId, ignored -> new HashSet<>()).add(questionKey)) {
                continue;
            }
            candidates.add(QaCandidate.builder()
                    .sourceChunkId(sourceId)
                    .question(question)
                    .answer(answer)
                    .build());
            counts.merge(sourceId, 1, Integer::sum);
        }
        return candidates;
    }

    private UUID parseSourceId(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
