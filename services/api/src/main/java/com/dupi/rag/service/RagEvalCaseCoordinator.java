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

    private final KnowledgeBaseService knowledgeBaseService;
    private final RagEvalCaseRepository caseRepository;

    @Transactional(readOnly = true)
    public List<RagEvalCase> loadCases(UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        List<RagEvalCase> existing = caseRepository.findByKbIdOrderByCreatedAtAsc(kbId);
        if (existing.size() > MAX_CASES_PER_KB) {
            throw new IllegalArgumentException(
                    "A knowledge base can run at most " + MAX_CASES_PER_KB + " RAG eval cases");
        }
        return existing;
    }

    @Transactional
    public void assertCanCreate(UUID kbId) {
        knowledgeBaseService.findForUpdateOrThrow(kbId);
        if (caseRepository.countByKbId(kbId) >= MAX_CASES_PER_KB) {
            throw new IllegalArgumentException(
                    "A knowledge base can have at most " + MAX_CASES_PER_KB + " RAG eval cases");
        }
    }
}
