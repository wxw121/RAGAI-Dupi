package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.entity.RagEvalRunResult;
import com.dupi.rag.domain.entity.RagQualityPolicy;
import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.enums.RagQualityGateStatus;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.dto.RagEvalCaseRequest;
import com.dupi.rag.dto.RagQualityPolicyResponse;
import com.dupi.rag.dto.RetrievalHit;
import com.dupi.rag.dto.RetrieveResponse;
import com.dupi.rag.repository.RagEvalCaseRepository;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.RagEvalRunResultRepository;
import com.dupi.rag.repository.RagQualityPolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagEvalServiceTest {

    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock RetrievalService retrievalService;
    @Mock RagEvalCaseRepository caseRepository;
    @Mock RagEvalRunRepository runRepository;
    @Mock RagEvalRunResultRepository resultRepository;
    @Mock RagEvalCaseCoordinator caseCoordinator;
    @Mock RagQualityPolicyRepository policyRepository;
    @Mock AuditLogService auditLogService;
    @Mock RetrievalProfileService retrievalProfileService;

    @Test
    void runAgainstBaselineLoadsEvidenceAndPersistsComparisonStatus() {
        UUID kbId = UUID.randomUUID();
        UUID baselineId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase evalCase = caseEntity(kbId, UUID.randomUUID());
        String fingerprint = new RagQualityGateService().fingerprint(new RagQualityGateService.CaseDefinition(
                evalCase.getQuery(), 1, 3, evalCase.getExpectedFileName(), evalCase.getMustContainAny()));
        RagQualityPolicy policy = RagQualityPolicy.builder().kbId(kbId).baselineRunId(baselineId).build();
        RagEvalRun baseline = RagEvalRun.builder().id(baselineId).kbId(kbId).passedCount(1).totalCount(1).build();
        RagEvalRunResult baselineResult = RagEvalRunResult.builder().caseKey(evalCase.getCaseKey())
                .caseFingerprint(fingerprint).passed(true).failureReasons(List.of()).latencyMs(1L).build();
        when(caseCoordinator.loadOrSeed(kbId)).thenReturn(List.of(evalCase));
        when(policyRepository.findByKbId(kbId)).thenReturn(Optional.of(policy));
        when(runRepository.findById(baselineId)).thenReturn(Optional.of(baseline));
        when(resultRepository.findByRunIdOrderByCaseKeyAsc(baselineId)).thenReturn(List.of(baselineResult));
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(
                RetrieveResponse.builder().retrievalMode("vector").hits(List.of()).build());
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun value = invocation.getArgument(0); value.setId(runId); return value;
        });

        var response = service().run(kbId, false);

        assertThat(response.getBaselineRunId()).isEqualTo(baselineId);
        assertThat(response.getResults()).singleElement().satisfies(result ->
                assertThat(result.getComparisonStatus()).isEqualTo(com.dupi.rag.domain.enums.RagEvalComparisonStatus.REGRESSED));
        verify(resultRepository).save(argThat(result ->
                result.getComparisonStatus() == com.dupi.rag.domain.enums.RagEvalComparisonStatus.REGRESSED));
    }

    @Test
    void listRunsMapsPersistedResultsAndEmptyRunProducesZeroMetrics() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalRun historical = RagEvalRun.builder().id(runId).kbId(kbId).status(RagEvalRunStatus.COMPLETED)
                .passedCount(0).totalCount(0).metrics(Map.of()).profileSnapshot(Map.of()).policySnapshot(Map.of()).build();
        when(runRepository.findTop10ByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(historical));
        when(resultRepository.findByRunIdOrderByCaseKeyAsc(runId)).thenReturn(List.of());
        assertThat(service().listRuns(kbId)).singleElement().satisfies(item -> assertThat(item.getId()).isEqualTo(runId));

        when(caseCoordinator.loadOrSeed(kbId)).thenReturn(List.of());
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun value = invocation.getArgument(0); value.setId(runId); return value;
        });
        var empty = service().run(kbId, false);
        assertThat(empty.getMetrics()).containsEntry("passRate", 0.0)
                .containsEntry("averageHitCount", 0.0).containsEntry("latencyP95Ms", 0L);
    }

    @Test
    void listCasesSeedsBuiltInCasesWhenKnowledgeBaseHasNoPersistedCases() {
        UUID kbId = UUID.randomUUID();
        when(caseCoordinator.loadOrSeed(kbId)).thenReturn(List.of(
                builtInCase(kbId, "formats-supported"),
                builtInCase(kbId, "core-capabilities"),
                builtInCase(kbId, "chunk-strategies")
        ));

        var cases = service().listCases(kbId);

        assertThat(cases).extracting(response -> response.getCaseKey()).containsExactly(
                "formats-supported",
                "core-capabilities",
                "chunk-strategies"
        );
        verify(caseCoordinator).loadOrSeed(kbId);
    }

    @Test
    void createCaseAppliesOptionalDefaultsAndNormalizesBlankFile() {
        UUID kbId = UUID.randomUUID();
        when(caseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        RagEvalCaseRequest request = new RagEvalCaseRequest();
        request.setCaseKey(" key "); request.setQuery(" query "); request.setExpectedFileName("  ");

        var response = service().createCase(kbId, request);

        assertThat(response.getCaseKey()).isEqualTo("key");
        assertThat(response.getQuery()).isEqualTo("query");
        assertThat(response.getMinHits()).isEqualTo(1);
        assertThat(response.getTopK()).isEqualTo(5);
        assertThat(response.getExpectedFileName()).isNull();
        assertThat(response.getMustContainAny()).isEmpty();
    }

    @Test
    void createCaseRejectsKnowledgeBasesAtTheCaseLimit() {
        UUID kbId = UUID.randomUUID();
        doThrow(new IllegalArgumentException("at most 100 RAG eval cases"))
                .when(caseCoordinator).assertCanCreate(kbId);
        RagEvalCaseRequest request = new RagEvalCaseRequest();
        request.setCaseKey("too-many");
        request.setQuery("question");

        assertThatThrownBy(() -> service().createCase(kbId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void runRejectsKnowledgeBasesAboveTheCaseLimit() {
        UUID kbId = UUID.randomUUID();
        when(caseCoordinator.loadOrSeed(kbId))
                .thenThrow(new IllegalArgumentException("at most 100 RAG eval cases"));

        assertThatThrownBy(() -> service().run(kbId, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void createUpdateDeleteAndListCasesAreScopedToKnowledgeBase() {
        UUID kbId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        RagEvalCase existing = caseEntity(kbId, caseId);
        when(caseRepository.save(any(RagEvalCase.class))).thenAnswer(inv -> {
            RagEvalCase saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(caseId);
            }
            return saved;
        });
        when(caseRepository.findByIdAndKbId(caseId, kbId)).thenReturn(Optional.of(existing));
        when(caseCoordinator.loadOrSeed(kbId)).thenReturn(List.of(existing));

        RagEvalCaseRequest request = new RagEvalCaseRequest();
        request.setCaseKey("install");
        request.setQuery("How to install?");
        request.setMinHits(1);
        request.setTopK(3);
        request.setExpectedFileName("guide.md");
        request.setMustContainAny(List.of("install", "setup"));

        var created = service().createCase(kbId, request);
        request.setQuery("How to setup?");
        var updated = service().updateCase(kbId, caseId, request);
        var listed = service().listCases(kbId);
        service().deleteCase(kbId, caseId);

        assertThat(created.getId()).isEqualTo(caseId);
        assertThat(updated.getQuery()).isEqualTo("How to setup?");
        assertThat(listed).hasSize(1);
        verify(knowledgeBaseService, times(2)).findOrThrow(kbId);
        verify(caseRepository).delete(existing);
    }

    @Test
    void runPersistsCaseResultsAndHistorySummary() {
        UUID kbId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        RagEvalCase evalCase = caseEntity(kbId, caseId);
        when(caseCoordinator.loadOrSeed(kbId)).thenReturn(List.of(evalCase));
        when(retrievalService.retrieve(argThat(id -> id.equals(kbId)), any())).thenReturn(RetrieveResponse.builder()
                .query("How to install?")
                .retrievalMode("hybrid_rerank")
                .diagnostics(Map.of(
                        "retrievalMode", "hybrid_rerank",
                        "embeddingModel", "embed",
                        "embeddingDimension", 1024
                ))
                .hits(List.of(RetrievalHit.builder()
                        .chunkId(chunkId)
                        .docId(docId)
                        .fileName("guide.md")
                        .content("Use install command")
                        .score(0.8)
                        .metadata(Map.of("heading", "Install", "retrievalStages", Map.of(
                                "vectorRank", 2, "sparseRank", 1, "fusionRank", 1, "rerankRank", 1)))
                        .build()))
                .build());
        List<RagEvalRunStatus> savedStatuses = new ArrayList<>();
        when(runRepository.save(any(RagEvalRun.class))).thenAnswer(inv -> {
            RagEvalRun run = inv.getArgument(0);
            savedStatuses.add(run.getStatus());
            run.setId(runId);
            return run;
        });

        var response = service().run(kbId, true);

        assertThat(response.getId()).isEqualTo(runId);
        assertThat(response.getPassedCount()).isEqualTo(1);
        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(RagEvalRunStatus.COMPLETED);
        assertThat(response.getFailureMessage()).isNull();
        assertThat(response.getGateStatus()).isEqualTo(RagQualityGateStatus.UNBASELINED);
        assertThat(response.getMetrics()).containsEntry("passRate", 100.0);
        assertThat(response.getPolicySnapshot()).containsEntry("minimumPassRate", 80);
        assertThat(response.getProfileSnapshot()).containsEntry("retrievalMode", "hybrid_rerank");
        assertThat(savedStatuses).containsExactly(RagEvalRunStatus.RUNNING, RagEvalRunStatus.COMPLETED);
        assertThat(response.getResults()).singleElement().satisfies(result -> {
            assertThat(result.isPassed()).isTrue();
            assertThat(result.getMatchedFileName()).isEqualTo("guide.md");
            assertThat(result.getMatchedToken()).isEqualTo("install");
            assertThat(result.getRetrievalMode()).isEqualTo("hybrid_rerank");
            assertThat(result.getEmbeddingDimension()).isEqualTo(1024);
            assertThat(result.getMatchedRank()).isEqualTo(1);
            assertThat(result.getVectorRank()).isEqualTo(2);
            assertThat(result.getSparseRank()).isEqualTo(1);
            assertThat(result.getFusionRank()).isEqualTo(1);
            assertThat(result.getRerankRank()).isEqualTo(1);
            assertThat(result.getCaseFingerprint()).hasSize(64);
        });
        ArgumentCaptor<RagEvalRun> runCaptor = ArgumentCaptor.forClass(RagEvalRun.class);
        verify(runRepository, times(2)).save(runCaptor.capture());
        assertThat(runCaptor.getAllValues().get(1).getPassedCount()).isEqualTo(1);
        verify(resultRepository).save(argThat(result ->
                result.isPassed()
                        && "guide.md".equals(result.getMatchedFileName())
                        && "install".equals(result.getMatchedToken())
        ));
    }

    @Test
    void noAnswerCaseFailsWhenRetrievalReturnsEvidence() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase evalCase = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("no-answer").query("unknown").minHits(0).topK(5)
                .expectedFileName(null).mustContainAny(List.of()).build();
        when(caseCoordinator.loadOrSeed(kbId)).thenReturn(List.of(evalCase));
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder()
                .hits(List.of(RetrievalHit.builder().chunkId(UUID.randomUUID()).docId(UUID.randomUUID())
                        .fileName("unrelated.md").content("unrelated evidence").score(0.1).build()))
                .build());
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0); run.setId(runId); return run;
        });

        var response = service().run(kbId, false);

        assertThat(response.getResults()).singleElement().satisfies(result -> {
            assertThat(result.isPassed()).isFalse();
            assertThat(result.getFailureReasons()).contains("expected no hits, got 1");
        });
    }

    @Test
    void runCanExplicitlyEvaluateVectorModeAndRejectsVectorProfileCombination() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(caseCoordinator.loadOrSeed(kbId)).thenReturn(List.of(caseEntity(kbId, UUID.randomUUID())));
        when(retrievalService.retrieveForEvaluation(eq(kbId), any(), eq(com.dupi.rag.domain.enums.RetrievalMode.VECTOR)))
                .thenReturn(RetrieveResponse.builder().retrievalMode("vector").hits(List.of()).build());
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun saved = invocation.getArgument(0); saved.setId(runId); return saved;
        });

        var response = service().run(kbId, false, null, com.dupi.rag.domain.enums.RetrievalMode.VECTOR);

        assertThat(response.getProfileSnapshot()).containsEntry("retrievalMode", "vector");
        verify(retrievalService).retrieveForEvaluation(eq(kbId), any(),
                eq(com.dupi.rag.domain.enums.RetrievalMode.VECTOR));
        assertThatThrownBy(() -> service().run(kbId, false, UUID.randomUUID(),
                com.dupi.rag.domain.enums.RetrievalMode.VECTOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot use");
    }

    @Test
    void getAndUpdatePolicyCreateDefaultsAndPersistThresholds() {
        UUID kbId = UUID.randomUUID();
        RagQualityPolicy policy = RagQualityPolicy.builder().id(UUID.randomUUID()).kbId(kbId).build();
        when(policyRepository.findByKbIdForUpdate(kbId)).thenReturn(Optional.empty(), Optional.of(policy));
        when(policyRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var defaults = service().getPolicy(kbId);
        com.dupi.rag.dto.RagQualityPolicyRequest request = new com.dupi.rag.dto.RagQualityPolicyRequest();
        request.setMinimumPassRate(90); request.setMaximumPassRateDrop(2);
        request.setMaximumNewFailures(1); request.setBlockWhenUnbaselined(true);
        var updated = service().updatePolicy(kbId, request);

        assertThat(defaults.getMinimumPassRate()).isEqualTo(80);
        assertThat(updated.getMinimumPassRate()).isEqualTo(90);
        assertThat(updated.getMaximumPassRateDrop()).isEqualTo(2);
        assertThat(updated.getMaximumNewFailures()).isEqualTo(1);
        assertThat(updated.getBlockWhenUnbaselined()).isTrue();
        verify(auditLogService).recordSuccess("RAG_QUALITY_POLICY_UPDATE", "KNOWLEDGE_BASE", kbId,
                "Updated RAG quality policy");
    }

    @Test
    void comparisonLoadsBaselineEvidenceAndReportsRemovedCases() {
        UUID kbId = UUID.randomUUID();
        UUID baselineId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalRun run = RagEvalRun.builder().id(runId).kbId(kbId).baselineRunId(baselineId)
                .status(RagEvalRunStatus.COMPLETED).passedCount(1).totalCount(1).build();
        RagEvalRunResult baselineOnly = RagEvalRunResult.builder().caseKey("removed").caseFingerprint("a")
                .passed(true).failureReasons(List.of()).latencyMs(1L).build();
        RagEvalRunResult current = RagEvalRunResult.builder().caseKey("current").caseFingerprint("b")
                .passed(true).failureReasons(List.of()).latencyMs(1L).build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(resultRepository.findByRunIdOrderByCaseKeyAsc(runId)).thenReturn(List.of(current));
        when(resultRepository.findByRunIdOrderByCaseKeyAsc(baselineId)).thenReturn(List.of(baselineOnly));

        var response = service().getRunComparison(kbId, runId);

        assertThat(response.getRemovedBaselineCaseKeys()).containsExactly("removed");
        assertThat(response.getResults()).singleElement();
        verify(knowledgeBaseService).findOrThrow(kbId);
    }

    @Test
    void promoteBaselineRejectsBlockedRuns() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.of(RagEvalRun.builder()
                .id(runId)
                .kbId(kbId)
                .status(RagEvalRunStatus.COMPLETED)
                .gateStatus(RagQualityGateStatus.BLOCKED)
                .build()));

        assertThatThrownBy(() -> service().promoteBaseline(kbId, runId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passing quality gate");
    }

    @Test
    void promoteBaselineAcceptsFirstThresholdPassingUnbaselinedRun() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagQualityPolicy policy = RagQualityPolicy.builder().id(UUID.randomUUID()).kbId(kbId).build();
        when(policyRepository.findByKbIdForUpdate(kbId)).thenReturn(Optional.of(policy));
        when(runRepository.findById(runId)).thenReturn(Optional.of(RagEvalRun.builder()
                .id(runId)
                .kbId(kbId)
                .status(RagEvalRunStatus.COMPLETED)
                .gateStatus(RagQualityGateStatus.UNBASELINED)
                .passedCount(1)
                .totalCount(1)
                .build()));

        TransactionSynchronizationManager.initSynchronization();
        RagQualityPolicyResponse response;
        try {
            response = service().promoteBaseline(kbId, runId);
            verify(auditLogService, never()).recordSuccess(any(), any(), any(), any());
            TransactionSynchronizationUtils.triggerAfterCommit();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(response.getBaselineRunId()).isEqualTo(runId);
        verify(policyRepository).saveAndFlush(argThat(saved -> runId.equals(saved.getBaselineRunId())));
        verify(auditLogService).recordSuccess("RAG_BASELINE_PROMOTE", "KNOWLEDGE_BASE", kbId,
                "Promoted RAG evaluation baseline " + runId);
    }

    @Test
    void promoteBaselineRechecksRunAgainstCurrentPolicy() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagQualityPolicy policy = RagQualityPolicy.builder()
                .id(UUID.randomUUID())
                .kbId(kbId)
                .minimumPassRate(80)
                .build();
        when(policyRepository.findByKbIdForUpdate(kbId)).thenReturn(Optional.of(policy));
        when(runRepository.findById(runId)).thenReturn(Optional.of(RagEvalRun.builder()
                .id(runId)
                .kbId(kbId)
                .status(RagEvalRunStatus.COMPLETED)
                .gateStatus(RagQualityGateStatus.UNBASELINED)
                .passedCount(1)
                .totalCount(2)
                .build()));
        when(resultRepository.findByRunIdOrderByCaseKeyAsc(runId)).thenReturn(List.of());

        assertThatThrownBy(() -> service().promoteBaseline(kbId, runId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passing quality gate");
    }

    @Test
    void runMarksHistoryFailedWhenResultPersistenceBreaks() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(caseCoordinator.loadOrSeed(kbId)).thenReturn(List.of(caseEntity(kbId, UUID.randomUUID())));
        List<RagEvalRunStatus> savedStatuses = new ArrayList<>();
        List<String> savedFailures = new ArrayList<>();
        when(runRepository.save(any(RagEvalRun.class))).thenAnswer(inv -> {
            RagEvalRun run = inv.getArgument(0);
            savedStatuses.add(run.getStatus());
            savedFailures.add(run.getFailureMessage());
            run.setId(runId);
            return run;
        });
        when(resultRepository.save(any())).thenThrow(new IllegalStateException("database write failed"));

        assertThatThrownBy(() -> service().run(kbId, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAG evaluation failed")
                .hasRootCauseMessage("database write failed");

        assertThat(savedStatuses).containsExactly(RagEvalRunStatus.RUNNING, RagEvalRunStatus.FAILED);
        assertThat(savedFailures).containsExactly(null, "database write failed");
    }

    @Test
    void runWithCandidateProfilePersistsExactProfileSnapshot() {
        UUID kbId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase evalCase = caseEntity(kbId, UUID.randomUUID());
        RetrievalProfile profile = RetrievalProfile.builder()
                .id(profileId).kbId(kbId).name("candidate").version(3)
                .vectorCandidateCount(40).sparseCandidateCount(25).rrfConstant(50)
                .rerankEnabled(false).rerankCandidateLimit(15).finalTopK(4).build();
        when(caseCoordinator.loadOrSeed(kbId)).thenReturn(List.of(evalCase));
        when(retrievalProfileService.find(kbId, profileId)).thenReturn(profile);
        when(retrievalService.retrieveForProfile(eq(kbId), any(), eq(profileId)))
                .thenReturn(RetrieveResponse.builder().retrievalMode("hybrid").hits(List.of()).build());
        when(runRepository.save(any(RagEvalRun.class))).thenAnswer(invocation -> {
            RagEvalRun saved = invocation.getArgument(0);
            saved.setId(runId);
            return saved;
        });

        var response = service().run(kbId, true, profileId);

        assertThat(response.getProfileSnapshot()).isEqualTo(profile.snapshot());
        assertThat(response.isUseRerank()).isFalse();
        verify(retrievalService).retrieveForProfile(eq(kbId), any(), eq(profileId));
    }

    private RagEvalService service() {
        return new RagEvalService(
                knowledgeBaseService,
                retrievalService,
                caseRepository,
                runRepository,
                resultRepository,
                caseCoordinator,
                policyRepository,
                new RagQualityGateService(),
                auditLogService,
                retrievalProfileService
        );
    }

    private static RagEvalCase builtInCase(UUID kbId, String caseKey) {
        return RagEvalCase.builder()
                .id(UUID.randomUUID())
                .kbId(kbId)
                .caseKey(caseKey)
                .query("query")
                .minHits(1)
                .topK(5)
                .mustContainAny(List.of())
                .build();
    }

    private static RagEvalCase caseEntity(UUID kbId, UUID caseId) {
        RagEvalCase evalCase = RagEvalCase.builder()
                .id(caseId)
                .kbId(kbId)
                .caseKey("install")
                .query("How to install?")
                .minHits(1)
                .topK(3)
                .expectedFileName("guide.md")
                .mustContainAny(List.of("install", "setup"))
                .createdAt(Instant.parse("2026-07-12T00:00:00Z"))
                .updatedAt(Instant.parse("2026-07-12T00:00:00Z"))
                .build();
        return evalCase;
    }
}
