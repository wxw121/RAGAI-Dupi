package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.entity.RagEvalRunResult;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.dto.RagEvalCaseRequest;
import com.dupi.rag.dto.RagEvalCaseResponse;
import com.dupi.rag.dto.RagEvalRunResponse;
import com.dupi.rag.dto.RagEvalRunResultResponse;
import com.dupi.rag.dto.RetrievalHit;
import com.dupi.rag.dto.RetrieveRequest;
import com.dupi.rag.dto.RetrieveResponse;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.RagEvalCaseRepository;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.RagEvalRunResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RagEvalService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final RetrievalService retrievalService;
    private final RagEvalCaseRepository caseRepository;
    private final RagEvalRunRepository runRepository;
    private final RagEvalRunResultRepository resultRepository;
    private final RagEvalCaseCoordinator caseCoordinator;

    @Transactional
    public List<RagEvalCaseResponse> listCases(UUID kbId) {
        return caseCoordinator.loadOrSeed(kbId).stream()
                .map(this::toCaseResponse)
                .toList();
    }

    @Transactional
    public RagEvalCaseResponse createCase(UUID kbId, RagEvalCaseRequest request) {
        caseCoordinator.assertCanCreate(kbId);
        RagEvalCase evalCase = RagEvalCase.builder().kbId(kbId).build();
        apply(evalCase, request);
        return toCaseResponse(caseRepository.save(evalCase));
    }

    @Transactional
    public RagEvalCaseResponse updateCase(UUID kbId, UUID caseId, RagEvalCaseRequest request) {
        knowledgeBaseService.findOrThrow(kbId);
        RagEvalCase evalCase = findCase(kbId, caseId);
        apply(evalCase, request);
        return toCaseResponse(caseRepository.save(evalCase));
    }

    @Transactional
    public void deleteCase(UUID kbId, UUID caseId) {
        knowledgeBaseService.findOrThrow(kbId);
        caseRepository.delete(findCase(kbId, caseId));
    }

    @Transactional(readOnly = true)
    public List<RagEvalRunResponse> listRuns(UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        return runRepository.findTop10ByKbIdOrderByCreatedAtDesc(kbId).stream()
                .map(run -> toRunResponse(run, resultRepository.findByRunIdOrderByCaseKeyAsc(run.getId())))
                .toList();
    }

    public RagEvalRunResponse run(UUID kbId, boolean useRerank) {
        List<RagEvalCase> cases = caseCoordinator.loadOrSeed(kbId);
        RagEvalRun run = RagEvalRun.builder()
                .kbId(kbId)
                .useRerank(useRerank)
                .totalCount(cases.size())
                .passedCount(0)
                .status(RagEvalRunStatus.RUNNING)
                .createdAt(Instant.now())
                .build();
        run = runRepository.save(run);

        List<RagEvalRunResult> results = new ArrayList<>();
        try {
            for (RagEvalCase evalCase : cases) {
                RagEvalRunResult result = evaluate(kbId, run.getId(), evalCase, useRerank);
                RagEvalRunResult saved = resultRepository.save(result);
                results.add(saved == null ? result : saved);
            }
            int passed = (int) results.stream().filter(RagEvalRunResult::isPassed).count();
            run.setPassedCount(passed);
            run.setTotalCount(results.size());
            run.setStatus(RagEvalRunStatus.COMPLETED);
            run.setFailureMessage(null);
            run = runRepository.save(run);
            return toRunResponse(run, results);
        } catch (Exception ex) {
            run.setPassedCount((int) results.stream().filter(RagEvalRunResult::isPassed).count());
            run.setStatus(RagEvalRunStatus.FAILED);
            run.setFailureMessage(truncateFailure(ex));
            try {
                runRepository.save(run);
            } catch (Exception statusError) {
                ex.addSuppressed(statusError);
            }
            throw new IllegalStateException("RAG evaluation failed: " + truncateFailure(ex), ex);
        }
    }

    private RagEvalRunResult evaluate(UUID kbId, UUID runId, RagEvalCase evalCase, boolean useRerank) {
        List<String> failureReasons = new ArrayList<>();
        RetrieveResponse response = null;
        List<RetrievalHit> hits = List.of();
        String matchedFile = null;
        String matchedToken = null;

        try {
            RetrieveRequest request = new RetrieveRequest();
            request.setQuery(evalCase.getQuery());
            request.setTopK(safeTopK(evalCase));
            request.setUseRerank(useRerank);
            response = retrievalService.retrieve(kbId, request);
            hits = response.getHits() == null ? List.of() : response.getHits();
            if (hits.size() < safeMinHits(evalCase)) {
                failureReasons.add("expected at least " + safeMinHits(evalCase) + " hits, got " + hits.size());
            }
            matchedFile = matchFile(evalCase, hits, failureReasons);
            matchedToken = matchToken(evalCase, hits, failureReasons);
        } catch (Exception ex) {
            failureReasons.add(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }

        Map<String, Object> diagnostics = response == null || response.getDiagnostics() == null
                ? Map.of()
                : response.getDiagnostics();
        return RagEvalRunResult.builder()
                .runId(runId)
                .caseId(evalCase.getId())
                .caseKey(evalCase.getCaseKey())
                .query(evalCase.getQuery())
                .passed(failureReasons.isEmpty())
                .failureReasons(failureReasons)
                .hitCount(hits.size())
                .expectedFileName(evalCase.getExpectedFileName())
                .matchedFileName(matchedFile)
                .matchedToken(matchedToken)
                .retrievalMode(stringDiagnostic(diagnostics, "retrievalMode", response == null ? null : response.getRetrievalMode()))
                .fallbackReason(stringDiagnostic(diagnostics, "fallbackReason", null))
                .embeddingModel(stringDiagnostic(diagnostics, "embeddingModel", null))
                .embeddingDimension(intDiagnostic(diagnostics, "embeddingDimension"))
                .topK(safeTopK(evalCase))
                .createdAt(Instant.now())
                .build();
    }

    private String matchFile(RagEvalCase evalCase, List<RetrievalHit> hits, List<String> failureReasons) {
        if (evalCase.getExpectedFileName() == null || evalCase.getExpectedFileName().isBlank()) {
            return null;
        }
        return hits.stream()
                .filter(hit -> evalCase.getExpectedFileName().equals(hit.getFileName()))
                .findFirst()
                .map(RetrievalHit::getFileName)
                .orElseGet(() -> {
                    failureReasons.add("missing expected file " + evalCase.getExpectedFileName());
                    return null;
                });
    }

    private String matchToken(RagEvalCase evalCase, List<RetrievalHit> hits, List<String> failureReasons) {
        List<String> tokens = evalCase.getMustContainAny() == null ? List.of() : evalCase.getMustContainAny();
        if (tokens.isEmpty()) {
            return null;
        }
        String joined = hits.stream()
                .map(hit -> hit.getContent() == null ? "" : hit.getContent())
                .reduce("", (left, right) -> left + "\n" + right)
                .toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (token != null && joined.contains(token.toLowerCase(Locale.ROOT))) {
                return token;
            }
        }
        failureReasons.add("hits did not contain any expected token: " + String.join(", ", tokens));
        return null;
    }

    private String stringDiagnostic(Map<String, Object> diagnostics, String key, String fallback) {
        Object value = diagnostics.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private Integer intDiagnostic(Map<String, Object> diagnostics, String key) {
        Object value = diagnostics.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private int safeTopK(RagEvalCase evalCase) {
        return evalCase.getTopK() == null ? 5 : evalCase.getTopK();
    }

    private int safeMinHits(RagEvalCase evalCase) {
        return evalCase.getMinHits() == null ? 1 : evalCase.getMinHits();
    }

    private RagEvalCase findCase(UUID kbId, UUID caseId) {
        return caseRepository.findByIdAndKbId(caseId, kbId)
                .orElseThrow(() -> new ResourceNotFoundException("RAG eval case not found: " + caseId));
    }

    private void apply(RagEvalCase evalCase, RagEvalCaseRequest request) {
        evalCase.setCaseKey(request.getCaseKey().trim());
        evalCase.setQuery(request.getQuery().trim());
        evalCase.setMinHits(request.getMinHits() == null ? 1 : request.getMinHits());
        evalCase.setTopK(request.getTopK() == null ? 5 : request.getTopK());
        evalCase.setExpectedFileName(blankToNull(request.getExpectedFileName()));
        evalCase.setMustContainAny(request.getMustContainAny() == null ? List.of() : request.getMustContainAny());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncateFailure(Exception ex) {
        String message = ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }

    private RagEvalCaseResponse toCaseResponse(RagEvalCase evalCase) {
        return RagEvalCaseResponse.builder()
                .id(evalCase.getId())
                .kbId(evalCase.getKbId())
                .caseKey(evalCase.getCaseKey())
                .query(evalCase.getQuery())
                .minHits(evalCase.getMinHits())
                .topK(evalCase.getTopK())
                .expectedFileName(evalCase.getExpectedFileName())
                .mustContainAny(evalCase.getMustContainAny())
                .createdAt(evalCase.getCreatedAt())
                .updatedAt(evalCase.getUpdatedAt())
                .build();
    }

    private RagEvalRunResponse toRunResponse(RagEvalRun run, List<RagEvalRunResult> results) {
        return RagEvalRunResponse.builder()
                .id(run.getId())
                .kbId(run.getKbId())
                .useRerank(Boolean.TRUE.equals(run.getUseRerank()))
                .passedCount(run.getPassedCount())
                .totalCount(run.getTotalCount())
                .status(run.getStatus())
                .failureMessage(run.getFailureMessage())
                .createdAt(run.getCreatedAt())
                .results(results.stream().map(this::toResultResponse).toList())
                .build();
    }

    private RagEvalRunResultResponse toResultResponse(RagEvalRunResult result) {
        return RagEvalRunResultResponse.builder()
                .id(result.getId())
                .caseId(result.getCaseId())
                .caseKey(result.getCaseKey())
                .query(result.getQuery())
                .passed(result.isPassed())
                .failureReasons(result.getFailureReasons())
                .hitCount(result.getHitCount())
                .expectedFileName(result.getExpectedFileName())
                .matchedFileName(result.getMatchedFileName())
                .matchedToken(result.getMatchedToken())
                .retrievalMode(result.getRetrievalMode())
                .fallbackReason(result.getFallbackReason())
                .embeddingModel(result.getEmbeddingModel())
                .embeddingDimension(result.getEmbeddingDimension())
                .topK(result.getTopK())
                .build();
    }

}
