package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.entity.RagEvalRunResult;
import com.dupi.rag.domain.entity.RagQualityPolicy;
import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.enums.RagEvalCaseCategory;
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
import com.dupi.rag.dto.RagEvalRunRequest;
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

    public RagEvalRunResponse run(UUID kbId, RagEvalRunRequest request) {
        RagEvalRunRequest effective = request == null ? new RagEvalRunRequest() : request;
        boolean useRerank = Boolean.TRUE.equals(effective.getUseRerank());
        return run(kbId, useRerank, effective.getProfiles(),
                effective.getTopKOverride(), blankToNull(effective.getExperimentLabel()));
    }

    public RagEvalRunResponse run(UUID kbId, boolean useRerank, UUID profileId) {
        return run(kbId, useRerank, profileId, null);
    }

    public RagEvalRunResponse run(
            UUID kbId,
            boolean useRerank,
            List<com.dupi.rag.domain.enums.RetrievalProfile> requestedProfiles
    ) {
        return run(kbId, useRerank, requestedProfiles, null, null);
    }

    private RagEvalRunResponse run(
            UUID kbId,
            boolean useRerank,
            List<com.dupi.rag.domain.enums.RetrievalProfile> requestedProfiles,
            Integer topKOverride,
            String experimentLabel
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
                .profileSnapshot(profileSetSnapshot(profiles, useRerank, topKOverride, experimentLabel))
                .createdAt(Instant.now())
                .build();
        run = runRepository.save(run);

        List<RagEvalRunResult> results = new ArrayList<>();
        try {
            for (com.dupi.rag.domain.enums.RetrievalProfile profile : profiles) {
                for (RagEvalCase evalCase : cases) {
                    RagEvalRunResult result = evaluate(
                            kbId, run.getId(), evalCase, useRerank, null, null, profile, topKOverride);
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
        return run(kbId, useRerank, profileId, retrievalMode, null, null);
    }

    private RagEvalRunResponse run(UUID kbId, boolean useRerank, UUID profileId, RetrievalMode retrievalMode,
                                   Integer topKOverride, String experimentLabel) {
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
                .profileSnapshot(withExperimentMetadata(
                        profile == null ? Map.of("useRerank", effectiveRerank) : profile.snapshot(),
                        topKOverride, experimentLabel))
                .baselineRunId(baselineRun == null ? null : baselineRun.getId())
                .policySnapshot(policySnapshot(policy))
                .createdAt(Instant.now())
                .build();
        run = runRepository.save(run);

        List<RagEvalRunResult> results = new ArrayList<>();
        try {
            for (RagEvalCase evalCase : cases) {
                results.add(evaluate(kbId, run.getId(), evalCase, effectiveRerank, profile, retrievalMode,
                        null, topKOverride));
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
            if (profile == null) run.setProfileSnapshot(retrievalSnapshot(effectiveRerank, results,
                    topKOverride, experimentLabel));
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
        return evaluate(kbId, runId, evalCase, useRerank, profile, retrievalMode, null, null);
    }

    private RagEvalRunResult evaluate(
            UUID kbId,
            UUID runId,
            RagEvalCase evalCase,
            boolean useRerank,
            RetrievalProfile profile,
            RetrievalMode retrievalMode,
            com.dupi.rag.domain.enums.RetrievalProfile qualityProfile,
            Integer topKOverride
    ) {
        long startedAt = System.nanoTime();
        int actualTopK = topKOverride == null ? safeTopK(evalCase) : topKOverride;
        List<String> failureReasons = new ArrayList<>();
        List<String> failureCategories = new ArrayList<>();
        RetrieveResponse response = null;
        List<RetrievalHit> hits = List.of();
        String matchedFile = null;
        List<String> matchedFiles = List.of();
        String matchedToken = null;
        boolean hitPassed = false;
        boolean citationEligible = hasExpectedFile(evalCase);
        boolean citationPassed = false;

        try {
            RetrieveRequest request = new RetrieveRequest();
            request.setQuery(evalCase.getQuery());
            request.setTopK(actualTopK);
            request.setUseRerank(useRerank);
            request.setRetrievalProfile(qualityProfile);
            response = retrieveForEvaluationTarget(kbId, request,
                    new EvaluationTarget(profile, retrievalMode, qualityProfile));
            hits = response.getHits() == null ? List.of() : response.getHits();
            boolean expectsNoHits = safeMinHits(evalCase) == 0
                    && expectedFiles(evalCase).isEmpty()
                    && (evalCase.getMustContainAny() == null || evalCase.getMustContainAny().isEmpty());
            hitPassed = expectsNoHits ? hits.isEmpty() : hits.size() >= safeMinHits(evalCase);
            if (expectsNoHits && !hits.isEmpty()) {
                failureReasons.add("expected no hits, got " + hits.size());
                failureCategories.add("UNEXPECTED_EVIDENCE");
            } else if (hits.size() < safeMinHits(evalCase)) {
                failureReasons.add("expected at least " + safeMinHits(evalCase) + " hits, got " + hits.size());
                failureCategories.add("INSUFFICIENT_HITS");
            }
            matchedFiles = matchFiles(evalCase, hits, failureReasons, failureCategories);
            matchedFile = matchedFiles.isEmpty() ? null : matchedFiles.get(0);
            citationPassed = citationEligible && matchedFiles.size() == expectedFiles(evalCase).size();
            matchedToken = matchToken(evalCase, hits, failureReasons, failureCategories);
        } catch (Exception ex) {
            failureReasons.add(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            failureCategories.add("RETRIEVAL_EXCEPTION");
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
                        evalCase.getQuery(), safeMinHits(evalCase), actualTopK,
                        evalCase.getExpectedFileName(), evalCase.getMustContainAny(),
                        evalCase.getCategory(), evalCase.getExpectedFileNames())))
                .passed(failureReasons.isEmpty())
                .hitPassed(hitPassed)
                .citationEligible(citationEligible)
                .citationPassed(citationPassed)
                .failureReasons(failureReasons)
                .failureCategories(failureCategories.stream().distinct().toList())
                .hitCount(hits.size())
                .category(evalCase.getCategory())
                .expectedFileName(evalCase.getExpectedFileName())
                .expectedFileNames(evalCase.getExpectedFileNames())
                .matchedFileName(matchedFile)
                .matchedFileNames(matchedFiles)
                .matchedToken(matchedToken)
                .retrievalMode(stringDiagnostic(diagnostics, "retrievalMode", response == null ? null : response.getRetrievalMode()))
                .retrievalProfile(qualityProfile == null
                        ? com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC
                        : qualityProfile)
                .fallbackReason(stringDiagnostic(diagnostics, "fallbackReason", null))
                .embeddingModel(stringDiagnostic(diagnostics, "embeddingModel", null))
                .embeddingDimension(intDiagnostic(diagnostics, "embeddingDimension"))
                .topK(actualTopK)
                .matchedRank(ranks.matchedRank())
                .vectorRank(ranks.vectorRank())
                .sparseRank(ranks.sparseRank())
                .fusionRank(ranks.fusionRank())
                .rerankRank(ranks.rerankRank())
                .latencyMs(Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L))
                .createdAt(Instant.now())
                .build();
    }

    private RetrieveResponse retrieveForEvaluationTarget(
            UUID kbId,
            RetrieveRequest request,
            EvaluationTarget target
    ) {
        if (target.qualityProfile() != null) {
            return retrievalService.retrieve(kbId, request);
        }
        if (target.profile() != null) {
            return retrievalService.retrieveForProfile(kbId, request, target.profile().getId());
        }
        if (target.retrievalMode() != null) {
            return retrievalService.retrieveForEvaluation(kbId, request, target.retrievalMode());
        }
        return retrievalService.retrieve(kbId, request);
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

    private List<String> matchFiles(RagEvalCase evalCase, List<RetrievalHit> hits, List<String> failureReasons,
                                    List<String> failureCategories) {
        List<String> expectedFiles = expectedFiles(evalCase);
        if (expectedFiles.isEmpty()) {
            return List.of();
        }
        List<String> hitFiles = hits.stream()
                .map(RetrievalHit::getFileName)
                .filter(fileName -> fileName != null && !fileName.isBlank())
                .distinct()
                .toList();
        List<String> matchedFiles = expectedFiles.stream().filter(hitFiles::contains).toList();
        List<String> missingFiles = expectedFiles.stream().filter(fileName -> !hitFiles.contains(fileName)).toList();
        if (!missingFiles.isEmpty()) {
            failureReasons.add(expectedFiles.size() == 1
                    ? "missing expected file " + missingFiles.get(0)
                    : "missing expected files " + String.join(", ", missingFiles));
            failureCategories.add("MISSING_EXPECTED_FILE");
        }
        return matchedFiles;
    }

    private String matchToken(RagEvalCase evalCase, List<RetrievalHit> hits, List<String> failureReasons,
                              List<String> failureCategories) {
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
        failureCategories.add("MISSING_EXPECTED_TOKEN");
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
        return !expectedFiles(evalCase).isEmpty();
    }

    private List<String> expectedFiles(RagEvalCase evalCase) {
        List<String> additional = evalCase.getExpectedFileNames() == null
                ? List.of()
                : evalCase.getExpectedFileNames();
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(evalCase.getExpectedFileName()), additional.stream())
                .filter(fileName -> fileName != null && !fileName.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
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
        return retrievalSnapshot(useRerank, results, null, null);
    }

    private Map<String, Object> retrievalSnapshot(
            boolean useRerank,
            List<RagEvalRunResult> results,
            Integer topKOverride,
            String experimentLabel
    ) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("useRerank", useRerank);
        results.stream().findFirst().ifPresent(result -> {
            if (hasText(result.getRetrievalMode())) snapshot.put("retrievalMode", result.getRetrievalMode());
            if (hasText(result.getEmbeddingModel())) snapshot.put("embeddingModel", result.getEmbeddingModel());
            if (result.getEmbeddingDimension() != null) snapshot.put("embeddingDimension", result.getEmbeddingDimension());
            if (result.getTopK() != null) snapshot.put("topK", result.getTopK());
        });
        return withExperimentMetadata(snapshot, topKOverride, experimentLabel);
    }

    private Map<String, Object> profileSetSnapshot(
            List<com.dupi.rag.domain.enums.RetrievalProfile> profiles,
            boolean useRerank,
            Integer topKOverride,
            String experimentLabel
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("profiles", profiles.stream()
                .map(com.dupi.rag.domain.enums.RetrievalProfile::wireValue)
                .toList());
        snapshot.put("useRerank", useRerank);
        return withExperimentMetadata(snapshot, topKOverride, experimentLabel);
    }

    private Map<String, Object> withExperimentMetadata(
            Map<String, Object> snapshot,
            Integer topKOverride,
            String experimentLabel
    ) {
        Map<String, Object> enriched = new LinkedHashMap<>(snapshot);
        if (topKOverride != null) {
            enriched.put("topKOverride", topKOverride);
        }
        if (hasText(experimentLabel)) {
            enriched.put("experimentLabel", experimentLabel.trim());
        }
        if (enriched.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(enriched);
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
                .filter(result -> hasText(result.getExpectedFileName())
                        || result.getExpectedFileNames() != null && result.getExpectedFileNames().stream().anyMatch(this::hasText))
                .toList();
        List<RagEvalRunResult> tokenEligible = results.stream()
                .filter(result -> {
                    RagEvalCase evalCase = caseById.get(result.getCaseId());
                    return evalCase != null && evalCase.getMustContainAny() != null
                            && !evalCase.getMustContainAny().isEmpty();
                })
                .toList();
        List<Long> latencies = results.stream().map(RagEvalRunResult::getLatencyMs).sorted().toList();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("passRate", rate(passed, total));
        metrics.put("eligibleExpectedFileHitRate", rate(fileEligible.stream()
                .filter(RagEvalRunResult::isCitationPassed).count(), fileEligible.size()));
        metrics.put("eligibleKeywordHitRate", rate(tokenEligible.stream()
                .filter(result -> hasText(result.getMatchedToken())).count(), tokenEligible.size()));
        metrics.put("averageHitCount", total == 0 ? 0.0 : results.stream()
                .mapToInt(result -> result.getHitCount() == null ? 0 : result.getHitCount()).average().orElse(0.0));
        metrics.put("fallbackCount", results.stream().filter(result -> hasText(result.getFallbackReason())
                && !"none".equalsIgnoreCase(result.getFallbackReason())).count());
        metrics.put("failureCategoryCounts", failureCategoryCounts(results));
        metrics.put("latencyP50Ms", percentile(latencies, 0.50));
        metrics.put("latencyP95Ms", percentile(latencies, 0.95));
        metrics.put("categorySummaries", categorySummaries(results));
        metrics.put("profileSummaries", profileSummaries(results));
        metrics.put("profileComparisons", profileComparisons(results));
        metrics.put("releaseGate", releaseGate(results));
        metrics.put("releaseReadiness", releaseReadiness(results, metrics));
        metrics.put("realQueryFeedback", realQueryFeedback(results));
        metrics.put("experimentMatrix", experimentMatrix(results));
        metrics.put("answerQuality", answerQuality(results));
        metrics.put("onlineObservability", onlineObservability(results));
        metrics.put("dataIndexGovernance", dataIndexGovernance(results));
        return metrics;
    }

    private Map<String, Long> failureCategoryCounts(List<RagEvalRunResult> results) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (RagEvalRunResult result : results) {
            List<String> categories = result.getFailureCategories() == null
                    ? List.of()
                    : result.getFailureCategories();
            for (String category : categories) {
                if (category == null || category.isBlank()) {
                    continue;
                }
                counts.merge(category, 1L, Long::sum);
            }
        }
        return counts;
    }

    private Map<String, Object> categorySummaries(List<RagEvalRunResult> results) {
        Map<String, List<RagEvalRunResult>> grouped = new LinkedHashMap<>();
        for (RagEvalRunResult result : results) {
            String key = result.getCategory() == null
                    ? RagEvalCaseCategory.REAL_QUERY.name()
                    : result.getCategory().name();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
        }
        Map<String, Object> summaries = new LinkedHashMap<>();
        grouped.forEach((category, items) -> summaries.put(category, summaryMetrics(items, false)));
        return summaries;
    }

    private Map<String, Object> profileSummaries(List<RagEvalRunResult> results) {
        Map<String, Object> summaries = new LinkedHashMap<>();
        profileGroups(results).forEach((profile, items) -> summaries.put(profile, summaryMetrics(items, true)));
        return summaries;
    }

    private Map<String, Object> profileComparisons(List<RagEvalRunResult> results) {
        Map<String, List<RagEvalRunResult>> groups = profileGroups(results);
        List<RagEvalRunResult> classicResults = groups.get(com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC.wireValue());
        if (classicResults == null || classicResults.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> classic = summaryMetrics(classicResults, true);
        Map<String, Object> comparisons = new LinkedHashMap<>();
        groups.forEach((profile, items) -> {
            if (com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC.wireValue().equals(profile)) {
                return;
            }
            Map<String, Object> candidate = summaryMetrics(items, true);
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("baseline", com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC.wireValue());
            comparison.put("candidate", profile);
            comparison.put("passRateDelta", doubleValue(candidate.get("passRate")) - doubleValue(classic.get("passRate")));
            comparison.put("hitRateDelta", doubleValue(candidate.get("hitRate")) - doubleValue(classic.get("hitRate")));
            comparison.put("citationRateDelta", doubleValue(candidate.get("citationRate")) - doubleValue(classic.get("citationRate")));
            comparison.put("latencyP95MsDelta", longValue(candidate.get("latencyP95Ms")) - longValue(classic.get("latencyP95Ms")));
            comparison.put("fallbackCountDelta", intValue(candidate.get("fallbackCount")) - intValue(classic.get("fallbackCount")));
            comparisons.put(profile, comparison);
        });
        return comparisons;
    }

    private Map<String, Object> releaseGate(List<RagEvalRunResult> results) {
        int total = results.size();
        int passed = (int) results.stream().filter(RagEvalRunResult::isPassed).count();
        Map<String, Long> failureCounts = failureCategoryCounts(results);
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("status", total == 0 ? "NO_CASES" : passed == total && failureCounts.isEmpty() ? "PASS" : "BLOCKED");
        gate.put("passRate", rateFraction(passed, total));
        gate.put("passed", passed);
        gate.put("total", total);
        gate.put("failureCategoryCounts", failureCounts);
        gate.put("categoryBlockers", categorySummaries(results).entrySet().stream()
                .filter(entry -> doubleValue(((Map<?, ?>) entry.getValue()).get("passRate")) < 1.0)
                .map(Map.Entry::getKey)
                .toList());
        gate.put("profileGateBlockers", profileComparisons(results).entrySet().stream()
                .filter(entry -> doubleValue(((Map<?, ?>) entry.getValue()).get("passRateDelta")) < 0.0)
                .map(Map.Entry::getKey)
                .toList());
        return gate;
    }

    private Map<String, Object> releaseReadiness(List<RagEvalRunResult> results, Map<String, Object> metrics) {
        Map<?, ?> gate = metrics.get("releaseGate") instanceof Map<?, ?> map ? map : Map.of();
        int blockerCount = collectionSize(gate.get("categoryBlockers")) + collectionSize(gate.get("profileGateBlockers"))
                + failureCategoryCounts(results).size();
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("version", "V1.9");
        readiness.put("status", stringValue(gate.get("status")) == null ? "NO_CASES" : stringValue(gate.get("status")));
        readiness.put("readinessScore", Math.max(0.0, rateFraction(
                results.stream().filter(RagEvalRunResult::isPassed).count(), results.size()) * 100.0 - blockerCount));
        readiness.put("blockerCount", blockerCount);
        readiness.put("requiredEvidence", List.of("categorySummaries", "profileSummaries", "releaseGate",
                "profileComparisons", "failureCategoryCounts"));
        readiness.put("artifactKeys", List.of("metrics", "profileSnapshot", "results", "gateSummary"));
        readiness.put("recommendedAction", blockerCount == 0 ? "promote_or_release" : "triage_blockers_before_release");
        return readiness;
    }

    private Map<String, Object> realQueryFeedback(List<RagEvalRunResult> results) {
        List<Map<String, Object>> candidates = results.stream()
                .filter(result -> !result.isPassed() || !result.isCitationPassed()
                        || hasText(result.getFallbackReason()))
                .limit(25)
                .map(result -> {
                    Map<String, Object> candidate = new LinkedHashMap<>();
                    candidate.put("caseKey", result.getCaseKey());
                    candidate.put("query", result.getQuery());
                    candidate.put("category", result.getCategory() == null ? null : result.getCategory().name());
                    candidate.put("failureCategories", result.getFailureCategories());
                    candidate.put("suggestedAction", result.isPassed() ? "review_online_signal" : "promote_to_challenge_case");
                    return candidate;
                })
                .toList();
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("version", "V2.0");
        feedback.put("source", "rag_eval_failures_and_degraded_signals");
        feedback.put("candidateCount", candidates.size());
        feedback.put("candidates", candidates);
        feedback.put("categoryBreakdown", categorySummaries(results));
        return feedback;
    }

    private Map<String, Object> experimentMatrix(List<RagEvalRunResult> results) {
        Map<String, Object> matrix = new LinkedHashMap<>();
        matrix.put("version", "V2.1");
        matrix.put("topKValues", results.stream()
                .map(RagEvalRunResult::getTopK)
                .filter(value -> value != null && value > 0)
                .distinct()
                .sorted()
                .toList());
        matrix.put("profiles", profileGroups(results).keySet().stream().sorted().toList());
        matrix.put("retrievalModes", results.stream()
                .map(RagEvalRunResult::getRetrievalMode)
                .filter(this::hasText)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList());
        matrix.put("caseCount", (int) results.stream().map(RagEvalRunResult::getCaseKey).distinct().count());
        matrix.put("evaluationCount", results.size());
        matrix.put("rerankEvidenceCount", (int) results.stream().filter(result -> result.getRerankRank() != null).count());
        return matrix;
    }

    private Map<String, Object> answerQuality(List<RagEvalRunResult> results) {
        List<RagEvalRunResult> citationEligible = results.stream().filter(RagEvalRunResult::isCitationEligible).toList();
        int citationPassed = (int) citationEligible.stream().filter(RagEvalRunResult::isCitationPassed).count();
        int hallucinationRisk = (int) results.stream()
                .filter(result -> !result.isCitationPassed() && result.isCitationEligible()
                        || result.getCategory() == RagEvalCaseCategory.HARD_NEGATIVE && !result.isHitPassed())
                .count();
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("version", "V2.2");
        quality.put("citationEligibleCount", citationEligible.size());
        quality.put("citationPassedCount", citationPassed);
        quality.put("groundedPassRate", rateFraction(citationPassed, citationEligible.size()));
        quality.put("hallucinationRiskCount", hallucinationRisk);
        quality.put("unsupportedAnswerRiskCount", hallucinationRisk
                + (int) results.stream().filter(result -> !result.isHitPassed()
                && result.getCategory() != RagEvalCaseCategory.HARD_NEGATIVE).count());
        return quality;
    }

    private Map<String, Object> onlineObservability(List<RagEvalRunResult> results) {
        int fallbackCount = (int) results.stream().filter(result -> hasText(result.getFallbackReason())
                && !"none".equalsIgnoreCase(result.getFallbackReason())).count();
        int noAnswerCases = (int) results.stream()
                .filter(result -> result.getCategory() == RagEvalCaseCategory.HARD_NEGATIVE).count();
        List<Long> latencies = results.stream().map(RagEvalRunResult::getLatencyMs).sorted().toList();
        Map<String, Object> observability = new LinkedHashMap<>();
        observability.put("version", "V2.3");
        observability.put("fallbackCount", fallbackCount);
        observability.put("fallbackRate", rateFraction(fallbackCount, results.size()));
        observability.put("noAnswerCaseCount", noAnswerCases);
        observability.put("noAnswerCorrectnessRate", rateFraction(results.stream()
                .filter(result -> result.getCategory() == RagEvalCaseCategory.HARD_NEGATIVE)
                .filter(RagEvalRunResult::isHitPassed).count(), noAnswerCases));
        observability.put("latencyP50Ms", percentile(latencies, 0.50));
        observability.put("latencyP95Ms", percentile(latencies, 0.95));
        observability.put("degradedProfileCount", (int) profileComparisons(results).values().stream()
                .filter(value -> value instanceof Map<?, ?> map && doubleValue(map.get("passRateDelta")) < 0.0)
                .count());
        return observability;
    }

    private Map<String, Object> dataIndexGovernance(List<RagEvalRunResult> results) {
        int expectedSources = results.stream().mapToInt(this::expectedSourceCount).sum();
        int matchedExpectedSources = results.stream().mapToInt(this::matchedExpectedSourceCount).sum();
        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("version", "V2.4");
        governance.put("expectedSourceCount", expectedSources);
        governance.put("matchedExpectedSourceCount", matchedExpectedSources);
        governance.put("expectedSourceCoverageRate", rateFraction(matchedExpectedSources, expectedSources));
        governance.put("missingSourceCount", Math.max(0, expectedSources - matchedExpectedSources));
        governance.put("multiDocumentCaseCount", (int) results.stream()
                .filter(result -> result.getCategory() == RagEvalCaseCategory.MULTI_DOCUMENT).count());
        governance.put("ambiguousCaseCount", (int) results.stream()
                .filter(result -> result.getCategory() == RagEvalCaseCategory.AMBIGUOUS).count());
        governance.put("embeddingDimensions", results.stream()
                .map(RagEvalRunResult::getEmbeddingDimension)
                .filter(value -> value != null && value > 0)
                .distinct()
                .sorted()
                .toList());
        return governance;
    }

    private int expectedSourceCount(RagEvalRunResult result) {
        return expectedSourceNames(result).size();
    }

    private int matchedExpectedSourceCount(RagEvalRunResult result) {
        List<String> expected = expectedSourceNames(result);
        if (expected.isEmpty()) {
            return 0;
        }
        List<String> matched = result.getMatchedFileNames() == null ? List.of() : result.getMatchedFileNames();
        return (int) expected.stream().filter(matched::contains).count();
    }

    private List<String> expectedSourceNames(RagEvalRunResult result) {
        List<String> names = new ArrayList<>();
        if (hasText(result.getExpectedFileName())) {
            names.add(result.getExpectedFileName());
        }
        if (result.getExpectedFileNames() != null) {
            result.getExpectedFileNames().stream().filter(this::hasText).forEach(names::add);
        }
        return names.stream().distinct().toList();
    }

    private Map<String, List<RagEvalRunResult>> profileGroups(List<RagEvalRunResult> results) {
        Map<String, List<RagEvalRunResult>> grouped = new LinkedHashMap<>();
        for (RagEvalRunResult result : results) {
            com.dupi.rag.domain.enums.RetrievalProfile profile = result.getRetrievalProfile() == null
                    ? com.dupi.rag.domain.enums.RetrievalProfile.CLASSIC
                    : result.getRetrievalProfile();
            grouped.computeIfAbsent(profile.wireValue(), ignored -> new ArrayList<>()).add(result);
        }
        return grouped;
    }

    private Map<String, Object> summaryMetrics(List<RagEvalRunResult> results, boolean profileSummary) {
        int total = results.size();
        int passed = (int) results.stream().filter(RagEvalRunResult::isPassed).count();
        int hitPassed = (int) results.stream().filter(RagEvalRunResult::isHitPassed).count();
        List<RagEvalRunResult> citationEligible = results.stream().filter(RagEvalRunResult::isCitationEligible).toList();
        int citationPassed = (int) citationEligible.stream().filter(RagEvalRunResult::isCitationPassed).count();
        List<Long> latencies = results.stream().map(RagEvalRunResult::getLatencyMs).sorted().toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("passed", passed);
        summary.put("passRate", rateFraction(passed, total));
        summary.put(profileSummary ? "hitRate" : "hitPassRate", rateFraction(hitPassed, total));
        summary.put(profileSummary ? "citationRate" : "citationPassRate",
                rateFraction(citationPassed, citationEligible.size()));
        summary.put("avgHitCount", total == 0 ? 0.0 : results.stream()
                .mapToInt(result -> result.getHitCount() == null ? 0 : result.getHitCount())
                .average().orElse(0.0));
        summary.put("latencyP50Ms", percentile(latencies, 0.50));
        summary.put("latencyP95Ms", percentile(latencies, 0.95));
        summary.put("fallbackCount", (int) results.stream().filter(result -> hasText(result.getFallbackReason())
                && !"none".equalsIgnoreCase(result.getFallbackReason())).count());
        summary.put("failureCategoryCounts", failureCategoryCounts(results));
        return summary;
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }

    private double rateFraction(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
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
        evalCase.setCategory(request.getCategory() == null ? RagEvalCaseCategory.REAL_QUERY : request.getCategory());
        evalCase.setExpectedFileName(blankToNull(request.getExpectedFileName()));
        evalCase.setExpectedFileNames(normalizeStrings(request.getExpectedFileNames()));
        evalCase.setMustContainAny(request.getMustContainAny() == null ? List.of() : request.getMustContainAny());
    }

    private List<String> normalizeStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
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
                .category(evalCase.getCategory())
                .expectedFileName(evalCase.getExpectedFileName())
                .expectedFileNames(evalCase.getExpectedFileNames())
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
                .failureCategories(result.getFailureCategories())
                .hitPassed(result.isHitPassed())
                .citationEligible(result.isCitationEligible())
                .citationPassed(result.isCitationPassed())
                .hitCount(result.getHitCount())
                .category(result.getCategory())
                .expectedFileName(result.getExpectedFileName())
                .expectedFileNames(result.getExpectedFileNames())
                .matchedFileName(result.getMatchedFileName())
                .matchedFileNames(result.getMatchedFileNames())
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

    private int collectionSize(Object value) {
        return value instanceof java.util.Collection<?> collection ? collection.size() : 0;
    }

    private double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private RankEvidence rankEvidence(RagEvalCase evalCase, List<RetrievalHit> hits) {
        List<String> expectedFiles = expectedFiles(evalCase);
        for (int index = 0; index < hits.size(); index++) {
            RetrievalHit hit = hits.get(index);
            boolean matchesFile = hit.getFileName() != null && expectedFiles.contains(hit.getFileName());
            boolean matchesToken = evalCase.getMustContainAny() != null && hit.getContent() != null
                    && evalCase.getMustContainAny().stream().filter(java.util.Objects::nonNull)
                    .anyMatch(token -> hit.getContent().toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT)));
            boolean unconstrained = expectedFiles.isEmpty()
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

    private record EvaluationTarget(
            RetrievalProfile profile,
            RetrievalMode retrievalMode,
            com.dupi.rag.domain.enums.RetrievalProfile qualityProfile
    ) {}

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
