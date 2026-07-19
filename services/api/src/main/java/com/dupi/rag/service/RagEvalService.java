package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.entity.RagEvalRunResult;
import com.dupi.rag.domain.entity.RagQualityPolicy;
import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.RagEvalComparisonStatus;
import com.dupi.rag.domain.enums.RagEvalGateStatus;
import com.dupi.rag.domain.enums.RagQualityGateStatus;
import com.dupi.rag.domain.enums.RetrievalMode;
import com.dupi.rag.dto.RagEvalCaseRequest;
import com.dupi.rag.dto.RagEvalCaseResponse;
import com.dupi.rag.dto.RagEvalGateDecisionResponse;
import com.dupi.rag.dto.RagEvalProfileMetricsResponse;
import com.dupi.rag.dto.RagEvalRunResponse;
import com.dupi.rag.dto.RagEvalRunResultResponse;
import com.dupi.rag.dto.RagQualityPolicyRequest;
import com.dupi.rag.dto.RagQualityPolicyResponse;
import com.dupi.rag.dto.RetrievalHit;
import com.dupi.rag.dto.RetrieveRequest;
import com.dupi.rag.dto.RetrieveResponse;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.RagEvalCaseRepository;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.RagEvalRunResultRepository;
import com.dupi.rag.repository.RagQualityPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final RagQualityPolicyRepository policyRepository;
    private final RagQualityGateService qualityGateService;
    private final AuditLogService auditLogService;
    private final RetrievalProfileService retrievalProfileService;
    private final KnowledgeBaseMaintenanceService maintenanceService;
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
        maintenanceService.assertMutationAllowed(kbId);
        caseCoordinator.assertCanCreate(kbId);
        RagEvalCase evalCase = RagEvalCase.builder().kbId(kbId).build();
        apply(evalCase, request);
        return toCaseResponse(caseRepository.save(evalCase));
    }

    @Transactional
    public RagEvalCaseResponse updateCase(UUID kbId, UUID caseId, RagEvalCaseRequest request) {
        maintenanceService.assertMutationAllowed(kbId);
        knowledgeBaseService.findOrThrow(kbId);
        RagEvalCase evalCase = findCase(kbId, caseId);
        apply(evalCase, request);
        return toCaseResponse(caseRepository.save(evalCase));
    }

    @Transactional
    public void deleteCase(UUID kbId, UUID caseId) {
        maintenanceService.assertMutationAllowed(kbId);
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
        return run(kbId, useRerank, List.of(com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC));
    }

    public RagEvalRunResponse run(UUID kbId, boolean useRerank, UUID profileId) {
        return run(kbId, useRerank, profileId, null);
    }

    public RagEvalRunResponse run(
            UUID kbId,
            boolean useRerank,
            List<com.dupi.rag.domain.enums.RetrievalProfile> requestedProfiles
    ) {
        maintenanceService.assertMutationAllowed(kbId);
        List<RagEvalCase> cases = caseCoordinator.loadOrSeed(kbId);
        List<com.dupi.rag.domain.enums.RetrievalProfile> profiles = normalizeProfiles(requestedProfiles);
        long runRevision = profileIndexStateService.currentRevision(kbId);
        RagEvalRun run = RagEvalRun.builder()
                .kbId(kbId)
                .useRerank(useRerank)
                .profileSet(profiles)
                .indexRevision(runRevision)
                .totalCount(cases.size() * profiles.size())
                .passedCount(0)
                .status(RagEvalRunStatus.RUNNING)
                .profileSnapshot(Map.of(
                        "profiles", profiles.stream().map(com.dupi.rag.domain.enums.RetrievalProfile::wireValue).toList(),
                        "useRerank", useRerank
                ))
                .createdAt(Instant.now())
                .build();
        run = runRepository.save(run);

        List<RagEvalRunResult> results = new ArrayList<>();
        try {
            for (com.dupi.rag.domain.enums.RetrievalProfile profile : profiles) {
                for (RagEvalCase evalCase : cases) {
                    RagEvalRunResult result = evaluate(
                            kbId, run.getId(), evalCase, useRerank, null, null, profile);
                    RagEvalRunResult saved = resultRepository.save(result);
                    results.add(saved == null ? result : saved);
                }
            }
            long currentRevision = profileIndexStateService.currentRevision(kbId);
            boolean indexReady = profileIndexStateService.isV2Ready(kbId);
            Map<com.dupi.rag.domain.enums.RetrievalProfile, RagEvalGateDecisionResponse> decisions =
                    retrievalProfileGateService.calculate(results, runRevision, currentRevision, indexReady);
            run.setPassedCount((int) results.stream().filter(RagEvalRunResult::isPassed).count());
            run.setTotalCount(results.size());
            run.setStatus(RagEvalRunStatus.COMPLETED);
            run.setFailureMessage(null);
            run.setMetrics(metrics(cases, results));
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

    public RagEvalRunResponse run(UUID kbId, boolean useRerank, UUID profileId, RetrievalMode retrievalMode) {
        maintenanceService.assertMutationAllowed(kbId);
        if (profileId != null && retrievalMode == RetrievalMode.VECTOR) {
            throw new IllegalArgumentException("VECTOR evaluation cannot use a retrieval profile");
        }
        List<RagEvalCase> cases = caseCoordinator.loadOrSeed(kbId);
        RetrievalProfile profile = profileId == null ? null : retrievalProfileService.find(kbId, profileId);
        boolean effectiveRerank = profile == null ? useRerank : Boolean.TRUE.equals(profile.getRerankEnabled());
        RagQualityPolicy policy = getOrCreatePolicy(kbId);
        RagEvalRun baselineRun = policy.getBaselineRunId() == null
                ? null
                : runRepository.findById(policy.getBaselineRunId())
                .filter(candidate -> kbId.equals(candidate.getKbId()))
                .orElseThrow(() -> new IllegalStateException("RAG baseline run is unavailable"));
        List<RagEvalRunResult> baselineResults = baselineRun == null
                ? List.of()
                : resultRepository.findByRunIdOrderByCaseKeyAsc(baselineRun.getId());
        RagEvalRun run = RagEvalRun.builder()
                .kbId(kbId)
                .useRerank(effectiveRerank)
                .totalCount(cases.size())
                .passedCount(0)
                .status(RagEvalRunStatus.RUNNING)
                .profileSnapshot(profile == null ? Map.of("useRerank", effectiveRerank) : profile.snapshot())
                .baselineRunId(baselineRun == null ? null : baselineRun.getId())
                .policySnapshot(policySnapshot(policy))
                .createdAt(Instant.now())
                .build();
        run = runRepository.save(run);

        List<RagEvalRunResult> results = new ArrayList<>();
        try {
            for (RagEvalCase evalCase : cases) {
                results.add(evaluate(kbId, run.getId(), evalCase, effectiveRerank, profile, retrievalMode));
            }
            RagQualityGateService.ComparisonReport comparison = baselineRun == null
                    ? null
                    : qualityGateService.compareCases(toEvidence(baselineResults), toEvidence(results));
            applyComparison(results, comparison);
            for (RagEvalRunResult result : results) {
                RagEvalRunResult saved = resultRepository.save(result);
                if (saved != null && saved != result) {
                    results.set(results.indexOf(result), saved);
                }
            }
            int passed = (int) results.stream().filter(RagEvalRunResult::isPassed).count();
            run.setPassedCount(passed);
            run.setTotalCount(results.size());
            run.setStatus(RagEvalRunStatus.COMPLETED);
            run.setFailureMessage(null);
            run.setMetrics(metrics(cases, results));
            if (profile == null) run.setProfileSnapshot(retrievalSnapshot(effectiveRerank, results));
            run.setGateStatus(qualityGateService.decide(toPolicy(policy),
                    baselineRun == null ? null : summary(baselineRun), summary(run), comparison));
            run = runRepository.save(run);
            return toRunResponse(run, results, comparison);
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

    private RagEvalRunResult evaluate(UUID kbId, UUID runId, RagEvalCase evalCase, boolean useRerank,
                                      RetrievalProfile profile, RetrievalMode retrievalMode) {
        return evaluate(kbId, runId, evalCase, useRerank, profile, retrievalMode, null);
    }

    private RagEvalRunResult evaluate(
            UUID kbId,
            UUID runId,
            RagEvalCase evalCase,
            boolean useRerank,
            RetrievalProfile profile,
            RetrievalMode retrievalMode,
            com.dupi.rag.domain.enums.RetrievalProfile qualityProfile
    ) {
        long startedAt = System.nanoTime();
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
            request.setRetrievalProfile(qualityProfile);
            response = qualityProfile != null
                    ? retrievalService.retrieve(kbId, request)
                    : profile != null
                    ? retrievalService.retrieveForProfile(kbId, request, profile.getId())
                    : retrievalMode == null
                    ? retrievalService.retrieve(kbId, request)
                    : retrievalService.retrieveForEvaluation(kbId, request, retrievalMode);
            hits = response.getHits() == null ? List.of() : response.getHits();
            boolean expectsNoHits = safeMinHits(evalCase) == 0
                    && (evalCase.getExpectedFileName() == null || evalCase.getExpectedFileName().isBlank())
                    && (evalCase.getMustContainAny() == null || evalCase.getMustContainAny().isEmpty());
            hitPassed = expectsNoHits ? hits.isEmpty() : hits.size() >= safeMinHits(evalCase);
            if (expectsNoHits && !hits.isEmpty()) {
                failureReasons.add("expected no hits, got " + hits.size());
            } else if (hits.size() < safeMinHits(evalCase)) {
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
        RankEvidence ranks = rankEvidence(evalCase, hits);
        return RagEvalRunResult.builder()
                .runId(runId)
                .caseId(evalCase.getId())
                .caseKey(evalCase.getCaseKey())
                .query(evalCase.getQuery())
                .caseFingerprint(qualityGateService.fingerprint(new RagQualityGateService.CaseDefinition(
                        evalCase.getQuery(), safeMinHits(evalCase), safeTopK(evalCase),
                        evalCase.getExpectedFileName(), evalCase.getMustContainAny())))
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
                .retrievalProfile(qualityProfile == null
                        ? com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC
                        : qualityProfile)
                .fallbackReason(stringDiagnostic(diagnostics, "fallbackReason", null))
                .embeddingModel(stringDiagnostic(diagnostics, "embeddingModel", null))
                .embeddingDimension(intDiagnostic(diagnostics, "embeddingDimension"))
                .topK(safeTopK(evalCase))
                .matchedRank(ranks.matchedRank())
                .vectorRank(ranks.vectorRank())
                .sparseRank(ranks.sparseRank())
                .fusionRank(ranks.fusionRank())
                .rerankRank(ranks.rerankRank())
                .latencyMs(Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L))
                .createdAt(Instant.now())
                .build();
    }

    @Transactional
    public RagQualityPolicyResponse getPolicy(UUID kbId) {
        return toPolicyResponse(getOrCreatePolicyForUpdate(kbId));
    }

    @Transactional
    public RagQualityPolicyResponse updatePolicy(UUID kbId, RagQualityPolicyRequest request) {
        maintenanceService.assertMutationAllowed(kbId);
        knowledgeBaseService.findOrThrow(kbId);
        RagQualityPolicy policy = getOrCreatePolicyForUpdate(kbId);
        policy.setMinimumPassRate(request.getMinimumPassRate());
        policy.setMaximumPassRateDrop(request.getMaximumPassRateDrop());
        policy.setMaximumNewFailures(request.getMaximumNewFailures());
        policy.setBlockWhenUnbaselined(request.getBlockWhenUnbaselined());
        RagQualityPolicy saved = policyRepository.saveAndFlush(policy);
        auditAfterCommit("RAG_QUALITY_POLICY_UPDATE", kbId, "Updated RAG quality policy");
        return toPolicyResponse(saved == null ? policy : saved);
    }

    @Transactional
    public RagQualityPolicyResponse promoteBaseline(UUID kbId, UUID runId) {
        maintenanceService.assertMutationAllowed(kbId);
        knowledgeBaseService.findOrThrow(kbId);
        RagEvalRun run = runRepository.findById(runId)
                .filter(candidate -> kbId.equals(candidate.getKbId()))
                .orElseThrow(() -> new ResourceNotFoundException("RAG evaluation run not found: " + runId));
        if (run.getStatus() != RagEvalRunStatus.COMPLETED
                || run.getGateStatus() == RagQualityGateStatus.BLOCKED
                || run.getGateStatus() == RagQualityGateStatus.WARN) {
            throw new IllegalArgumentException("A completed run with a passing quality gate is required");
        }
        RagQualityPolicy policy = getOrCreatePolicyForUpdate(kbId);
        boolean firstBaseline = policy.getBaselineRunId() == null;
        RagQualityGateStatus currentDecision = recalculateGate(run, policy);
        boolean promotable = currentDecision == RagQualityGateStatus.PASS
                || firstBaseline && currentDecision == RagQualityGateStatus.UNBASELINED;
        if (!promotable) {
            throw new IllegalArgumentException("A completed run with a passing quality gate is required");
        }
        policy.setBaselineRunId(runId);
        RagQualityPolicy saved = policyRepository.saveAndFlush(policy);
        auditAfterCommit("RAG_BASELINE_PROMOTE", kbId, "Promoted RAG evaluation baseline " + runId);
        return toPolicyResponse(saved == null ? policy : saved);
    }

    @Transactional(readOnly = true)
    public RagEvalRunResponse getRunComparison(UUID kbId, UUID runId) {
        knowledgeBaseService.findOrThrow(kbId);
        RagEvalRun run = runRepository.findById(runId)
                .filter(candidate -> kbId.equals(candidate.getKbId()))
                .orElseThrow(() -> new ResourceNotFoundException("RAG evaluation run not found: " + runId));
        List<RagEvalRunResult> results = resultRepository.findByRunIdOrderByCaseKeyAsc(runId);
        RagQualityGateService.ComparisonReport comparison = null;
        if (run.getBaselineRunId() != null) {
            List<RagEvalRunResult> baselineResults = resultRepository
                    .findByRunIdOrderByCaseKeyAsc(run.getBaselineRunId());
            comparison = qualityGateService.compareCases(toEvidence(baselineResults), toEvidence(results));
        }
        return toRunResponse(run, results, comparison);
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

    private boolean hasExpectedFile(RagEvalCase evalCase) {
        return evalCase.getExpectedFileName() != null && !evalCase.getExpectedFileName().isBlank();
    }

    private List<com.dupi.rag.domain.enums.RetrievalProfile> normalizeProfiles(
            List<com.dupi.rag.domain.enums.RetrievalProfile> requestedProfiles
    ) {
        if (requestedProfiles == null || requestedProfiles.isEmpty()) {
            return List.of(com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC);
        }
        List<com.dupi.rag.domain.enums.RetrievalProfile> profiles = requestedProfiles.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (profiles.isEmpty()) {
            return List.of(com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC);
        }
        boolean hasCandidate = profiles.stream()
                .anyMatch(profile -> profile != com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC);
        if (!hasCandidate) {
            return List.of(com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC);
        }
        List<com.dupi.rag.domain.enums.RetrievalProfile> normalized = new ArrayList<>();
        normalized.add(com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC);
        profiles.stream()
                .filter(profile -> profile != com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC)
                .forEach(normalized::add);
        return normalized;
    }

    private RagQualityPolicy getOrCreatePolicy(UUID kbId) {
        return policyRepository.findByKbId(kbId).orElseGet(() -> {
            RagQualityPolicy policy = RagQualityPolicy.builder().kbId(kbId).build();
            try {
                RagQualityPolicy saved = policyRepository.save(policy);
                return saved == null ? policy : saved;
            } catch (DataIntegrityViolationException conflict) {
                return policyRepository.findByKbId(kbId).orElseThrow(() -> conflict);
            }
        });
    }

    private RagQualityPolicy getOrCreatePolicyForUpdate(UUID kbId) {
        knowledgeBaseService.findForUpdateOrThrow(kbId);
        return policyRepository.findByKbIdForUpdate(kbId).orElseGet(() -> {
            RagQualityPolicy policy = RagQualityPolicy.builder().kbId(kbId).build();
            return policyRepository.saveAndFlush(policy);
        });
    }

    private RagQualityGateService.Policy toPolicy(RagQualityPolicy policy) {
        return new RagQualityGateService.Policy(policy.getMinimumPassRate(), policy.getMaximumPassRateDrop(),
                policy.getMaximumNewFailures(), Boolean.TRUE.equals(policy.getBlockWhenUnbaselined()));
    }

    private RagQualityGateService.RunSummary summary(RagEvalRun run) {
        return new RagQualityGateService.RunSummary(run.getTotalCount(), run.getPassedCount());
    }

    private RagQualityGateStatus recalculateGate(RagEvalRun run, RagQualityPolicy policy) {
        List<RagEvalRunResult> currentResults = resultRepository.findByRunIdOrderByCaseKeyAsc(run.getId());
        if (policy.getBaselineRunId() == null) {
            return qualityGateService.decide(toPolicy(policy), null, summary(run), null);
        }
        RagEvalRun baseline = runRepository.findById(policy.getBaselineRunId())
                .filter(candidate -> run.getKbId().equals(candidate.getKbId()))
                .orElseThrow(() -> new IllegalStateException("RAG baseline run is unavailable"));
        List<RagEvalRunResult> baselineResults = resultRepository
                .findByRunIdOrderByCaseKeyAsc(baseline.getId());
        RagQualityGateService.ComparisonReport comparison = qualityGateService
                .compareCases(toEvidence(baselineResults), toEvidence(currentResults));
        return qualityGateService.decide(toPolicy(policy), summary(baseline), summary(run), comparison);
    }

    private Map<String, Object> policySnapshot(RagQualityPolicy policy) {
        return Map.of(
                "minimumPassRate", policy.getMinimumPassRate(),
                "maximumPassRateDrop", policy.getMaximumPassRateDrop(),
                "maximumNewFailures", policy.getMaximumNewFailures(),
                "blockWhenUnbaselined", Boolean.TRUE.equals(policy.getBlockWhenUnbaselined())
        );
    }

    private Map<String, Object> retrievalSnapshot(boolean useRerank, List<RagEvalRunResult> results) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("useRerank", useRerank);
        results.stream().findFirst().ifPresent(result -> {
            if (hasText(result.getRetrievalMode())) snapshot.put("retrievalMode", result.getRetrievalMode());
            if (hasText(result.getEmbeddingModel())) snapshot.put("embeddingModel", result.getEmbeddingModel());
            if (result.getEmbeddingDimension() != null) snapshot.put("embeddingDimension", result.getEmbeddingDimension());
            if (result.getTopK() != null) snapshot.put("topK", result.getTopK());
        });
        return Map.copyOf(snapshot);
    }

    private void auditAfterCommit(String action, UUID kbId, String message) {
        Runnable audit = () -> auditLogService.recordSuccess(action, "KNOWLEDGE_BASE", kbId, message);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            audit.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                audit.run();
            }
        });
    }

    private List<RagQualityGateService.CaseEvidence> toEvidence(List<RagEvalRunResult> results) {
        return results.stream()
                .map(result -> new RagQualityGateService.CaseEvidence(
                        result.getCaseKey(), result.getCaseFingerprint(), result.isPassed()))
                .toList();
    }

    private void applyComparison(List<RagEvalRunResult> results,
                                 RagQualityGateService.ComparisonReport comparison) {
        if (comparison == null) {
            results.forEach(result -> result.setComparisonStatus(RagEvalComparisonStatus.NEW));
            return;
        }
        Map<String, RagEvalComparisonStatus> statusByKey = comparison.currentCases().stream()
                .collect(java.util.stream.Collectors.toMap(
                        item -> item.current().caseKey(), RagQualityGateService.CaseComparison::status));
        results.forEach(result -> result.setComparisonStatus(statusByKey.get(result.getCaseKey())));
    }

    private Map<String, Object> metrics(List<RagEvalCase> cases, List<RagEvalRunResult> results) {
        int total = results.size();
        long passed = results.stream().filter(RagEvalRunResult::isPassed).count();
        Map<UUID, RagEvalCase> caseById = cases.stream()
                .collect(java.util.stream.Collectors.toMap(RagEvalCase::getId, item -> item));
        List<RagEvalRunResult> fileEligible = results.stream()
                .filter(result -> hasText(result.getExpectedFileName()))
                .toList();
        List<RagEvalRunResult> tokenEligible = results.stream()
                .filter(result -> {
                    RagEvalCase evalCase = caseById.get(result.getCaseId());
                    return evalCase != null && evalCase.getMustContainAny() != null
                            && !evalCase.getMustContainAny().isEmpty();
                })
                .toList();
        List<Long> latencies = results.stream().map(RagEvalRunResult::getLatencyMs).sorted().toList();
        return Map.of(
                "passRate", rate(passed, total),
                "eligibleExpectedFileHitRate", rate(fileEligible.stream()
                        .filter(result -> hasText(result.getMatchedFileName())).count(), fileEligible.size()),
                "eligibleKeywordHitRate", rate(tokenEligible.stream()
                        .filter(result -> hasText(result.getMatchedToken())).count(), tokenEligible.size()),
                "averageHitCount", total == 0 ? 0.0 : results.stream()
                        .mapToInt(result -> result.getHitCount() == null ? 0 : result.getHitCount()).average().orElse(0.0),
                "fallbackCount", results.stream().filter(result -> hasText(result.getFallbackReason())
                        && !"none".equalsIgnoreCase(result.getFallbackReason())).count(),
                "latencyP50Ms", percentile(latencies, 0.50),
                "latencyP95Ms", percentile(latencies, 0.95)
        );
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }

    private long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) return 0L;
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, index));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
        return toRunResponse(run, results, null);
    }

    private RagEvalRunResponse toRunResponse(RagEvalRun run, List<RagEvalRunResult> results,
                                             RagQualityGateService.ComparisonReport comparison) {
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
                .gateStatus(run.getGateStatus())
                .metrics(run.getMetrics())
                .profileSnapshot(run.getProfileSnapshot())
                .removedBaselineCaseKeys(comparison == null ? List.of() : comparison.removedBaselineCases().stream()
                        .map(RagQualityGateService.CaseEvidence::caseKey).toList())
                .baselineRunId(run.getBaselineRunId())
                .policySnapshot(run.getPolicySnapshot())
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
                .matchedRank(result.getMatchedRank())
                .vectorRank(result.getVectorRank())
                .sparseRank(result.getSparseRank())
                .fusionRank(result.getFusionRank())
                .rerankRank(result.getRerankRank())
                .caseFingerprint(result.getCaseFingerprint())
                .comparisonStatus(result.getComparisonStatus())
                .latencyMs(result.getLatencyMs())
                .build();
    }

    private Map<String, Object> toGateSummaryMap(
            Map<com.dupi.rag.domain.enums.RetrievalProfile, RagEvalGateDecisionResponse> decisions
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        decisions.forEach((profile, decision) -> summary.put(profile.wireValue(), decision));
        return summary;
    }

    private Map<com.dupi.rag.domain.enums.RetrievalProfile, RagEvalGateDecisionResponse> toGateSummaryResponse(
            Map<String, Object> rawSummary
    ) {
        if (rawSummary == null || rawSummary.isEmpty()) {
            return Map.of();
        }
        Map<com.dupi.rag.domain.enums.RetrievalProfile, RagEvalGateDecisionResponse> summary = new LinkedHashMap<>();
        rawSummary.forEach((key, value) -> {
            com.dupi.rag.domain.enums.RetrievalProfile profile =
                    com.dupi.rag.domain.enums.RetrievalProfile.fromWireValue(key);
            summary.put(profile, toGateDecision(value, profile));
        });
        return summary;
    }

    private RagEvalGateDecisionResponse toGateDecision(
            Object value,
            com.dupi.rag.domain.enums.RetrievalProfile fallbackCandidate
    ) {
        if (value instanceof RagEvalGateDecisionResponse decision) {
            return decision;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return RagEvalGateDecisionResponse.builder()
                    .candidate(fallbackCandidate)
                    .baseline(com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC)
                    .status(RagEvalGateStatus.NOT_EVALUATED)
                    .reason("not_evaluated")
                    .build();
        }
        return RagEvalGateDecisionResponse.builder()
                .candidate(profileValue(map.get("candidate"), fallbackCandidate))
                .baseline(profileValue(
                        map.get("baseline"), com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC))
                .status(statusValue(map.get("status")))
                .reason(stringValue(map.get("reason")))
                .metrics(metricsValue(map.get("metrics"), fallbackCandidate))
                .classicMetrics(metricsValue(
                        map.get("classicMetrics"), com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC))
                .hitRateDelta(doubleValue(map.get("hitRateDelta")))
                .citationPassRateDelta(doubleValue(map.get("citationPassRateDelta")))
                .runRevision(longValue(map.get("runRevision")))
                .currentRevision(longValue(map.get("currentRevision")))
                .indexReady(Boolean.TRUE.equals(map.get("indexReady")))
                .build();
    }

    private RagEvalProfileMetricsResponse metricsValue(
            Object value,
            com.dupi.rag.domain.enums.RetrievalProfile fallbackProfile
    ) {
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

    private com.dupi.rag.domain.enums.RetrievalProfile profileValue(
            Object value,
            com.dupi.rag.domain.enums.RetrievalProfile fallback
    ) {
        return value == null
                ? fallback
                : com.dupi.rag.domain.enums.RetrievalProfile.fromWireValue(String.valueOf(value));
    }

    private RagEvalGateStatus statusValue(Object value) {
        return value == null
                ? RagEvalGateStatus.NOT_EVALUATED
                : RagEvalGateStatus.valueOf(String.valueOf(value));
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

    private RankEvidence rankEvidence(RagEvalCase evalCase, List<RetrievalHit> hits) {
        for (int index = 0; index < hits.size(); index++) {
            RetrievalHit hit = hits.get(index);
            boolean matchesFile = evalCase.getExpectedFileName() != null
                    && evalCase.getExpectedFileName().equals(hit.getFileName());
            boolean matchesToken = evalCase.getMustContainAny() != null && hit.getContent() != null
                    && evalCase.getMustContainAny().stream().filter(java.util.Objects::nonNull)
                    .anyMatch(token -> hit.getContent().toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT)));
            boolean unconstrained = evalCase.getExpectedFileName() == null
                    && (evalCase.getMustContainAny() == null || evalCase.getMustContainAny().isEmpty());
            if (matchesFile || matchesToken || unconstrained) {
                Map<?, ?> stages = hit.getMetadata() != null && hit.getMetadata().get("retrievalStages") instanceof Map<?, ?> map
                        ? map : Map.of();
                return new RankEvidence(index + 1, number(stages.get("vectorRank")), number(stages.get("sparseRank")),
                        number(stages.get("fusionRank")), number(stages.get("rerankRank")));
            }
        }
        return new RankEvidence(null, null, null, null, null);
    }

    private Integer number(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private record RankEvidence(Integer matchedRank, Integer vectorRank, Integer sparseRank,
                                Integer fusionRank, Integer rerankRank) {}

    private RagQualityPolicyResponse toPolicyResponse(RagQualityPolicy policy) {
        return RagQualityPolicyResponse.builder()
                .id(policy.getId())
                .kbId(policy.getKbId())
                .minimumPassRate(policy.getMinimumPassRate())
                .maximumPassRateDrop(policy.getMaximumPassRateDrop())
                .maximumNewFailures(policy.getMaximumNewFailures())
                .blockWhenUnbaselined(policy.getBlockWhenUnbaselined())
                .baselineRunId(policy.getBaselineRunId())
                .createdAt(policy.getCreatedAt())
                .updatedAt(policy.getUpdatedAt())
                .build();
    }

}
