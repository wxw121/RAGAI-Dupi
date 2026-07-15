package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.repository.RagEvalCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RagEvalCaseCoordinator {

    public static final int MAX_CASES_PER_KB = 100;

    private static final List<BuiltInCase> BUILT_IN_CASES = List.of(
            new BuiltInCase(
                    "formats-supported",
                    "dupi-RAG supports which document formats?",
                    "sample-knowledge.md",
                    List.of("PDF", "Word", "Excel")
            ),
            new BuiltInCase(
                    "core-capabilities",
                    "What are the core capabilities of dupi-RAG?",
                    "sample-knowledge.md",
                    List.of("Milvus", "SSE", "BM25", "Embedding")
            ),
            new BuiltInCase(
                    "chunk-strategies",
                    "Which chunk strategies are mentioned?",
                    "sample-knowledge.md",
                    List.of("recursive", "semantic")
            )
    );

    private final KnowledgeBaseService knowledgeBaseService;
    private final RagEvalCaseRepository caseRepository;

    @Transactional
    public List<RagEvalCase> loadOrSeed(UUID kbId) {
        knowledgeBaseService.findForUpdateOrThrow(kbId);
        List<RagEvalCase> existing = caseRepository.findByKbIdOrderByCreatedAtAsc(kbId);
        if (existing.size() > MAX_CASES_PER_KB) {
            throw new IllegalArgumentException(
                    "A knowledge base can run at most " + MAX_CASES_PER_KB + " RAG eval cases");
        }
        if (!existing.isEmpty()) {
            return existing;
        }
        List<RagEvalCase> seeded = BUILT_IN_CASES.stream()
                .map(definition -> RagEvalCase.builder()
                        .id(UUID.randomUUID())
                        .kbId(kbId)
                        .caseKey(definition.caseKey())
                        .query(definition.query())
                        .minHits(1)
                        .topK(5)
                        .expectedFileName(definition.expectedFileName())
                        .mustContainAny(definition.mustContainAny())
                        .build())
                .toList();
        return caseRepository.saveAll(seeded);
    }

    @Transactional
    public void assertCanCreate(UUID kbId) {
        knowledgeBaseService.findForUpdateOrThrow(kbId);
        if (caseRepository.countByKbId(kbId) >= MAX_CASES_PER_KB) {
            throw new IllegalArgumentException(
                    "A knowledge base can have at most " + MAX_CASES_PER_KB + " RAG eval cases");
        }
    }

    private record BuiltInCase(
            String caseKey,
            String query,
            String expectedFileName,
            List<String> mustContainAny
    ) {
    }
}
