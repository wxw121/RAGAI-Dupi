package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.entity.RagEvalRunResult;
import com.dupi.rag.domain.enums.RagEvalGateStatus;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.RetrievalProfile;
import com.dupi.rag.dto.RagEvalCaseRequest;
import com.dupi.rag.dto.RagEvalCaseResponse;
import com.dupi.rag.dto.RagEvalGateDecisionResponse;
import com.dupi.rag.dto.RagEvalProfileMetricsResponse;
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
import java.util.LinkedHashMap;
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
    private final ProfileIndexStateService profileIndexStateService;
    private final RetrievalProfileGateService retrievalProfileGateService;

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
        return run(kbId, useRerank, List.of(RetrievalProfile.CLASSIC));
    }

    public RagEvalRunResponse run(UUID kbId, boolean useRerank, List<RetrievalProfile> requestedProfiles) {
        List<RagEvalCase> cases = caseCoordinator.loadOrSeed(kbId);
        List<RetrievalProfile> profiles = normalizeProfiles(requestedProfiles);
        long runRevision = profileIndexStateService.currentRevision(kbId);
        RagEvalRun run = RagEvalRun.builder()
                .kbId(kbId)
                .useRerank(useRerank)
                .profileSet(profiles)
                .indexRevision(runRevision)
                .totalCount(cases.size() * profiles.size())
                .passedCount(0)
                .status(RagEvalRunStatus.RUNNING)
                .createdAt(Instant.now())
                .build();
        run = runRepository.save(run);

        List<RagEvalRunResult> results = new ArrayList<>();
        try {
            for (RetrievalProfile profile : profiles) {
                for (RagEvalCase evalCase : cases) {
                    RagEvalRunResult result = evaluate(kbId, run.getId(), evalCase, useRerank, profile);
                    RagEvalRunResult saved = resultRepository.save(result);
                    results.add(saved == null ? result : saved);
                }
            }
            long currentRevision = profileIndexStateService.currentRevision(kbId);
            boolean indexReady = profileIndexStateService.isV2Ready(kbId);
            Map<RetrievalProfile, RagEvalGateDecisionResponse> decisions = retrievalProfileGateService.calculate(
                    results,
                    runRevision,
                    currentRevision,
                    indexReady
            );
            int passed = (int) results.stream().filter(RagEvalRunResult::isPassed).count();
            run.setPassedCount(passed);
            run.setTotalCount(results.size());
            run.setStatus(RagEvalRunStatus.COMPLETED);
            run.setFailureMessage(null);
            run.setGateSummary(toGateSummaryMap(decisions));
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

    private List<RetrievalProfile> normalizeProfiles(List<RetrievalProfile> requestedProfiles) {
        if (requestedProfiles == null || requestedProfiles.isEmpty()) {
            return List.of(RetrievalProfile.CLASSIC);
        }
        List<RetrievalProfile> profiles = requestedProfiles.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (profiles.isEmpty()) {
            return List.of(RetrievalProfile.CLASSIC);
        }
        boolean hasCandidate = profiles.stream().anyMatch(profile -> profile != RetrievalProfile.CLASSIC);
        if (!hasCandidate) {
            return List.of(RetrievalProfile.CLASSIC);
        }
        List<RetrievalProfile> normalized = new ArrayList<>();
        normalized.add(RetrievalProfile.CLASSIC);
        profiles.stream()
                .filter(profile -> profile != RetrievalProfile.CLASSIC)
                .forEach(normalized::add);
        return normalized;
    }

    private RagEvalRunResult evaluate(UUID kbId, UUID runId, RagEvalCase evalCase, boolean useRerank, RetrievalProfile retrievalProfile) {
        List<String> failureReasons = new ArrayList<>();
        RetrieveResponse response = null;
        List<RetrievalHit> hits = List.of();
        String matchedFile = null;
        String matchedToken = null;
        boolean hitPassed = false;
        boolean citationEligible = hasExpectedFile(evalCase);
        boolean citationPassed = false;

        try {
            RetrieveRequest request = new RetrieveRequest();
            request.setQuery(evalCase.getQuery());
            request.setTopK(safeTopK(evalCase));
            request.setUseRerank(useRerank);
            request.setRetrievalProfile(retrievalProfile);
            response = retrievalService.retrieve(kbId, request);
            hits = response.getHits() == null ? List.of() : response.getHits();
            hitPassed = hits.size() >= safeMinHits(evalCase);
            if (!hitPassed) {
                failureReasons.add("expected at least " + safeMinHits(evalCase) + " hits, got " + hits.size());
            }
            matchedFile = matchFile(evalCase, hits, failureReasons);
            citationPassed = citationEligible && matchedFile != null;
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
                .hitPassed(hitPassed)
                .citationEligible(citationEligible)
                .citationPassed(citationPassed)
                .failureReasons(failureReasons)
                .hitCount(hits.size())
                .expectedFileName(evalCase.getExpectedFileName())
                .matchedFileName(matchedFile)
                .matchedToken(matchedToken)
                .retrievalMode(stringDiagnostic(diagnostics, "retrievalMode", response == null ? null : response.getRetrievalMode()))
                .retrievalProfile(retrievalProfile)
                .fallbackReason(stringDiagnostic(diagnostics, "fallbackReason", null))
                .embeddingModel(stringDiagnostic(diagnostics, "embeddingModel", null))
                .embeddingDimension(intDiagnostic(diagnostics, "embeddingDimension"))
                .topK(safeTopK(evalCase))
                .createdAt(Instant.now())
                .build();
    }

    private String matchFile(RagEvalCase evalCase, List<RetrievalHit> hits, List<String> failureReasons) {
        if (!hasExpectedFile(evalCase)) {
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

    private boolean hasExpectedFile(RagEvalCase evalCase) {
        return evalCase.getExpectedFileName() != null && !evalCase.getExpectedFileName().isBlank();
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
                .profileSet(run.getProfileSet())
                .indexRevision(run.getIndexRevision())
                .gateSummary(toGateSummaryResponse(run.getGateSummary()))
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
                .hitPassed(result.isHitPassed())
                .citationEligible(result.isCitationEligible())
                .citationPassed(result.isCitationPassed())
                .hitCount(result.getHitCount())
                .expectedFileName(result.getExpectedFileName())
                .matchedFileName(result.getMatchedFileName())
                .matchedToken(result.getMatchedToken())
                .retrievalMode(result.getRetrievalMode())
                .retrievalProfile(result.getRetrievalProfile())
                .fallbackReason(result.getFallbackReason())
                .embeddingModel(result.getEmbeddingModel())
                .embeddingDimension(result.getEmbeddingDimension())
                .topK(result.getTopK())
                .build();
    }

    private Map<String, Object> toGateSummaryMap(Map<RetrievalProfile, RagEvalGateDecisionResponse> decisions) {
        Map<String, Object> summary = new LinkedHashMap<>();
        decisions.forEach((profile, decision) -> summary.put(profile.wireValue(), decision));
        return summary;
    }

    private Map<RetrievalProfile, RagEvalGateDecisionResponse> toGateSummaryResponse(Map<String, Object> rawSummary) {
        if (rawSummary == null || rawSummary.isEmpty()) {
            return Map.of();
        }
        Map<RetrievalProfile, RagEvalGateDecisionResponse> summary = new LinkedHashMap<>();
        rawSummary.forEach((key, value) -> {
            RetrievalProfile profile = RetrievalProfile.fromWireValue(key);
            summary.put(profile, toGateDecision(value, profile));
        });
        return summary;
    }

    private RagEvalGateDecisionResponse toGateDecision(Object value, RetrievalProfile fallbackCandidate) {
        if (value instanceof RagEvalGateDecisionResponse decision) {
            return decision;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return RagEvalGateDecisionResponse.builder()
                    .candidate(fallbackCandidate)
                    .baseline(RetrievalProfile.CLASSIC)
                    .status(RagEvalGateStatus.NOT_EVALUATED)
                    .reason("not_evaluated")
                    .build();
        }
        return RagEvalGateDecisionResponse.builder()
                .candidate(profileValue(map.get("candidate"), fallbackCandidate))
                .baseline(profileValue(map.get("baseline"), RetrievalProfile.CLASSIC))
                .status(statusValue(map.get("status")))
                .reason(stringValue(map.get("reason")))
                .metrics(metricsValue(map.get("metrics"), fallbackCandidate))
                .classicMetrics(metricsValue(map.get("classicMetrics"), RetrievalProfile.CLASSIC))
                .hitRateDelta(doubleValue(map.get("hitRateDelta")))
                .citationPassRateDelta(doubleValue(map.get("citationPassRateDelta")))
                .runRevision(longValue(map.get("runRevision")))
                .currentRevision(longValue(map.get("currentRevision")))
                .indexReady(Boolean.TRUE.equals(map.get("indexReady")))
                .build();
    }

    private RagEvalProfileMetricsResponse metricsValue(Object value, RetrievalProfile fallbackProfile) {
        if (value instanceof RagEvalProfileMetricsResponse metrics) {
            return metrics;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return RagEvalProfileMetricsResponse.builder().profile(fallbackProfile).build();
        }
        return RagEvalProfileMetricsResponse.builder()
                .profile(profileValue(map.get("profile"), fallbackProfile))
                .totalCases(intValue(map.get("totalCases")))
                .passedCount(intValue(map.get("passedCount")))
                .hitPassedCount(intValue(map.get("hitPassedCount")))
                .citationEligibleCount(intValue(map.get("citationEligibleCount")))
                .citationPassedCount(intValue(map.get("citationPassedCount")))
                .passRate(doubleValue(map.get("passRate")))
                .hitRate(doubleValue(map.get("hitRate")))
                .citationPassRate(doubleValue(map.get("citationPassRate")))
                .build();
    }

    private RetrievalProfile profileValue(Object value, RetrievalProfile fallback) {
        return value == null ? fallback : RetrievalProfile.fromWireValue(String.valueOf(value));
    }

    private RagEvalGateStatus statusValue(Object value) {
        return value == null ? RagEvalGateStatus.NOT_EVALUATED : RagEvalGateStatus.valueOf(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

}
