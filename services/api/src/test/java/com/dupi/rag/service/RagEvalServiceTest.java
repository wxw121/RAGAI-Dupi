package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.entity.RagEvalRunResult;
import com.dupi.rag.domain.entity.RagQualityPolicy;
import com.dupi.rag.domain.enums.RagEvalCaseCategory;
import com.dupi.rag.domain.enums.RagEvalGateStatus;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.RagQualityGateStatus;
import com.dupi.rag.domain.enums.RetrievalProfile;
import com.dupi.rag.dto.RagEvalGateDecisionResponse;
import com.dupi.rag.dto.RagEvalProfileMetricsResponse;
import com.dupi.rag.dto.RagEvalCaseRequest;
import com.dupi.rag.dto.RagEvalRunRequest;
import com.dupi.rag.dto.RagQualityPolicyResponse;
import com.dupi.rag.dto.RetrievalHit;
import com.dupi.rag.dto.RetrieveRequest;
import com.dupi.rag.dto.RetrieveResponse;
import com.dupi.rag.repository.RagEvalCaseRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.RagEvalRunResultRepository;
import com.dupi.rag.repository.RagQualityPolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
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
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    @Mock RagEvalCaseValidationService caseValidationService;
    @Mock DocumentRepository documentRepository;

    @BeforeEach
    void defaultCasesAreSourceValid() {
        org.mockito.Mockito.lenient().when(caseValidationService.validate(any(), any())).thenAnswer(invocation -> {
            List<RagEvalCase> cases = invocation.getArgument(1);
            Map<UUID, RagEvalCaseValidationService.CaseValidity> validity = cases.stream().collect(
                    java.util.stream.Collectors.toMap(
                            RagEvalCase::getId,
                            ignored -> new RagEvalCaseValidationService.CaseValidity(true, List.of())));
            return new RagEvalCaseValidationService.ValidationReport(validity, List.of(), "valid");
        });
    }

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
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(evalCase));
        when(policyRepository.findByKbId(kbId)).thenReturn(Optional.of(policy));
        when(runRepository.findById(baselineId)).thenReturn(Optional.of(baseline));
        when(resultRepository.findByRunIdOrderByCaseKeyAsc(baselineId)).thenReturn(List.of(baselineResult));
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(
                RetrieveResponse.builder().retrievalMode("vector").hits(List.of()).build());
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun value = invocation.getArgument(0);
            value.setId(runId);
            return value;
        });

        var response = service().run(kbId, false, null, null);

        assertThat(response.getBaselineRunId()).isEqualTo(baselineId);
        assertThat(response.getResults()).singleElement().satisfies(result ->
                assertThat(result.getComparisonStatus())
                        .isEqualTo(com.dupi.rag.domain.enums.RagEvalComparisonStatus.REGRESSED));
        verify(resultRepository).save(argThat(result ->
                result.getComparisonStatus() == com.dupi.rag.domain.enums.RagEvalComparisonStatus.REGRESSED));
    }

    @Test
    void listRunsMapsPersistedResultsAndEmptyLegacyRunProducesZeroMetrics() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalRun historical = RagEvalRun.builder().id(runId).kbId(kbId).status(RagEvalRunStatus.COMPLETED)
                .passedCount(0).totalCount(0).metrics(Map.of()).profileSnapshot(Map.of()).policySnapshot(Map.of()).build();
        when(runRepository.findTop10ByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(historical));
        when(resultRepository.findByRunIdOrderByCaseKeyAsc(runId)).thenReturn(List.of());
        assertThat(service().listRuns(kbId)).singleElement().satisfies(item -> assertThat(item.getId()).isEqualTo(runId));

        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of());
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun value = invocation.getArgument(0);
            value.setId(runId);
            return value;
        });
        var empty = service().run(kbId, false, null, null);
        assertThat(empty.getTotalCount()).isZero();
        assertThat(empty.getResults()).isEmpty();
        assertThat(empty.getMetrics()).containsEntry("passRate", 0.0)
                .containsEntry("averageHitCount", 0.0).containsEntry("latencyP95Ms", 0L);
        assertThat((Map<String, Object>) empty.getMetrics().get("releaseGate"))
                .containsEntry("status", "NO_CASES")
                .containsEntry("total", 0)
                .containsEntry("passed", 0);
    }

    @Test
    void listRunsHydratesSerializedProfileGateSummaries() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Map<String, Object> metrics = Map.ofEntries(
                Map.entry("profile", "parent-child"),
                Map.entry("totalCases", 4),
                Map.entry("passedCount", 3),
                Map.entry("hitPassedCount", 4),
                Map.entry("citationEligibleCount", 3),
                Map.entry("citationPassedCount", 2),
                Map.entry("passRate", 0.75),
                Map.entry("hitRate", 1.0),
                Map.entry("citationPassRate", 2.0 / 3.0)
        );
        Map<String, Object> decision = Map.ofEntries(
                Map.entry("candidate", "parent-child"),
                Map.entry("baseline", "classic"),
                Map.entry("status", "PASSED"),
                Map.entry("reason", "passed"),
                Map.entry("metrics", metrics),
                Map.entry("classicMetrics", metrics),
                Map.entry("hitRateDelta", 0.1),
                Map.entry("citationPassRateDelta", 0.2),
                Map.entry("runRevision", 8L),
                Map.entry("currentRevision", 9L),
                Map.entry("indexReady", true)
        );
        RagEvalRun historical = RagEvalRun.builder()
                .id(runId)
                .kbId(kbId)
                .status(RagEvalRunStatus.COMPLETED)
                .passedCount(0)
                .totalCount(0)
                .metrics(Map.of())
                .profileSnapshot(Map.of())
                .policySnapshot(Map.of())
                .gateSummary(Map.of(
                        "parent-child", decision,
                        "qa-assisted", "invalid",
                        "combined", Map.of()
                ))
                .build();
        when(runRepository.findTop10ByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(historical));
        when(resultRepository.findByRunIdOrderByCaseKeyAsc(runId)).thenReturn(List.of());

        var response = service().listRuns(kbId).get(0);

        assertThat(response.getGateSummary().get(RetrievalProfile.PARENT_CHILD)).satisfies(gate -> {
            assertThat(gate.getStatus()).isEqualTo(RagEvalGateStatus.PASSED);
            assertThat(gate.getMetrics().getPassedCount()).isEqualTo(3);
            assertThat(gate.getHitRateDelta()).isEqualTo(0.1);
            assertThat(gate.getRunRevision()).isEqualTo(8L);
            assertThat(gate.getCurrentRevision()).isEqualTo(9L);
            assertThat(gate.isIndexReady()).isTrue();
        });
        assertThat(response.getGateSummary().get(RetrievalProfile.QA_ASSISTED).getStatus())
                .isEqualTo(RagEvalGateStatus.NOT_EVALUATED);
        assertThat(response.getGateSummary().get(RetrievalProfile.COMBINED).getMetrics().getTotalCases())
                .isZero();
    }

    @Test
    void listCasesReturnsPersistedCasesFromCoordinator() {
        UUID kbId = UUID.randomUUID();
        List<RagEvalCase> persisted = List.of(
                builtInCase(kbId, "formats-supported"),
                builtInCase(kbId, "core-capabilities"),
                builtInCase(kbId, "chunk-strategies"));
        when(caseCoordinator.loadCases(kbId)).thenReturn(persisted);
        when(caseValidationService.validate(kbId, persisted)).thenReturn(new RagEvalCaseValidationService.ValidationReport(
                Map.of(
                        persisted.get(0).getId(), new RagEvalCaseValidationService.CaseValidity(false, List.of("sample-knowledge.md")),
                        persisted.get(1).getId(), new RagEvalCaseValidationService.CaseValidity(true, List.of()),
                        persisted.get(2).getId(), new RagEvalCaseValidationService.CaseValidity(true, List.of())),
                List.of(persisted.get(0)), "fingerprint"));

        var cases = service().listCases(kbId);

        assertThat(cases).extracting(response -> response.getCaseKey()).containsExactly(
                "formats-supported",
                "core-capabilities",
                "chunk-strategies"
        );
        assertThat(cases.get(0).isSourceValid()).isFalse();
        assertThat(cases.get(0).getMissingExpectedFileNames()).containsExactly("sample-knowledge.md");
        assertThat(cases.get(1).isSourceValid()).isTrue();
        verify(caseCoordinator).loadCases(kbId);
    }

    @Test
    void runRejectsInvalidSourcesBeforeCreatingRunHistory() {
        UUID kbId = UUID.randomUUID();
        RagEvalCase invalid = caseEntity(kbId, UUID.randomUUID());
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(invalid));
        when(caseValidationService.validate(kbId, List.of(invalid))).thenReturn(
                new RagEvalCaseValidationService.ValidationReport(
                        Map.of(invalid.getId(), new RagEvalCaseValidationService.CaseValidity(false, List.of("guide.md"))),
                        List.of(invalid), "fingerprint"));

        assertThatThrownBy(() -> service().run(kbId, new RagEvalRunRequest()))
                .isInstanceOf(com.dupi.rag.exception.RagEvalCaseConflictException.class)
                .hasMessageContaining("guide.md");
        verify(runRepository, never()).save(any());
    }

    @Test
    void createCaseAppliesOptionalDefaultsAndNormalizesBlankFile() {
        UUID kbId = UUID.randomUUID();
        when(caseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        RagEvalCaseRequest request = new RagEvalCaseRequest();
        request.setCaseKey(" key ");
        request.setQuery(" query ");
        request.setExpectedFileName("  ");

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
    void createCaseRejectsDocumentIdsFromAnotherKnowledgeBase() {
        UUID kbId = UUID.randomUUID();
        UUID otherKbId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findAllById(List.of(documentId))).thenReturn(List.of(
                Document.builder().id(documentId).kbId(otherKbId).fileName("guide.md").build()));
        RagEvalCaseRequest request = new RagEvalCaseRequest();
        request.setCaseKey("cross-kb");
        request.setQuery("question");
        request.setExpectedDocumentId(documentId);
        request.setExpectedFileName("guide.md");

        assertThatThrownBy(() -> service().createCase(kbId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("knowledge base");
        verify(caseRepository, never()).save(any());
    }

    @Test
    void createCaseResolvesUniqueFilenameOnlyWebPayloadToStableId() {
        UUID kbId = UUID.randomUUID();
        Document guide = Document.builder().id(UUID.randomUUID()).kbId(kbId).fileName("guide.md").build();
        when(documentRepository.findByKbIdOrderByIdAsc(kbId)).thenReturn(List.of(guide));
        when(caseRepository.save(any(RagEvalCase.class))).thenAnswer(invocation -> {
            RagEvalCase saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        RagEvalCaseRequest request = new RagEvalCaseRequest();
        request.setCaseKey("legacy-web-single");
        request.setQuery("question");
        request.setExpectedFileName("guide.md");

        var response = service().createCase(kbId, request);

        assertThat(response.getExpectedDocumentId()).isEqualTo(guide.getId());
        assertThat(response.getExpectedFileName()).isEqualTo("guide.md");
    }

    @Test
    void createCaseResolvesUniqueMultiFilenameOnlyWebPayloadInOrder() {
        UUID kbId = UUID.randomUUID();
        Document guide = Document.builder().id(UUID.randomUUID()).kbId(kbId).fileName("guide.md").build();
        Document operations = Document.builder().id(UUID.randomUUID()).kbId(kbId).fileName("operations.md").build();
        when(documentRepository.findByKbIdOrderByIdAsc(kbId)).thenReturn(List.of(operations, guide));
        when(caseRepository.save(any(RagEvalCase.class))).thenAnswer(invocation -> {
            RagEvalCase saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        RagEvalCaseRequest request = new RagEvalCaseRequest();
        request.setCaseKey("legacy-web-multi");
        request.setQuery("question");
        request.setCategory(RagEvalCaseCategory.MULTI_DOCUMENT);
        request.setMinHits(2);
        request.setExpectedFileName("guide.md");
        request.setExpectedFileNames(List.of("operations.md"));

        assertThat(request.isValidCategoryAssertions()).isTrue();
        var response = service().createCase(kbId, request);

        assertThat(response.getExpectedDocumentIds()).containsExactly(guide.getId(), operations.getId());
        assertThat(response.getExpectedFileNames()).containsExactly("guide.md", "operations.md");
    }

    @Test
    void createCaseRejectsAmbiguousFilenameOnlyWebPayload() {
        UUID kbId = UUID.randomUUID();
        when(documentRepository.findByKbIdOrderByIdAsc(kbId)).thenReturn(List.of(
                Document.builder().id(UUID.randomUUID()).kbId(kbId).fileName("guide.md").build(),
                Document.builder().id(UUID.randomUUID()).kbId(kbId).fileName("guide.md").build()));
        RagEvalCaseRequest request = new RagEvalCaseRequest();
        request.setCaseKey("ambiguous");
        request.setQuery("question");
        request.setExpectedFileName("guide.md");

        assertThatThrownBy(() -> service().createCase(kbId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous")
                .hasMessageContaining("guide.md");
        verify(caseRepository, never()).save(any());
    }

    @Test
    void createCaseRejectsMissingFilenameOnlyWebPayload() {
        UUID kbId = UUID.randomUUID();
        when(documentRepository.findByKbIdOrderByIdAsc(kbId)).thenReturn(List.of());
        RagEvalCaseRequest request = new RagEvalCaseRequest();
        request.setCaseKey("missing");
        request.setQuery("question");
        request.setExpectedFileName("gone.md");

        assertThatThrownBy(() -> service().createCase(kbId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found")
                .hasMessageContaining("gone.md");
        verify(caseRepository, never()).save(any());
    }

    @Test
    void runRejectsKnowledgeBasesAboveTheCaseLimit() {
        UUID kbId = UUID.randomUUID();
        when(caseCoordinator.loadCases(kbId))
                .thenThrow(new IllegalArgumentException("at most 100 RAG eval cases"));

        assertThatThrownBy(() -> service().run(kbId, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void createUpdateDeleteAndListCasesAreScopedToKnowledgeBase() {
        UUID kbId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID guideId = UUID.randomUUID();
        UUID operationsId = UUID.randomUUID();
        RagEvalCase existing = caseEntity(kbId, caseId);
        when(documentRepository.findAllById(List.of(guideId, operationsId))).thenReturn(List.of(
                Document.builder().id(guideId).kbId(kbId).fileName("guide.md").build(),
                Document.builder().id(operationsId).kbId(kbId).fileName("operations.md").build()));
        when(caseRepository.save(any(RagEvalCase.class))).thenAnswer(inv -> {
            RagEvalCase saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(caseId);
            }
            return saved;
        });
        when(caseRepository.findByIdAndKbId(caseId, kbId)).thenReturn(Optional.of(existing));
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(existing));

        RagEvalCaseRequest request = new RagEvalCaseRequest();
        request.setCaseKey("install");
        request.setQuery("How to install?");
        request.setMinHits(2);
        request.setTopK(3);
        request.setCategory(RagEvalCaseCategory.MULTI_DOCUMENT);
        request.setExpectedDocumentIds(List.of(guideId, operationsId));
        request.setExpectedFileNames(List.of("guide.md", "operations.md"));
        request.setMustContainAny(List.of("install", "setup"));

        var created = service().createCase(kbId, request);
        request.setQuery("How to setup?");
        var updated = service().updateCase(kbId, caseId, request);
        var listed = service().listCases(kbId);
        service().deleteCase(kbId, caseId);

        assertThat(created.getId()).isEqualTo(caseId);
        assertThat(created.getCategory()).isEqualTo(RagEvalCaseCategory.MULTI_DOCUMENT);
        assertThat(created.getExpectedDocumentIds()).containsExactly(guideId, operationsId);
        assertThat(created.getExpectedFileNames()).containsExactly("guide.md", "operations.md");
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
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(evalCase));
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
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(evalCase));
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
    void runAppliesExperimentTopKOverrideAndStoresMetadata() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase evalCase = caseEntity(kbId, UUID.randomUUID());
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(evalCase));
        when(retrievalService.retrieve(eq(kbId), any())).thenAnswer(invocation -> {
            RetrieveRequest request = invocation.getArgument(1);
            return RetrieveResponse.builder()
                    .query(request.getQuery())
                    .retrievalMode("vector")
                    .hits(List.of(RetrievalHit.builder()
                            .chunkId(UUID.randomUUID())
                            .docId(UUID.randomUUID())
                            .fileName("guide.md")
                            .content("install command")
                            .score(0.9)
                            .metadata(Map.of())
                            .build()))
                    .build();
        });
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any(RagEvalRun.class))).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0);
            run.setId(runId);
            return run;
        });

        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setUseRerank(true);
        request.setTopKOverride(9);
        request.setExperimentLabel(" candidate-topk-9 ");

        var response = service().run(kbId, request);

        ArgumentCaptor<RetrieveRequest> requestCaptor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrievalService).retrieve(eq(kbId), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getTopK()).isEqualTo(9);
        assertThat(response.getProfileSnapshot())
                .containsEntry("experimentLabel", "candidate-topk-9")
                .containsEntry("topKOverride", 9)
                .containsEntry("useRerank", true);
        assertThat(response.getResults()).singleElement().satisfies(result -> {
            assertThat(result.getTopK()).isEqualTo(9);
            assertThat(result.isPassed()).isTrue();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiProfileRunBuildsCategoryProfileComparisonAndReleaseGateMetrics() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase evalCase = caseEntity(kbId, UUID.randomUUID());
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(evalCase));
        when(retrievalService.retrieve(eq(kbId), any())).thenAnswer(invocation -> {
            RetrieveRequest request = invocation.getArgument(1);
            if (request.getRetrievalProfile() == RetrievalProfile.PARENT_CHILD) {
                return RetrieveResponse.builder()
                        .retrievalMode("hybrid")
                        .diagnostics(Map.of("retrievalMode", "hybrid"))
                        .hits(List.of())
                        .build();
            }
            return RetrieveResponse.builder()
                    .retrievalMode("vector")
                    .diagnostics(Map.of("retrievalMode", "vector"))
                    .hits(List.of(RetrievalHit.builder()
                            .chunkId(UUID.randomUUID())
                            .docId(UUID.randomUUID())
                            .fileName("guide.md")
                            .content("install command")
                            .score(0.9)
                            .metadata(Map.of())
                            .build()))
                    .build();
        });
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any(RagEvalRun.class))).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0);
            run.setId(runId);
            return run;
        });
        when(profileIndexStateService.currentRevision(kbId)).thenReturn(17L);

        var response = service().run(kbId, false, List.of(RetrievalProfile.PARENT_CHILD));

        Map<String, Object> categorySummaries = (Map<String, Object>) response.getMetrics().get("categorySummaries");
        Map<String, Object> realQuery = (Map<String, Object>) categorySummaries.get("REAL_QUERY");
        assertThat(realQuery).containsEntry("total", 2).containsEntry("passed", 1);

        Map<String, Object> profileSummaries = (Map<String, Object>) response.getMetrics().get("profileSummaries");
        assertThat(profileSummaries).containsKeys("classic", "parent-child");

        Map<String, Object> comparisons = (Map<String, Object>) response.getMetrics().get("profileComparisons");
        Map<String, Object> parentChild = (Map<String, Object>) comparisons.get("parent-child");
        assertThat(parentChild)
                .containsEntry("baseline", "classic")
                .containsEntry("candidate", "parent-child")
                .containsEntry("passRateDelta", -1.0)
                .containsEntry("hitRateDelta", -1.0);

        Map<String, Object> releaseGate = (Map<String, Object>) response.getMetrics().get("releaseGate");
        assertThat(releaseGate).containsEntry("status", "BLOCKED");
        assertThat((Map<String, Long>) releaseGate.get("failureCategoryCounts"))
                .containsEntry("INSUFFICIENT_HITS", 1L);
    }

    @Test
    void noAnswerCaseFailsWhenLegacyRetrievalReturnsEvidence() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase evalCase = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("no-answer").query("unknown").minHits(0).topK(5)
                .expectedFileName(null).mustContainAny(List.of()).build();
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(evalCase));
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder()
                .hits(List.of(RetrievalHit.builder().chunkId(UUID.randomUUID()).docId(UUID.randomUUID())
                        .fileName("unrelated.md").content("unrelated evidence").score(0.1).build()))
                .build());
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0);
            run.setId(runId);
            return run;
        });

        var response = service().run(kbId, false, null, null);

        assertThat(response.getResults()).singleElement().satisfies(result -> {
            assertThat(result.isPassed()).isFalse();
            assertThat(result.getFailureReasons()).contains("expected no hits, got 1");
            assertThat(result.getFailureCategories()).containsExactly("UNEXPECTED_EVIDENCE");
        });
        assertThat(response.getMetrics()).containsKey("failureCategoryCounts");
        assertThat((Map<String, Long>) response.getMetrics().get("failureCategoryCounts"))
                .containsEntry("UNEXPECTED_EVIDENCE", 1L);
    }

    @Test
    void runClassifiesInsufficientHitsAndMissingExpectedEvidence() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase evalCase = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("missing-evidence").query("How to install?").minHits(2).topK(5)
                .expectedFileName("guide.md").mustContainAny(List.of("install")).build();
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(evalCase));
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder()
                .hits(List.of(RetrievalHit.builder().chunkId(UUID.randomUUID()).docId(UUID.randomUUID())
                        .fileName("other.md").content("unrelated evidence").score(0.1).build()))
                .build());
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0);
            run.setId(runId);
            return run;
        });

        var response = service().run(kbId, false, null, null);

        assertThat(response.getResults()).singleElement().satisfies(result ->
                assertThat(result.getFailureCategories()).containsExactly(
                        "INSUFFICIENT_HITS", "MISSING_EXPECTED_FILE", "MISSING_EXPECTED_TOKEN"));
        assertThat((Map<String, Long>) response.getMetrics().get("failureCategoryCounts"))
                .containsExactly(
                        entry("INSUFFICIENT_HITS", 1L),
                        entry("MISSING_EXPECTED_FILE", 1L),
                        entry("MISSING_EXPECTED_TOKEN", 1L));
    }

    @Test
    void multiDocumentCaseRequiresHitsFromEveryExpectedSource() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase evalCase = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("multi-source").query("How do release and recovery work together?")
                .category(RagEvalCaseCategory.MULTI_DOCUMENT).minHits(2).topK(5)
                .expectedFileName("release.md").expectedFileNames(List.of("recovery.md"))
                .mustContainAny(List.of("rollback")).build();
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(evalCase));
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder()
                .hits(List.of(
                        RetrievalHit.builder().chunkId(UUID.randomUUID()).docId(UUID.randomUUID())
                                .fileName("release.md").content("release rollback gate").score(0.9).build(),
                        RetrievalHit.builder().chunkId(UUID.randomUUID()).docId(UUID.randomUUID())
                                .fileName("release.md").content("release verification").score(0.8).build()
                ))
                .build());
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0);
            run.setId(runId);
            return run;
        });

        var response = service().run(kbId, false, null, null);

        assertThat(response.getResults()).singleElement().satisfies(result -> {
            assertThat(result.isPassed()).isFalse();
            assertThat(result.getCategory()).isEqualTo(RagEvalCaseCategory.MULTI_DOCUMENT);
            assertThat(result.getExpectedFileNames()).containsExactly("recovery.md");
            assertThat(result.getMatchedFileNames()).containsExactly("release.md");
            assertThat(result.getFailureReasons()).contains("missing expected files recovery.md");
            assertThat(result.getFailureCategories()).contains("MISSING_EXPECTED_FILE");
        });
    }

    @Test
    void multiDocumentCasePassesWhenEveryExpectedSourceIsPresent() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase evalCase = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("multi-source-pass").query("How do release and recovery work together?")
                .category(RagEvalCaseCategory.MULTI_DOCUMENT).minHits(2).topK(5)
                .expectedFileName("release.md").expectedFileNames(List.of("recovery.md"))
                .mustContainAny(List.of("rollback")).build();
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(evalCase));
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder()
                .hits(List.of(
                        RetrievalHit.builder().chunkId(UUID.randomUUID()).docId(UUID.randomUUID())
                                .fileName("release.md").content("release gate").score(0.9).build(),
                        RetrievalHit.builder().chunkId(UUID.randomUUID()).docId(UUID.randomUUID())
                                .fileName("recovery.md").content("rollback procedure").score(0.8).build()
                ))
                .build());
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0);
            run.setId(runId);
            return run;
        });

        var response = service().run(kbId, false, null, null);

        assertThat(response.getResults()).singleElement().satisfies(result -> {
            assertThat(result.isPassed()).isTrue();
            assertThat(result.isCitationPassed()).isTrue();
            assertThat(result.getMatchedFileNames()).containsExactly("release.md", "recovery.md");
            assertThat(result.getFailureReasons()).isEmpty();
        });
    }

    @Test
    void additionalExpectedSourcesCountForSourceMetricsAndRankEvidence() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase evalCase = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("additional-source-only").query("How does recovery work?")
                .category(RagEvalCaseCategory.REAL_QUERY).minHits(1).topK(5)
                .expectedFileName(null).expectedFileNames(List.of("recovery.md"))
                .mustContainAny(List.of()).build();
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(evalCase));
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder()
                .hits(List.of(
                        RetrievalHit.builder().chunkId(UUID.randomUUID()).docId(UUID.randomUUID())
                                .fileName("release.md").content("release checklist").score(0.9)
                                .metadata(Map.of("retrievalStages", Map.of("vectorRank", 10))).build(),
                        RetrievalHit.builder().chunkId(UUID.randomUUID()).docId(UUID.randomUUID())
                                .fileName("recovery.md").content("recovery checklist").score(0.8)
                                .metadata(Map.of("retrievalStages", Map.of("vectorRank", 20))).build()
                ))
                .build());
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0);
            run.setId(runId);
            return run;
        });

        var response = service().run(kbId, false, null, null);

        assertThat(response.getMetrics()).containsEntry("eligibleExpectedFileHitRate", 100.0);
        assertThat(response.getResults()).singleElement().satisfies(result -> {
            assertThat(result.isPassed()).isTrue();
            assertThat(result.isCitationPassed()).isTrue();
            assertThat(result.getMatchedFileName()).isEqualTo("recovery.md");
            assertThat(result.getMatchedRank()).isEqualTo(2);
            assertThat(result.getVectorRank()).isEqualTo(20);
        });
    }

    @Test
    void runClassifiesRetrievalExceptions() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(caseEntity(kbId, UUID.randomUUID())));
        when(retrievalService.retrieve(eq(kbId), any())).thenThrow(new IllegalStateException("retrieval unavailable"));
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0);
            run.setId(runId);
            return run;
        });

        var response = service().run(kbId, false, null, null);

        assertThat(response.getResults()).singleElement().satisfies(result -> {
            assertThat(result.getFailureReasons()).containsExactly("retrieval unavailable");
            assertThat(result.getFailureCategories()).containsExactly("RETRIEVAL_EXCEPTION");
        });
        assertThat((Map<String, Long>) response.getMetrics().get("failureCategoryCounts"))
                .containsExactly(entry("RETRIEVAL_EXCEPTION", 1L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void metricsExposeV20QualityClosureRollupsAndReleaseReport() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase realQuery = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("real-install").query("How do I install?")
                .category(RagEvalCaseCategory.REAL_QUERY).minHits(1).topK(5)
                .expectedFileName("guide.md").mustContainAny(List.of("install")).build();
        RagEvalCase hardNegative = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("no-answer").query("What is the moon password?")
                .category(RagEvalCaseCategory.HARD_NEGATIVE).minHits(0).topK(5)
                .mustContainAny(List.of()).build();
        RagEvalCase multiDocument = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("multi-source").query("Compare release and recovery")
                .category(RagEvalCaseCategory.MULTI_DOCUMENT).minHits(2).topK(5)
                .expectedFileName("release.md").expectedFileNames(List.of("recovery.md"))
                .mustContainAny(List.of("release")).build();
        RagEvalCase ambiguous = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("ambiguous-version").query("Which version is current?")
                .category(RagEvalCaseCategory.AMBIGUOUS).minHits(1).topK(5)
                .expectedFileName("release.md").mustContainAny(List.of("2.5.4")).build();
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(realQuery, hardNegative, multiDocument, ambiguous));
        when(profileIndexStateService.currentRevision(kbId)).thenReturn(7L);
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);
        when(retrievalProfileGateService.calculate(any(), eq(7L), eq(7L), eq(true))).thenReturn(Map.of(
                RetrievalProfile.CLASSIC, gate(RetrievalProfile.CLASSIC),
                RetrievalProfile.PARENT_CHILD, gate(RetrievalProfile.PARENT_CHILD)
        ));
        when(retrievalService.retrieve(eq(kbId), any())).thenAnswer(invocation -> {
            RetrieveRequest request = invocation.getArgument(1);
            if (request.getQuery().contains("install")) {
                return RetrieveResponse.builder().retrievalMode("hybrid")
                        .diagnostics(Map.of("retrievalMode", "hybrid", "embeddingModel", "embedding-2",
                                "embeddingDimension", 1024))
                        .hits(List.of(RetrievalHit.builder().fileName("guide.md").content("install setup").score(0.9)
                                .metadata(Map.of("retrievalStages", Map.of("vectorRank", 1))).build()))
                        .build();
            }
            if (request.getQuery().contains("moon")) {
                return RetrieveResponse.builder().retrievalMode("hybrid")
                        .diagnostics(Map.of("retrievalMode", "hybrid", "fallbackReason", "local_text"))
                        .hits(List.of(RetrievalHit.builder().fileName("noise.md").content("unexpected").score(0.1).build()))
                        .build();
            }
            if (request.getQuery().contains("Compare")) {
                return RetrieveResponse.builder().retrievalMode("hybrid")
                        .hits(List.of(RetrievalHit.builder().fileName("release.md").content("release steps").score(0.8).build()))
                        .build();
            }
            return RetrieveResponse.builder().retrievalMode("hybrid")
                    .hits(List.of(RetrievalHit.builder().fileName("release.md").content("current release").score(0.7).build()))
                    .build();
        });
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0);
            run.setId(runId);
            return run;
        });

        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setProfiles(List.of(RetrievalProfile.CLASSIC, RetrievalProfile.PARENT_CHILD));
        request.setTopKOverride(8);
        request.setExperimentLabel("release-candidate-v2");

        var response = service().run(kbId, request);

        Map<String, Object> releaseReadiness = (Map<String, Object>) response.getMetrics().get("releaseReadiness");
        assertThat(releaseReadiness).containsEntry("version", "V2.0");
        assertThat(releaseReadiness).containsEntry("status", "BLOCKED");
        assertThat((List<String>) releaseReadiness.get("requiredEvidence")).contains("categorySummaries", "releaseGate");
        Map<String, Object> feedbackLoop = (Map<String, Object>) response.getMetrics().get("realQueryFeedback");
        assertThat(feedbackLoop).containsEntry("version", "V2.0");
        assertThat((Integer) feedbackLoop.get("candidateCount")).isGreaterThan(0);
        assertThat((List<Map<String, Object>>) feedbackLoop.get("candidates"))
                .allSatisfy(candidate -> assertThat(candidate)
                        .containsEntry("reviewStatus", "OPEN")
                        .containsEntry("sourceRunId", runId.toString())
                        .containsKeys("id", "retrievalProfile"));
        List<String> candidateIds = ((List<Map<String, Object>>) feedbackLoop.get("candidates")).stream()
                .map(candidate -> String.valueOf(candidate.get("id")))
                .toList();
        assertThat(candidateIds).doesNotHaveDuplicates();
        Map<String, Object> experimentMatrix = (Map<String, Object>) response.getMetrics().get("experimentMatrix");
        assertThat(experimentMatrix).containsEntry("version", "V2.0");
        assertThat((List<Integer>) experimentMatrix.get("topKValues")).containsExactly(8);
        assertThat((List<String>) experimentMatrix.get("profiles")).contains("classic", "parent-child");
        Map<String, Object> answerQuality = (Map<String, Object>) response.getMetrics().get("answerQuality");
        assertThat(answerQuality).containsEntry("version", "V2.0");
        assertThat(answerQuality).containsEntry("judgeStatus", "REVIEW_REQUIRED");
        assertThat((Double) answerQuality.get("groundedPassRate")).isLessThan(1.0);
        assertThat((List<Map<String, Object>>) answerQuality.get("riskCases"))
                .anySatisfy(item -> assertThat(item).containsEntry("caseKey", "no-answer"));
        Map<String, Object> onlineObservability = (Map<String, Object>) response.getMetrics().get("onlineObservability");
        assertThat(onlineObservability).containsEntry("version", "V2.0");
        assertThat(onlineObservability).containsEntry("sloStatus", "DEGRADED");
        assertThat((Integer) onlineObservability.get("fallbackCount")).isGreaterThan(0);
        Map<String, Object> dataIndexGovernance = (Map<String, Object>) response.getMetrics().get("dataIndexGovernance");
        assertThat(dataIndexGovernance).containsEntry("version", "V2.0");
        assertThat(dataIndexGovernance).containsEntry("governanceStatus", "ACTION_REQUIRED");
        assertThat((Integer) dataIndexGovernance.get("multiDocumentCaseCount")).isEqualTo(2);
        Map<String, Object> onlineSlo = (Map<String, Object>) response.getMetrics().get("onlineSlo");
        assertThat(onlineSlo).containsEntry("version", "V2.0");
        assertThat(onlineSlo).containsEntry("status", "BREACHED");
        assertThat((List<String>) onlineSlo.get("breachedObjectives")).contains("fallbackRate", "passRate");
        Map<String, Object> canaryGate = (Map<String, Object>) response.getMetrics().get("canaryGate");
        assertThat(canaryGate).containsEntry("version", "V2.0");
        assertThat(canaryGate).containsEntry("decision", "ROLLBACK");
        assertThat((List<String>) canaryGate.get("reasons")).contains("releaseGateBlocked", "onlineSloBreached");
        assertThat((Map<String, String>) canaryGate.get("profileGateStatuses"))
                .containsEntry("parent-child", "PASSED");
        Map<String, Object> closure = (Map<String, Object>) response.getMetrics().get("v2QualityClosure");
        assertThat(closure).containsEntry("version", "V2.0");
        assertThat(closure).containsEntry("status", "BLOCKED");
        assertThat((List<String>) closure.get("completedCapabilities")).contains(
                "persistentFeedbackCandidates",
                "deterministicAnswerJudge",
                "retrievalExperimentMatrix",
                "dataIndexGovernanceSummary",
                "onlineQualitySlo",
                "canaryPromoteRollbackGate"
        );
        assertThat((List<String>) closure.get("recommendedActions")).contains(
                "promote_failed_or_degraded_real_queries_to_eval_cases",
                "review_missing_sources_and_rebuild_index",
                "hold_canary_and_triage_quality_regressions"
        );
        Map<String, Object> releaseReport = (Map<String, Object>) response.getMetrics().get("releaseReport");
        assertThat(releaseReport)
                .containsEntry("version", "V2.0")
                .containsEntry("releaseName", "RAG Quality Closure")
                .containsEntry("releaseGateStatus", "BLOCKED")
                .containsEntry("canaryDecision", "ROLLBACK")
                .containsEntry("closureStatus", "BLOCKED")
                .containsEntry("recommendedAction", "rollback_or_fix_blockers");
    }

    @Test
    @SuppressWarnings("unchecked")
    void v20ClosurePromotesPassingCandidateAndAcceptsCorrectNoAnswerCases() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase grounded = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("grounded-answer").query("How do I install?")
                .category(RagEvalCaseCategory.REAL_QUERY).minHits(1).topK(5)
                .expectedFileName("guide.md").mustContainAny(List.of("install")).build();
        RagEvalCase noAnswer = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("correct-no-answer").query("What is the moon password?")
                .category(RagEvalCaseCategory.HARD_NEGATIVE).minHits(0).topK(5)
                .mustContainAny(List.of()).build();
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(grounded, noAnswer));
        when(profileIndexStateService.currentRevision(kbId)).thenReturn(9L);
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);
        when(retrievalProfileGateService.calculate(any(), eq(9L), eq(9L), eq(true))).thenReturn(Map.of(
                RetrievalProfile.CLASSIC, gate(RetrievalProfile.CLASSIC),
                RetrievalProfile.PARENT_CHILD, gate(RetrievalProfile.PARENT_CHILD)
        ));
        when(retrievalService.retrieve(eq(kbId), any())).thenAnswer(invocation -> {
            RetrieveRequest request = invocation.getArgument(1);
            if (request.getQuery().contains("moon")) {
                return RetrieveResponse.builder().retrievalMode("hybrid").hits(List.of()).build();
            }
            return RetrieveResponse.builder().retrievalMode("hybrid")
                    .diagnostics(Map.of("fallbackReason", "none"))
                    .hits(List.of(RetrievalHit.builder().fileName("guide.md")
                            .content("install setup").score(0.9).build()))
                    .build();
        });
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0);
            run.setId(runId);
            return run;
        });

        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setProfiles(List.of(RetrievalProfile.CLASSIC, RetrievalProfile.PARENT_CHILD));

        var response = service().run(kbId, request);

        Map<String, Object> feedback = (Map<String, Object>) response.getMetrics().get("realQueryFeedback");
        assertThat(feedback).containsEntry("candidateCount", 0);
        Map<String, Object> answerQuality = (Map<String, Object>) response.getMetrics().get("answerQuality");
        assertThat(answerQuality)
                .containsEntry("judgeStatus", "PASS")
                .containsEntry("hallucinationRiskCount", 0)
                .containsEntry("unsupportedAnswerRiskCount", 0);
        assertThat((List<Map<String, Object>>) answerQuality.get("riskCases")).isEmpty();
        Map<String, Object> onlineSlo = (Map<String, Object>) response.getMetrics().get("onlineSlo");
        assertThat(onlineSlo).containsEntry("status", "MET");
        Map<String, Object> canaryGate = (Map<String, Object>) response.getMetrics().get("canaryGate");
        assertThat(canaryGate)
                .containsEntry("decision", "PROMOTE")
                .containsEntry("shadowEvalRequired", false);
        assertThat((List<String>) canaryGate.get("candidateProfiles")).containsExactly("parent-child");
        assertThat((Map<String, String>) canaryGate.get("profileGateStatuses"))
                .containsEntry("parent-child", "PASSED");
        assertThat((List<String>) canaryGate.get("reasons")).isEmpty();
        Map<String, Object> closure = (Map<String, Object>) response.getMetrics().get("v2QualityClosure");
        assertThat(closure).containsEntry("status", "READY");
        assertThat((List<String>) closure.get("recommendedActions"))
                .contains("promote_candidate_defaults", "record_release_report");
        Map<String, Object> releaseReport = (Map<String, Object>) response.getMetrics().get("releaseReport");
        assertThat(releaseReport)
                .containsEntry("releaseGateStatus", "PASS")
                .containsEntry("canaryDecision", "PROMOTE")
                .containsEntry("closureStatus", "READY")
                .containsEntry("recommendedAction", "promote_candidate_defaults");
    }

    @Test
    @SuppressWarnings("unchecked")
    void v20CanaryRollsBackWhenProfileGateBlocksEvenIfEvalMetricsPass() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase grounded = RagEvalCase.builder().id(UUID.randomUUID()).kbId(kbId)
                .caseKey("grounded-answer").query("How do I install?")
                .category(RagEvalCaseCategory.REAL_QUERY).minHits(1).topK(5)
                .expectedFileName("guide.md").mustContainAny(List.of("install")).build();
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(grounded));
        when(profileIndexStateService.currentRevision(kbId)).thenReturn(9L);
        when(profileIndexStateService.isV2Ready(kbId)).thenReturn(true);
        when(retrievalProfileGateService.calculate(any(), eq(9L), eq(9L), eq(true))).thenReturn(Map.of(
                RetrievalProfile.CLASSIC, gate(RetrievalProfile.CLASSIC),
                RetrievalProfile.PARENT_CHILD, blockedGate(RetrievalProfile.PARENT_CHILD)
        ));
        when(retrievalService.retrieve(eq(kbId), any())).thenReturn(RetrieveResponse.builder().retrievalMode("hybrid")
                .hits(List.of(RetrievalHit.builder().fileName("guide.md").content("install setup").score(0.9).build()))
                .build());
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0);
            run.setId(runId);
            return run;
        });

        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setProfiles(List.of(RetrievalProfile.CLASSIC, RetrievalProfile.PARENT_CHILD));

        var response = service().run(kbId, request);

        Map<String, Object> onlineSlo = (Map<String, Object>) response.getMetrics().get("onlineSlo");
        assertThat(onlineSlo).containsEntry("status", "MET");
        Map<String, Object> canaryGate = (Map<String, Object>) response.getMetrics().get("canaryGate");
        assertThat(canaryGate).containsEntry("decision", "ROLLBACK");
        assertThat((Map<String, String>) canaryGate.get("profileGateStatuses"))
                .containsEntry("parent-child", "BLOCKED");
        assertThat((List<String>) canaryGate.get("reasons"))
                .containsExactly("profileGateBlocked:parent-child");
        Map<String, Object> closure = (Map<String, Object>) response.getMetrics().get("v2QualityClosure");
        assertThat(closure).containsEntry("status", "BLOCKED");
    }

    @Test
    void runCanExplicitlyEvaluateVectorModeAndRejectsVectorProfileCombination() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(caseEntity(kbId, UUID.randomUUID())));
        when(retrievalService.retrieveForEvaluation(eq(kbId), any(),
                eq(com.dupi.rag.domain.enums.RetrievalMode.VECTOR)))
                .thenReturn(RetrieveResponse.builder().retrievalMode("vector").hits(List.of()).build());
        when(runRepository.save(any())).thenAnswer(invocation -> {
            RagEvalRun saved = invocation.getArgument(0);
            saved.setId(runId);
            return saved;
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
        request.setMinimumPassRate(90);
        request.setMaximumPassRateDrop(2);
        request.setMaximumNewFailures(1);
        request.setBlockWhenUnbaselined(true);
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
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(caseEntity(kbId, UUID.randomUUID())));
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
    void legacyRunMarksHistoryFailedWhenResultPersistenceBreaks() {
        UUID kbId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(caseEntity(kbId, UUID.randomUUID())));
        List<RagEvalRunStatus> savedStatuses = new ArrayList<>();
        when(runRepository.save(any(RagEvalRun.class))).thenAnswer(invocation -> {
            RagEvalRun run = invocation.getArgument(0);
            savedStatuses.add(run.getStatus());
            run.setId(runId);
            return run;
        });
        when(resultRepository.save(any())).thenThrow(new IllegalStateException("database write failed"));

        assertThatThrownBy(() -> service().run(kbId, false, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAG evaluation failed")
                .hasRootCauseMessage("database write failed");

        assertThat(savedStatuses).containsExactly(RagEvalRunStatus.RUNNING, RagEvalRunStatus.FAILED);
    }

    @Test
    void runRequestWithCandidateProfilePersistsExactProfileSnapshot() {
        UUID kbId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        RagEvalCase evalCase = caseEntity(kbId, UUID.randomUUID());
        com.dupi.rag.domain.entity.RetrievalProfile profile =
                com.dupi.rag.domain.entity.RetrievalProfile.builder()
                        .id(profileId).kbId(kbId).name("candidate").version(3)
                        .vectorCandidateCount(40).sparseCandidateCount(25).rrfConstant(50)
                        .rerankEnabled(false).rerankCandidateLimit(15).finalTopK(4).build();
        when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(evalCase));
        when(retrievalProfileService.find(kbId, profileId)).thenReturn(profile);
        when(retrievalService.retrieveForProfile(eq(kbId), any(), eq(profileId)))
                .thenReturn(RetrieveResponse.builder().retrievalMode("hybrid").hits(List.of()).build());
        when(runRepository.save(any(RagEvalRun.class))).thenAnswer(invocation -> {
            RagEvalRun saved = invocation.getArgument(0);
            saved.setId(runId);
            return saved;
        });

        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setUseRerank(true);
        request.setProfileId(profileId);
        var response = service().run(kbId, request);

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
                retrievalProfileService,
                maintenanceService,
                profileIndexStateService,
                retrievalProfileGateService,
                caseValidationService,
                documentRepository
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

    private static RagEvalGateDecisionResponse blockedGate(RetrievalProfile candidate) {
        RagEvalProfileMetricsResponse metrics = RagEvalProfileMetricsResponse.builder()
                .profile(candidate)
                .totalCases(1)
                .passedCount(1)
                .hitPassedCount(1)
                .citationEligibleCount(1)
                .citationPassedCount(1)
                .passRate(1.0)
                .hitRate(1.0)
                .citationPassRate(1.0)
                .build();
        return RagEvalGateDecisionResponse.builder()
                .candidate(candidate)
                .baseline(RetrievalProfile.CLASSIC)
                .status(RagEvalGateStatus.BLOCKED)
                .reason("candidate gate blocked")
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
