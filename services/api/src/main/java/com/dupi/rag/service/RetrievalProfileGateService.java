package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.entity.RagEvalRunResult;
import com.dupi.rag.domain.enums.RagEvalGateStatus;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.RetrievalProfile;
import com.dupi.rag.dto.RagEvalGateDecisionResponse;
import com.dupi.rag.dto.RagEvalProfileMetricsResponse;
import com.dupi.rag.exception.RetrievalProfileConflictException;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.RagEvalRunResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RetrievalProfileGateService {

    private static final int MIN_CASES = 3;

    private final RagEvalRunRepository runRepository;
    private final RagEvalRunResultRepository resultRepository;
    private final ProfileIndexStateService profileIndexStateService;

    public Map<RetrievalProfile, RagEvalGateDecisionResponse> calculate(
            List<RagEvalRunResult> results,
            long runRevision,
            long currentRevision,
            boolean indexReady
    ) {
        Map<RetrievalProfile, List<RagEvalRunResult>> grouped = results.stream()
                .collect(Collectors.groupingBy(
                        RagEvalRunResult::getRetrievalProfile,
                        () -> new EnumMap<>(RetrievalProfile.class),
                        Collectors.toList()
                ));
        RagEvalProfileMetricsResponse classic = metrics(
                RetrievalProfile.CLASSIC,
                grouped.getOrDefault(RetrievalProfile.CLASSIC, List.of())
        );
        Map<RetrievalProfile, RagEvalGateDecisionResponse> decisions = new EnumMap<>(RetrievalProfile.class);
        for (var entry : grouped.entrySet()) {
            RetrievalProfile candidate = entry.getKey();
            if (candidate == RetrievalProfile.CLASSIC) {
                continue;
            }
            RagEvalProfileMetricsResponse candidateMetrics = metrics(candidate, entry.getValue());
            decisions.put(candidate, decision(
                    candidate,
                    candidateMetrics,
                    classic,
                    runRevision,
                    currentRevision,
                    indexReady
            ));
        }
        return decisions;
    }

    public RagEvalGateDecisionResponse latestDecision(UUID kbId, RetrievalProfile candidate) {
        if (candidate == RetrievalProfile.CLASSIC) {
            return notEvaluated(candidate).toBuilder()
                    .status(RagEvalGateStatus.PASSED)
                    .reason("classic_profile")
                    .build();
        }
        return runRepository.findTopByKbIdAndStatusOrderByCreatedAtDesc(kbId, RagEvalRunStatus.COMPLETED)
                .map(run -> latestDecision(kbId, candidate, run))
                .orElseGet(() -> notEvaluated(candidate));
    }

    public void assertCanActivate(UUID kbId, RetrievalProfile candidate) {
        if (candidate == RetrievalProfile.CLASSIC) {
            return;
        }
        RagEvalGateDecisionResponse decision = latestDecision(kbId, candidate);
        if (decision.getStatus() != RagEvalGateStatus.PASSED) {
            throw RetrievalProfileConflictException.gateBlocked(decision);
        }
    }

    private RagEvalGateDecisionResponse latestDecision(UUID kbId, RetrievalProfile candidate, RagEvalRun run) {
        long runRevision = run.getIndexRevision() == null ? -1L : run.getIndexRevision();
        Map<RetrievalProfile, RagEvalGateDecisionResponse> decisions = calculate(
                resultRepository.findByRunIdOrderByCaseKeyAsc(run.getId()),
                runRevision,
                profileIndexStateService.currentRevision(kbId),
                profileIndexStateService.isV2Ready(kbId)
        );
        return decisions.getOrDefault(candidate, notEvaluated(candidate));
    }

    private RagEvalGateDecisionResponse decision(
            RetrievalProfile candidate,
            RagEvalProfileMetricsResponse candidateMetrics,
            RagEvalProfileMetricsResponse classicMetrics,
            long runRevision,
            long currentRevision,
            boolean indexReady
    ) {
        RagEvalGateStatus status;
        String reason;
        if (!indexReady) {
            status = RagEvalGateStatus.INDEX_NOT_READY;
            reason = "profile_index_not_ready";
        } else if (runRevision != currentRevision) {
            status = RagEvalGateStatus.STALE;
            reason = "index_revision_changed";
        } else if (classicMetrics.getTotalCases() < MIN_CASES || candidateMetrics.getTotalCases() < MIN_CASES) {
            status = RagEvalGateStatus.INSUFFICIENT_DATA;
            reason = "not_enough_cases";
        } else if (classicMetrics.getCitationEligibleCount() == 0 || candidateMetrics.getCitationEligibleCount() == 0) {
            status = RagEvalGateStatus.INSUFFICIENT_DATA;
            reason = "no_citation_cases";
        } else if (candidateMetrics.getHitRate() < classicMetrics.getHitRate()) {
            status = RagEvalGateStatus.BLOCKED;
            reason = "hit_rate_regressed";
        } else if (candidateMetrics.getCitationPassRate() < classicMetrics.getCitationPassRate()) {
            status = RagEvalGateStatus.BLOCKED;
            reason = "citation_quality_regressed";
        } else {
            status = RagEvalGateStatus.PASSED;
            reason = "passed";
        }
        return RagEvalGateDecisionResponse.builder()
                .candidate(candidate)
                .baseline(RetrievalProfile.CLASSIC)
                .status(status)
                .reason(reason)
                .metrics(candidateMetrics)
                .classicMetrics(classicMetrics)
                .hitRateDelta(candidateMetrics.getHitRate() - classicMetrics.getHitRate())
                .citationPassRateDelta(candidateMetrics.getCitationPassRate() - classicMetrics.getCitationPassRate())
                .runRevision(runRevision)
                .currentRevision(currentRevision)
                .indexReady(indexReady)
                .build();
    }

    private RagEvalProfileMetricsResponse metrics(RetrievalProfile profile, List<RagEvalRunResult> results) {
        int total = results.size();
        int passed = (int) results.stream().filter(RagEvalRunResult::isPassed).count();
        int hitPassed = (int) results.stream().filter(RagEvalRunResult::isHitPassed).count();
        int citationEligible = (int) results.stream().filter(RagEvalRunResult::isCitationEligible).count();
        int citationPassed = (int) results.stream().filter(RagEvalRunResult::isCitationPassed).count();
        return RagEvalProfileMetricsResponse.builder()
                .profile(profile)
                .totalCases(total)
                .passedCount(passed)
                .hitPassedCount(hitPassed)
                .citationEligibleCount(citationEligible)
                .citationPassedCount(citationPassed)
                .passRate(rate(passed, total))
                .hitRate(rate(hitPassed, total))
                .citationPassRate(rate(citationPassed, citationEligible))
                .build();
    }

    private double rate(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private RagEvalGateDecisionResponse notEvaluated(RetrievalProfile candidate) {
        RagEvalProfileMetricsResponse empty = metrics(candidate, List.of());
        return RagEvalGateDecisionResponse.builder()
                .candidate(candidate)
                .baseline(RetrievalProfile.CLASSIC)
                .status(RagEvalGateStatus.NOT_EVALUATED)
                .reason("not_evaluated")
                .metrics(empty)
                .classicMetrics(metrics(RetrievalProfile.CLASSIC, List.of()))
                .hitRateDelta(0.0)
                .citationPassRateDelta(0.0)
                .build();
    }
}
