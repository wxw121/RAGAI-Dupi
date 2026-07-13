package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.dto.RagEvalCaseRequest;
import com.dupi.rag.dto.RetrievalHit;
import com.dupi.rag.dto.RetrieveResponse;
import com.dupi.rag.repository.RagEvalCaseRepository;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.RagEvalRunResultRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

        var response = service().run(kbId, true);

        assertThat(response.getId()).isEqualTo(runId);
        assertThat(response.getPassedCount()).isEqualTo(1);
        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(RagEvalRunStatus.COMPLETED);
        assertThat(response.getFailureMessage()).isNull();
        assertThat(savedStatuses).containsExactly(RagEvalRunStatus.RUNNING, RagEvalRunStatus.COMPLETED);
        assertThat(response.getResults()).singleElement().satisfies(result -> {
            assertThat(result.isPassed()).isTrue();
            assertThat(result.getMatchedFileName()).isEqualTo("guide.md");
            assertThat(result.getMatchedToken()).isEqualTo("install");
            assertThat(result.getRetrievalMode()).isEqualTo("hybrid_rerank");
            assertThat(result.getEmbeddingDimension()).isEqualTo(1024);
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
                caseCoordinator
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
