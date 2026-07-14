package com.dupi.rag.service;

import com.dupi.rag.domain.enums.RagEvalComparisonStatus;
import com.dupi.rag.domain.enums.RagQualityGateStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class RagQualityGateService {

    public RagQualityGateStatus decide(Policy policy, RunSummary baseline, RunSummary current) {
        return decide(policy, baseline, current, null);
    }

    public RagQualityGateStatus decide(
            Policy policy,
            RunSummary baseline,
            RunSummary current,
            ComparisonReport comparison
    ) {
        int newFailureCount = comparison == null ? current.newFailureCount() : comparison.newFailureCount();
        if (current.passRate() < policy.minimumPassRate() || newFailureCount > policy.maximumNewFailures()) {
            return RagQualityGateStatus.BLOCKED;
        }
        if (baseline == null) {
            return policy.blockWhenUnbaselined() ? RagQualityGateStatus.BLOCKED : RagQualityGateStatus.UNBASELINED;
        }
        if (baseline.passRate() - current.passRate() > policy.maximumPassRateDrop()) {
            return RagQualityGateStatus.BLOCKED;
        }
        return comparison == null || !comparison.fullyComparable()
                ? RagQualityGateStatus.WARN
                : RagQualityGateStatus.PASS;
    }

    public RagEvalComparisonStatus compare(
            String baselineCaseKey,
            String baselineFingerprint,
            String currentCaseKey,
            String currentFingerprint,
            boolean baselinePassed,
            boolean currentPassed
    ) {
        if (baselineCaseKey == null || baselineFingerprint == null
                || currentCaseKey == null || currentFingerprint == null
                || !Objects.equals(baselineCaseKey, currentCaseKey)
                || !Objects.equals(baselineFingerprint, currentFingerprint)) {
            return RagEvalComparisonStatus.NEW;
        }
        if (baselinePassed && !currentPassed) return RagEvalComparisonStatus.REGRESSED;
        if (!baselinePassed && currentPassed) return RagEvalComparisonStatus.IMPROVED;
        return RagEvalComparisonStatus.UNCHANGED;
    }

    public ComparisonReport compareCases(List<CaseEvidence> baselineCases, List<CaseEvidence> currentCases) {
        Map<String, CaseEvidence> baselineByKey = uniqueByCaseKey(baselineCases);
        Set<String> currentKeys = new HashSet<>();
        List<CaseComparison> comparisons = new ArrayList<>();
        int newFailureCount = 0;

        for (CaseEvidence current : currentCases) {
            if (!currentKeys.add(current.caseKey())) {
                throw new IllegalArgumentException("Duplicate current case key: " + current.caseKey());
            }
            CaseEvidence baseline = baselineByKey.get(current.caseKey());
            RagEvalComparisonStatus status = baseline == null
                    ? RagEvalComparisonStatus.NEW
                    : compare(baseline.caseKey(), baseline.fingerprint(), current.caseKey(), current.fingerprint(),
                    baseline.passed(), current.passed());
            if (status == RagEvalComparisonStatus.REGRESSED) newFailureCount++;
            comparisons.add(new CaseComparison(current, status));
        }

        List<CaseEvidence> removed = baselineCases.stream()
                .filter(candidate -> !currentKeys.contains(candidate.caseKey()))
                .toList();
        boolean fullyComparable = removed.isEmpty()
                && comparisons.stream().noneMatch(candidate -> candidate.status() == RagEvalComparisonStatus.NEW);
        return new ComparisonReport(List.copyOf(comparisons), removed, newFailureCount, fullyComparable);
    }

    public String fingerprint(CaseDefinition definition) {
        List<String> tokens = definition.mustContainAny() == null
                ? List.of()
                : definition.mustContainAny().stream()
                .map(this::normalizeText)
                .sorted(Comparator.naturalOrder())
                .toList();
        StringBuilder canonical = new StringBuilder();
        append(canonical, normalizeText(definition.query()));
        append(canonical, Integer.toString(definition.minHits()));
        append(canonical, Integer.toString(definition.topK()));
        append(canonical, normalizeText(definition.expectedFileName()));
        tokens.forEach(token -> append(canonical, token));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Map<String, CaseEvidence> uniqueByCaseKey(List<CaseEvidence> cases) {
        Map<String, CaseEvidence> byKey = new HashMap<>();
        for (CaseEvidence evidence : cases) {
            if (byKey.putIfAbsent(evidence.caseKey(), evidence) != null) {
                throw new IllegalArgumentException("Duplicate baseline case key: " + evidence.caseKey());
            }
        }
        return byKey;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    public record Policy(int minimumPassRate, int maximumPassRateDrop, int maximumNewFailures,
                         boolean blockWhenUnbaselined) { }

    public record RunSummary(int totalCount, int passedCount, int newFailureCount) {
        public RunSummary(int totalCount, int passedCount) {
            this(totalCount, passedCount, 0);
        }

        double passRate() {
            return totalCount == 0 ? 0 : passedCount * 100.0 / totalCount;
        }
    }

    public record CaseDefinition(String query, int minHits, int topK, String expectedFileName,
                                 List<String> mustContainAny) { }

    public record CaseEvidence(String caseKey, String fingerprint, boolean passed) { }

    public record CaseComparison(CaseEvidence current, RagEvalComparisonStatus status) { }

    public record ComparisonReport(List<CaseComparison> currentCases, List<CaseEvidence> removedBaselineCases,
                                   int newFailureCount, boolean fullyComparable) { }
}
