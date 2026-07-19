package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.entity.RagEvalRunResult;
import com.dupi.rag.domain.enums.RagEvalGateStatus;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.RetrievalProfile;
import com.dupi.rag.dto.RagEvalGateDecisionResponse;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.RagEvalRunResultRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalProfileGateServiceTest {

    @Mock RagEvalRunRepository runRepository;
    @Mock RagEvalRunResultRepository resultRepository;
    @Mock ProfileIndexStateService profileIndexStateService;

    @Test
    void calculateBuildsMetricsDeltasAndPassedDecision() {
        var decisions = service().calculate(List.of(
                result(RetrievalProfile.CLASSIC, "c1", true, true, true, true),
                result(RetrievalProfile.CLASSIC, "c2", true, true, true, true),
                result(RetrievalProfile.CLASSIC, "c3", true, false, false, true),
                result(RetrievalProfile.PARENT_CHILD, "c1", true, true, true, true),
                result(RetrievalProfile.PARENT_CHILD, "c2", true, true, true, true),
                result(RetrievalProfile.PARENT_CHILD, "c3", true, false, false, true)
        ), 7L, 7L, true);

        RagEvalGateDecisionResponse decision = decisions.get(RetrievalProfile.PARENT_CHILD);

        assertThat(decision.getStatus()).isEqualTo(RagEvalGateStatus.PASSED);
        assertThat(decision.getReason()).isEqualTo("passed");
        assertThat(decision.getMetrics().getTotalCases()).isEqualTo(3);
        assertThat(decision.getMetrics().getHitRate()).isEqualTo(1.0);
        assertThat(decision.getMetrics().getCitationPassRate()).isEqualTo(1.0);
        assertThat(decision.getClassicMetrics().getHitRate()).isEqualTo(1.0);
        assertThat(decision.getHitRateDelta()).isEqualTo(0.0);
        assertThat(decision.getCitationPassRateDelta()).isEqualTo(0.0);
    }

    @Test
    void calculateBlocksHitAndCitationRegressions() {
        assertStatus(service().calculate(List.of(
                result(RetrievalProfile.CLASSIC, "c1", true, true, true, true),
                result(RetrievalProfile.CLASSIC, "c2", true, true, true, true),
                result(RetrievalProfile.CLASSIC, "c3", true, true, true, true),
                result(RetrievalProfile.QA_ASSISTED, "c1", true, true, true, true),
                result(RetrievalProfile.QA_ASSISTED, "c2", true, true, true, true),
                result(RetrievalProfile.QA_ASSISTED, "c3", false, true, true, false)
        ), 1L, 1L, true).get(RetrievalProfile.QA_ASSISTED), RagEvalGateStatus.BLOCKED, "hit_rate_regressed");

        assertStatus(service().calculate(List.of(
                result(RetrievalProfile.CLASSIC, "c1", true, true, true, true),
                result(RetrievalProfile.CLASSIC, "c2", true, true, true, true),
                result(RetrievalProfile.CLASSIC, "c3", true, true, true, true),
                result(RetrievalProfile.COMBINED, "c1", true, true, true, true),
                result(RetrievalProfile.COMBINED, "c2", true, true, false, false),
                result(RetrievalProfile.COMBINED, "c3", true, true, true, true)
        ), 1L, 1L, true).get(RetrievalProfile.COMBINED), RagEvalGateStatus.BLOCKED, "citation_quality_regressed");
    }

    @Test
    void calculateMarksInsufficientStaleAndIndexNotReady() {
        assertStatus(service().calculate(List.of(
                result(RetrievalProfile.CLASSIC, "c1", true, true, true, true),
                result(RetrievalProfile.CLASSIC, "c2", true, true, true, true),
                result(RetrievalProfile.PARENT_CHILD, "c1", true, true, true, true),
                result(RetrievalProfile.PARENT_CHILD, "c2", true, true, true, true)
        ), 1L, 1L, true).get(RetrievalProfile.PARENT_CHILD), RagEvalGateStatus.INSUFFICIENT_DATA, "not_enough_cases");

        assertStatus(service().calculate(List.of(
                result(RetrievalProfile.CLASSIC, "c1", true, false, false, true),
                result(RetrievalProfile.CLASSIC, "c2", true, false, false, true),
                result(RetrievalProfile.CLASSIC, "c3", true, false, false, true),
                result(RetrievalProfile.PARENT_CHILD, "c1", true, false, false, true),
                result(RetrievalProfile.PARENT_CHILD, "c2", true, false, false, true),
                result(RetrievalProfile.PARENT_CHILD, "c3", true, false, false, true)
        ), 1L, 1L, true).get(RetrievalProfile.PARENT_CHILD), RagEvalGateStatus.INSUFFICIENT_DATA, "no_citation_cases");

        assertStatus(service().calculate(passingResults(RetrievalProfile.PARENT_CHILD), 1L, 2L, true)
                .get(RetrievalProfile.PARENT_CHILD), RagEvalGateStatus.STALE, "index_revision_changed");
        assertStatus(service().calculate(passingResults(RetrievalProfile.PARENT_CHILD), 1L, 1L, false)
                .get(RetrievalProfile.PARENT_CHILD), RagEvalGateStatus.INDEX_NOT_READY, "profile_index_not_ready");
    }

    @Test
    void latestDecisionUsesLatestCompletedRunContainingClassicAndCandidate() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(runRepository.findLatestCompletedContainingClassicAndProfile(kbId, RetrievalProfile.COMBINED.name()))
                .thenReturn(Optional.of(RagEvalRun.builder()
                        .id(runId)
                        .kbId(kbId)
                        .profileSet(List.of(RetrievalProfile.CLASSIC, RetrievalProfile.COMBINED))
                        .indexRevision(3L)
                        .status(RagEvalRunStatus.COMPLETED)
                        .build()));
        when(resultRepository.findByRunIdOrderByCaseKeyAsc(runId))
                .thenReturn(passingResults(RetrievalProfile.COMBINED));
        when(profileIndexStateService.currentRevision(kbId)).thenReturn(3L);
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);

        RagEvalGateDecisionResponse decision = service().latestDecision(kbId, RetrievalProfile.COMBINED);

        assertThat(decision.getStatus()).isEqualTo(RagEvalGateStatus.PASSED);
        assertThat(decision.getCandidate()).isEqualTo(RetrievalProfile.COMBINED);
    }

    @Test
    void assertCanActivateAllowsClassicAndPassingCandidateOnly() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        service().assertCanActivate(kbId, RetrievalProfile.CLASSIC);

        when(runRepository.findLatestCompletedContainingClassicAndProfile(kbId, RetrievalProfile.PARENT_CHILD.name()))
                .thenReturn(Optional.of(RagEvalRun.builder()
                        .id(runId)
                        .kbId(kbId)
                        .profileSet(List.of(RetrievalProfile.CLASSIC, RetrievalProfile.PARENT_CHILD))
                        .indexRevision(3L)
                        .build()));
        when(resultRepository.findByRunIdOrderByCaseKeyAsc(runId))
                .thenReturn(passingResults(RetrievalProfile.PARENT_CHILD));
        when(profileIndexStateService.currentRevision(kbId)).thenReturn(3L);
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);

        service().assertCanActivate(kbId, RetrievalProfile.PARENT_CHILD);

        assertThatThrownBy(() -> service().assertCanActivate(kbId, RetrievalProfile.QA_ASSISTED))
                .isInstanceOf(com.dupi.rag.exception.RetrievalProfileConflictException.class)
                .hasMessageContaining("not_evaluated");
    }

    private RetrievalProfileGateService service() {
        return new RetrievalProfileGateService(runRepository, resultRepository, profileIndexStateService);
    }

    private static List<RagEvalRunResult> passingResults(RetrievalProfile candidate) {
        return List.of(
                result(RetrievalProfile.CLASSIC, "c1", true, true, true, true),
                result(RetrievalProfile.CLASSIC, "c2", true, true, true, true),
                result(RetrievalProfile.CLASSIC, "c3", true, true, true, true),
                result(candidate, "c1", true, true, true, true),
                result(candidate, "c2", true, true, true, true),
                result(candidate, "c3", true, true, true, true)
        );
    }

    private static RagEvalRunResult result(
            RetrievalProfile profile,
            String caseKey,
            boolean hitPassed,
            boolean citationEligible,
            boolean citationPassed,
            boolean passed
    ) {
        return RagEvalRunResult.builder()
                .runId(UUID.randomUUID())
                .caseKey(caseKey)
                .query("query")
                .retrievalProfile(profile)
                .hitPassed(hitPassed)
                .citationEligible(citationEligible)
                .citationPassed(citationPassed)
                .passed(passed)
                .build();
    }

    private static void assertStatus(
            RagEvalGateDecisionResponse decision,
            RagEvalGateStatus status,
            String reason
    ) {
        assertThat(decision.getStatus()).isEqualTo(status);
        assertThat(decision.getReason()).isEqualTo(reason);
    }
}
