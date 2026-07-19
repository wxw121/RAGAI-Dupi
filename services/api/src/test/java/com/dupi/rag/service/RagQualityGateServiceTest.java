package com.dupi.rag.service;

import com.dupi.rag.domain.enums.RagEvalComparisonStatus;
import com.dupi.rag.domain.enums.RagQualityGateStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagQualityGateServiceTest {

    private final RagQualityGateService service = new RagQualityGateService();

    @Test
    void blocksWhenPassRateDropsBeyondTheBaselinedPolicy() {
        var policy = new RagQualityGateService.Policy(80, 5, 0, false);

        var status = service.decide(policy, new RagQualityGateService.RunSummary(3, 3),
                new RagQualityGateService.RunSummary(3, 2));

        assertThat(status).isEqualTo(RagQualityGateStatus.BLOCKED);
    }

    @Test
    void reportsUnbaselinedWhenAbsolutePolicyPassesAndBlockingIsDisabled() {
        var policy = new RagQualityGateService.Policy(50, 20, 1, false);

        var status = service.decide(policy, null, new RagQualityGateService.RunSummary(3, 3));

        assertThat(status).isEqualTo(RagQualityGateStatus.UNBASELINED);
    }

    @Test
    void blocksWhenNewFailuresExceedThePolicyAllowance() {
        var policy = new RagQualityGateService.Policy(50, 20, 0, false);

        var status = service.decide(policy, new RagQualityGateService.RunSummary(3, 3),
                new RagQualityGateService.RunSummary(3, 3, 1));

        assertThat(status).isEqualTo(RagQualityGateStatus.BLOCKED);
    }

    @Test
    void treatsAChangedCaseFingerprintAsNew() {
        var status = service.compare("install", "baseline-fingerprint", "install", "current-fingerprint", true, true);

        assertThat(status).isEqualTo(RagEvalComparisonStatus.NEW);
    }

    @Test
    void warnsWhenCaseSetsAreNotFullyComparable() {
        var policy = new RagQualityGateService.Policy(50, 20, 1, false);
        var comparison = service.compareCases(
                List.of(new RagQualityGateService.CaseEvidence("kept", "v1", true),
                        new RagQualityGateService.CaseEvidence("removed", "v1", true)),
                List.of(new RagQualityGateService.CaseEvidence("kept", "v2", true)));

        var status = service.decide(policy, new RagQualityGateService.RunSummary(2, 2),
                new RagQualityGateService.RunSummary(1, 1), comparison);

        assertThat(status).isEqualTo(RagQualityGateStatus.WARN);
        assertThat(comparison.currentCases()).extracting(RagQualityGateService.CaseComparison::status)
                .containsExactly(RagEvalComparisonStatus.NEW);
        assertThat(comparison.removedBaselineCases()).extracting(RagQualityGateService.CaseEvidence::caseKey)
                .containsExactly("removed");
    }

    @Test
    void computesNewFailuresOnlyFromMatchingRegressions() {
        var comparison = service.compareCases(
                List.of(new RagQualityGateService.CaseEvidence("regressed", "same", true),
                        new RagQualityGateService.CaseEvidence("changed", "old", true)),
                List.of(new RagQualityGateService.CaseEvidence("regressed", "same", false),
                        new RagQualityGateService.CaseEvidence("changed", "new", false)));

        assertThat(comparison.newFailureCount()).isEqualTo(1);
        assertThat(comparison.currentCases()).extracting(RagQualityGateService.CaseComparison::status)
                .containsExactly(RagEvalComparisonStatus.REGRESSED, RagEvalComparisonStatus.NEW);
    }

    @Test
    void missingBaselineIdentityIsNewInsteadOfThrowing() {
        assertThat(service.compare(null, null, "current", "fingerprint", true, false))
                .isEqualTo(RagEvalComparisonStatus.NEW);
        assertThat(service.compare("same", null, "same", null, true, true))
                .isEqualTo(RagEvalComparisonStatus.NEW);
    }

    @Test
    void warnsWhenBaselinedDecisionHasNoComparisonEvidence() {
        var policy = new RagQualityGateService.Policy(50, 20, 1, false);

        var status = service.decide(policy, new RagQualityGateService.RunSummary(1, 1),
                new RagQualityGateService.RunSummary(1, 1));

        assertThat(status).isEqualTo(RagQualityGateStatus.WARN);
    }

    @Test
    void detectsFractionalPassRateDropWithoutTruncation() {
        var policy = new RagQualityGateService.Policy(50, 6, 1, false);

        var status = service.decide(policy, new RagQualityGateService.RunSummary(3, 2),
                new RagQualityGateService.RunSummary(5, 3));

        assertThat(status).isEqualTo(RagQualityGateStatus.BLOCKED);
    }

    @Test
    void fingerprintsNormalizedCaseContentDeterministically() {
        var first = new RagQualityGateService.CaseDefinition(
                "  How   to install? ", 1, 5, " guide.md ", List.of("Windows", "Linux"));
        var equivalent = new RagQualityGateService.CaseDefinition(
                "How to install?", 1, 5, "guide.md", List.of("Linux", "Windows"));
        var changedAssertion = new RagQualityGateService.CaseDefinition(
                "How to install?", 2, 5, "guide.md", List.of("Linux", "Windows"));

        assertThat(service.fingerprint(first)).isEqualTo(service.fingerprint(equivalent));
        assertThat(service.fingerprint(first)).isNotEqualTo(service.fingerprint(changedAssertion));
    }

    @Test
    void relativeDropIgnoresNewOrChangedCases() {
        var comparison = service.compareCases(
                List.of(new RagQualityGateService.CaseEvidence("matched", "same", true),
                        new RagQualityGateService.CaseEvidence("changed", "old", true)),
                List.of(new RagQualityGateService.CaseEvidence("matched", "same", true),
                        new RagQualityGateService.CaseEvidence("changed", "new", false)));

        var status = service.decide(new RagQualityGateService.Policy(40, 10, 0, false),
                new RagQualityGateService.RunSummary(2, 2),
                new RagQualityGateService.RunSummary(2, 1), comparison);

        assertThat(status).isEqualTo(RagQualityGateStatus.WARN);
    }

    @Test
    void newPassingCasesCannotHideComparableRegression() {
        var baseline = List.of(new RagQualityGateService.CaseEvidence("matched", "same", true));
        var current = new java.util.ArrayList<RagQualityGateService.CaseEvidence>();
        current.add(new RagQualityGateService.CaseEvidence("matched", "same", false));
        for (int index = 0; index < 9; index++) {
            current.add(new RagQualityGateService.CaseEvidence("new-" + index, "v1", true));
        }
        var comparison = service.compareCases(baseline, current);

        var status = service.decide(new RagQualityGateService.Policy(80, 20, 1, false),
                new RagQualityGateService.RunSummary(1, 1),
                new RagQualityGateService.RunSummary(10, 9), comparison);

        assertThat(status).isEqualTo(RagQualityGateStatus.BLOCKED);
    }
}
