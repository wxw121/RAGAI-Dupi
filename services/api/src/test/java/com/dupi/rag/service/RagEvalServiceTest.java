package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.entity.RagEvalRunResult;
import com.dupi.rag.domain.enums.RagEvalGateStatus;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.RetrievalProfile;
import com.dupi.rag.dto.RagEvalGateDecisionResponse;
import com.dupi.rag.dto.RagEvalProfileMetricsResponse;
import com.dupi.rag.dto.RagEvalCaseRequest;
import com.dupi.rag.dto.RetrievalHit;
import com.dupi.rag.dto.RetrieveRequest;
import com.dupi.rag.dto.RetrieveResponse;
import com.dupi.rag.repository.RagEvalCaseRepository;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.RagEvalRunResultRepository;
import com.dupi.rag.repository.RagQualityPolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.verify;
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
    @Mock ProfileIndexStateService profileIndexStateService;
    @Mock RetrievalProfileGateService retrievalProfileGateService;
    @Mock RagQualityPolicyRepository policyRepository;
    @Mock RagQualityGateService qualityGateService;
    @Mock AuditLogService auditLogService;
    @Mock RetrievalProfileService retrievalProfileService;
    @Mock KnowledgeBaseMaintenanceService maintenanceService;

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
    void runPrependsClassicWhenEvaluatingCandidateProfile() {
        UUID kbId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        RagEvalCase evalCase = caseEntity(kbId, caseId);
        when(caseCoordinator.loadOrSeed(kbId)).thenReturn(List.of(evalCase));
        when(retrievalService.retrieve(argThat(id -> id.equals(kbId)), any())).thenAnswer(inv -> {
            RetrieveRequest request = inv.getArgument(1);
            return RetrieveResponse.builder()
                    .query(request.getQuery())
                    .retrievalMode("vector")
                    .diagnostics(Map.of(
                            "retrievalMode", "vector",
                            "retrievalProfile", request.getRetrievalProfile().wireValue(),
                            "embeddingModel", "embed",
                            "embeddingDimension", 1024
                    ))
                    .hits(List.of(RetrievalHit.builder()
                            .chunkId(chunkId)
                            .docId(docId)
                            .fileName("guide.md")
                            .content("Use install command")
                            .score(0.8)
                            .metadata(Map.of())
                            .build()))
                    .build();
        });
        when(runRepository.save(any(RagEvalRun.class))).thenAnswer(inv -> {
            RagEvalRun run = inv.getArgument(0);
            run.setId(runId);
            return run;
        });

        when(profileIndexStateService.currentRevision(kbId)).thenReturn(11L);

        var response = service().run(kbId, false, List.of(RetrievalProfile.PARENT_CHILD));

        assertThat(response.getTotalCount()).isEqualTo(2);
        assertThat(response.getProfileSet()).containsExactly(RetrievalProfile.CLASSIC, RetrievalProfile.PARENT_CHILD);
        assertThat(response.getIndexRevision()).isEqualTo(11L);
        assertThat(response.getResults()).extracting(result -> result.getRetrievalProfile())
                .containsExactly(RetrievalProfile.CLASSIC, RetrievalProfile.PARENT_CHILD);
        ArgumentCaptor<RetrieveRequest> requestCaptor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrievalService, times(2)).retrieve(argThat(id -> id.equals(kbId)), requestCaptor.capture());
        assertThat(requestCaptor.getAllValues()).extracting(RetrieveRequest::getRetrievalProfile)
                .containsExactly(RetrievalProfile.CLASSIC, RetrievalProfile.PARENT_CHILD);
        verify(resultRepository, times(2)).save(ArgumentMatchers.argThat(result ->
                result.getRetrievalProfile() == RetrievalProfile.CLASSIC
                        || result.getRetrievalProfile() == RetrievalProfile.PARENT_CHILD
        ));
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
                        .metadata(Map.of("heading", "Install"))
                        .build()))
                .build());
        List<RagEvalRunStatus> savedStatuses = new ArrayList<>();
        when(runRepository.save(any(RagEvalRun.class))).thenAnswer(inv -> {
            RagEvalRun run = inv.getArgument(0);
            savedStatuses.add(run.getStatus());
            run.setId(runId);
            return run;
        });
        when(profileIndexStateService.currentRevision(kbId)).thenReturn(5L);
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);
        when(retrievalProfileGateService.calculate(any(), ArgumentMatchers.eq(5L), ArgumentMatchers.eq(5L), ArgumentMatchers.eq(true)))
                .thenReturn(Map.of(RetrievalProfile.PARENT_CHILD, gate(RetrievalProfile.PARENT_CHILD)));

        var response = service().run(kbId, true, List.of(RetrievalProfile.PARENT_CHILD));

        assertThat(response.getId()).isEqualTo(runId);
        assertThat(response.getPassedCount()).isEqualTo(2);
        assertThat(response.getTotalCount()).isEqualTo(2);
        assertThat(response.getIndexRevision()).isEqualTo(5L);
        assertThat(response.getGateSummary().get(RetrievalProfile.PARENT_CHILD).getStatus())
                .isEqualTo(RagEvalGateStatus.PASSED);
        assertThat(response.getStatus()).isEqualTo(RagEvalRunStatus.COMPLETED);
        assertThat(response.getFailureMessage()).isNull();
        assertThat(savedStatuses).containsExactly(RagEvalRunStatus.RUNNING, RagEvalRunStatus.COMPLETED);
        assertThat(response.getResults()).allSatisfy(result -> {
            assertThat(result.isPassed()).isTrue();
            assertThat(result.isHitPassed()).isTrue();
            assertThat(result.isCitationEligible()).isTrue();
            assertThat(result.isCitationPassed()).isTrue();
            assertThat(result.getMatchedFileName()).isEqualTo("guide.md");
            assertThat(result.getMatchedToken()).isEqualTo("install");
            assertThat(result.getRetrievalMode()).isEqualTo("hybrid_rerank");
            assertThat(result.getEmbeddingDimension()).isEqualTo(1024);
        });
        ArgumentCaptor<RagEvalRun> runCaptor = ArgumentCaptor.forClass(RagEvalRun.class);
        verify(runRepository, times(2)).save(runCaptor.capture());
        assertThat(runCaptor.getAllValues().get(0).getIndexRevision()).isEqualTo(5L);
        assertThat(runCaptor.getAllValues().get(1).getPassedCount()).isEqualTo(2);
        assertThat(runCaptor.getAllValues().get(1).getGateSummary()).containsKey("parent-child");
        verify(resultRepository, times(2)).save(argThat(result ->
                result.isPassed()
                        && result.isHitPassed()
                        && result.isCitationEligible()
                        && result.isCitationPassed()
                        && "guide.md".equals(result.getMatchedFileName())
                        && "install".equals(result.getMatchedToken())
        ));
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

    private RagEvalService service() {
        return new RagEvalService(
                knowledgeBaseService,
                retrievalService,
                caseRepository,
                runRepository,
                resultRepository,
                caseCoordinator,
                policyRepository,
                qualityGateService,
                auditLogService,
                retrievalProfileService,
                maintenanceService,
                profileIndexStateService,
                retrievalProfileGateService
        );
    }

    private static RagEvalGateDecisionResponse gate(RetrievalProfile candidate) {
        RagEvalProfileMetricsResponse metrics = RagEvalProfileMetricsResponse.builder()
                .profile(candidate)
                .totalCases(3)
                .passedCount(3)
                .hitPassedCount(3)
                .citationEligibleCount(3)
                .citationPassedCount(3)
                .passRate(1.0)
                .hitRate(1.0)
                .citationPassRate(1.0)
                .build();
        return RagEvalGateDecisionResponse.builder()
                .candidate(candidate)
                .baseline(RetrievalProfile.CLASSIC)
                .status(RagEvalGateStatus.PASSED)
                .reason("passed")
                .metrics(metrics)
                .classicMetrics(metrics.toBuilder().profile(RetrievalProfile.CLASSIC).build())
                .hitRateDelta(0.0)
                .citationPassRateDelta(0.0)
                .build();
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
