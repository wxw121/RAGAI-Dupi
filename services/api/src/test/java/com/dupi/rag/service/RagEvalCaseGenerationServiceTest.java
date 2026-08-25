package com.dupi.rag.service;

import com.dupi.rag.client.LlmClient;
import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.RagEvalCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagEvalCaseGenerationServiceTest {

    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock DocumentRepository documentRepository;
    @Mock ChunkRepository chunkRepository;
    @Mock RagEvalCaseRepository caseRepository;
    @Mock LlmClient llmClient;
    @Mock KnowledgeBaseMaintenanceService maintenanceService;
    RagEvalCaseValidationService validationService;

    @BeforeEach
    void setUpValidationService() {
        validationService = new RagEvalCaseValidationService(documentRepository);
    }

    @Test
    void previewOnlyFillsEachDocumentsSingleSourceDeficit() {
        UUID kbId = UUID.randomUUID();
        Document one = document(kbId, "one.md");
        Document two = document(kbId, "two.md");
        Document covered = document(kbId, "covered.md");
        List<Document> documents = List.of(one, two, covered);
        List<RagEvalCase> cases = List.of(
                singleSource(kbId, "one-existing", one),
                singleSource(kbId, "covered-a", covered),
                singleSource(kbId, "covered-b", covered),
                multiSource(kbId, "cross-doc", one, two),
                sourceFree(kbId, "generic"));
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(documents);
        when(caseRepository.findByKbIdOrderByCreatedAtAsc(kbId)).thenReturn(cases);
        when(chunkRepository.countByDocId(one.getId())).thenReturn(1L);
        when(chunkRepository.countByDocId(two.getId())).thenReturn(1L);
        when(chunkRepository.countByDocId(covered.getId())).thenReturn(1L);
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(one.getId()))
                .thenReturn(List.of(chunk(one, "alpha evidence")));
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(two.getId()))
                .thenReturn(List.of(chunk(two, "beta gamma evidence")));
        when(llmClient.chat(anyString(), contains("one.md"))).thenReturn(jsonCases("one.md", "alpha"));
        when(llmClient.chat(anyString(), contains("two.md"))).thenReturn(jsonCases("two.md", "beta", "gamma"));

        var preview = service().preview(kbId);

        assertThat(preview.getDocuments()).filteredOn(item -> item.getFileName().equals("one.md"))
                .singleElement().satisfies(item -> {
                    assertThat(item.getDeficit()).isEqualTo(1);
                    assertThat(item.getProposals()).hasSize(1);
                });
        assertThat(preview.getDocuments()).filteredOn(item -> item.getFileName().equals("two.md"))
                .singleElement().satisfies(item -> {
                    assertThat(item.getDeficit()).isEqualTo(2);
                    assertThat(item.getProposals()).hasSize(2);
                });
        assertThat(preview.getDocuments()).filteredOn(item -> item.getFileName().equals("covered.md"))
                .singleElement().satisfies(item -> {
                    assertThat(item.getDeficit()).isZero();
                    assertThat(item.isCovered()).isTrue();
                });
        verify(llmClient, never()).chat(anyString(), contains("covered.md"));
        verify(caseRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void previewReportsPerDocumentGenerationErrorsWithoutMutatingCases() {
        UUID kbId = UUID.randomUUID();
        Document ok = document(kbId, "ok.md");
        Document failed = document(kbId, "failed.md");
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(ok, failed));
        when(caseRepository.findByKbIdOrderByCreatedAtAsc(kbId)).thenReturn(List.of());
        when(chunkRepository.countByDocId(ok.getId())).thenReturn(1L);
        when(chunkRepository.countByDocId(failed.getId())).thenReturn(1L);
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(ok.getId()))
                .thenReturn(List.of(chunk(ok, "alpha beta")));
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(failed.getId()))
                .thenReturn(List.of(chunk(failed, "failed evidence")));
        when(llmClient.chat(anyString(), contains("ok.md"))).thenReturn(jsonCases("ok.md", "alpha", "beta"));
        when(llmClient.chat(anyString(), contains("failed.md"))).thenReturn("not-json");

        var preview = service().preview(kbId);

        assertThat(preview.isConfirmable()).isFalse();
        assertThat(preview.getDocuments()).filteredOn(item -> item.getFileName().equals("failed.md"))
                .singleElement().satisfies(item -> assertThat(item.getError()).contains("Invalid AI response"));
        verify(caseRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void previewRetriesUngroundedKeywordsAndReturnsTheCorrectedCases() {
        UUID kbId = UUID.randomUUID();
        Document document = document(kbId, "packages.md");
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(document));
        when(caseRepository.findByKbIdOrderByCreatedAtAsc(kbId)).thenReturn(List.of());
        when(chunkRepository.countByDocId(document.getId())).thenReturn(1L);
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(document.getId()))
                .thenReturn(List.of(chunk(document, "pip poetry evidence lock file")));
        when(llmClient.chat(anyString(), contains("packages.md")))
                .thenReturn("{\"cases\":[{\"caseKey\":\"bad-1\",\"query\":\"q1\",\"expectedFileName\":\"packages.md\",\"mustContainAny\":[\"invented\",\"pip\"]},{\"caseKey\":\"bad-2\",\"query\":\"q2\",\"expectedFileName\":\"packages.md\",\"mustContainAny\":[\"poetry\",\"evidence\"]}]}")
                .thenReturn("{\"cases\":[{\"caseKey\":\"pip-case\",\"query\":\"q1\",\"expectedFileName\":\"packages.md\",\"mustContainAny\":[\"pip\",\"evidence\"]},{\"caseKey\":\"poetry-case\",\"query\":\"q2\",\"expectedFileName\":\"packages.md\",\"mustContainAny\":[\"poetry\",\"lock file\"]}]}");

        var preview = service().preview(kbId);

        assertThat(preview.isConfirmable()).isTrue();
        assertThat(preview.getDocuments()).singleElement().satisfies(item -> {
            assertThat(item.getError()).isNull();
            assertThat(item.getProposals()).hasSize(2);
        });
        verify(llmClient, org.mockito.Mockito.times(2)).chat(anyString(), contains("packages.md"));
    }

    @Test
    void previewNamesUngroundedKeywordsAfterRetriesAreExhausted() {
        UUID kbId = UUID.randomUUID();
        Document document = document(kbId, "packages.md");
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(document));
        when(caseRepository.findByKbIdOrderByCreatedAtAsc(kbId)).thenReturn(List.of());
        when(chunkRepository.countByDocId(document.getId())).thenReturn(1L);
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(document.getId()))
                .thenReturn(List.of(chunk(document, "pip poetry evidence")));
        when(llmClient.chat(anyString(), contains("packages.md"))).thenReturn(
                "{\"cases\":[{\"caseKey\":\"bad-1\",\"query\":\"q1\",\"expectedFileName\":\"packages.md\",\"mustContainAny\":[\"invented\",\"pip\"]},{\"caseKey\":\"bad-2\",\"query\":\"q2\",\"expectedFileName\":\"packages.md\",\"mustContainAny\":[\"poetry\",\"evidence\"]}]}");

        var preview = service().preview(kbId);

        assertThat(preview.isConfirmable()).isFalse();
        assertThat(preview.getDocuments()).singleElement().satisfies(item ->
                assertThat(item.getError()).contains("invented"));
        verify(llmClient, org.mockito.Mockito.times(3)).chat(anyString(), contains("packages.md"));
    }

    @Test
    void previewAcceptsGeneratedPayloadAsAnEquivalentCasesEnvelope() {
        UUID kbId = UUID.randomUUID();
        Document document = document(kbId, "asyncio.md");
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(document));
        when(caseRepository.findByKbIdOrderByCreatedAtAsc(kbId)).thenReturn(List.of());
        when(chunkRepository.countByDocId(document.getId())).thenReturn(1L);
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(document.getId()))
                .thenReturn(List.of(chunk(document, "asyncio 10 seconds evidence")));
        when(llmClient.chat(anyString(), contains("asyncio.md"))).thenReturn(
                "{\"generatedPayload\":[{\"caseKey\":\"async-1\",\"query\":\"q1\",\"expectedFileName\":\"asyncio.md\",\"mustContainAny\":[\"asyncio\",\"evidence\"]},{\"caseKey\":\"async-2\",\"query\":\"q2\",\"expectedFileName\":\"asyncio.md\",\"mustContainAny\":[\"10 seconds\",\"evidence\"]}]}");

        var preview = service().preview(kbId);

        assertThat(preview.isConfirmable()).isTrue();
        assertThat(preview.getDocuments()).singleElement().satisfies(item -> {
            assertThat(item.getError()).isNull();
            assertThat(item.getProposals()).hasSize(2);
        });
    }

    @Test
    void previewAcceptsAnArrayEnvelope() {
        UUID kbId = UUID.randomUUID();
        Document document = document(kbId, "rest.md");
        stubGenerationDocument(kbId, document, "GET POST DELETE evidence");
        when(llmClient.chat(anyString(), contains("rest.md"))).thenReturn(
                "[{\"caseKey\":\"get\",\"query\":\"q1\",\"expectedFileName\":\"rest.md\",\"mustContainAny\":[\"GET\",\"evidence\"]},{\"caseKey\":\"post\",\"query\":\"q2\",\"expectedFileName\":\"rest.md\",\"mustContainAny\":[\"POST\",\"DELETE\"]}]");

        var preview = service().preview(kbId);

        assertThat(preview.isConfirmable()).isTrue();
        assertThat(preview.getDocuments()).singleElement().satisfies(item ->
                assertThat(item.getProposals()).hasSize(2));
    }

    @Test
    void previewKeepsAValidSingleCaseAndRequestsOnlyTheRemainingCase() {
        UUID kbId = UUID.randomUUID();
        Document document = document(kbId, "rest.md");
        stubGenerationDocument(kbId, document, "GET POST DELETE evidence");
        when(llmClient.chat(anyString(), contains("rest.md")))
                .thenReturn("{\"caseKey\":\"get\",\"query\":\"q1\",\"expectedFileName\":\"rest.md\",\"mustContainAny\":[\"GET\",\"evidence\"]}")
                .thenReturn("{\"caseKey\":\"post\",\"query\":\"q2\",\"expectedFileName\":\"rest.md\",\"mustContainAny\":[\"POST\",\"DELETE\"]}");

        var preview = service().preview(kbId);

        assertThat(preview.isConfirmable()).isTrue();
        assertThat(preview.getDocuments()).singleElement().satisfies(item ->
                assertThat(item.getProposals()).extracting(com.dupi.rag.dto.RagEvalGenerationDraft::getCaseKey)
                        .containsExactly("get", "post"));
        verify(llmClient).chat(anyString(), contains("Generate exactly 1 cases"));
    }

    @Test
    void confirmReplacesOnlyInvalidCasesAndRejectsStaleFingerprints() {
        UUID kbId = UUID.randomUUID();
        Document document = document(kbId, "guide.md");
        RagEvalCase valid = singleSource(kbId, "valid", document);
        RagEvalCase stale = singleSource(kbId, "stale", "gone.md");
        List<RagEvalCase> existing = List.of(valid, stale);
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(document));
        when(caseRepository.findByKbIdOrderByCreatedAtAsc(kbId)).thenReturn(existing);
        when(chunkRepository.countByDocId(document.getId())).thenReturn(1L);
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(document.getId()))
                .thenReturn(List.of(chunk(document, "alpha evidence")));
        when(caseRepository.saveAll(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            Iterable<RagEvalCase> items = call.getArgument(0);
            items.forEach(item -> item.setId(UUID.randomUUID()));
            return items;
        });

        var preview = service().preview(kbId);
        var request = new com.dupi.rag.dto.RagEvalGenerationConfirmRequest();
        request.setDocumentFingerprint(preview.getDocumentFingerprint());
        request.setCaseFingerprint(preview.getCaseFingerprint());
        request.setReplaceCaseIds(List.of(stale.getId()));
        request.setGeneratedCases(List.of(com.dupi.rag.dto.RagEvalGenerationDraft.builder()
                .caseKey("new-case").query("What is alpha?").expectedDocumentId(document.getId())
                .expectedFileName("guide.md")
                .mustContainAny(List.of("alpha", "evidence")).build()));

        var confirmed = service().confirm(kbId, request);

        verify(caseRepository).deleteAll(List.of(stale));
        assertThat(confirmed).extracting(com.dupi.rag.dto.RagEvalCaseResponse::getCaseKey)
                .containsExactlyInAnyOrder("valid", "new-case");

        request.setCaseFingerprint("stale");
        assertThatThrownBy(() -> service().confirm(kbId, request))
                .isInstanceOf(com.dupi.rag.exception.RagEvalCaseConflictException.class);
    }

    @Test
    void previewAddGeneratesRequestedCasesOnlyForSelectedCompletedDocuments() {
        UUID kbId = UUID.randomUUID();
        Document selected = document(kbId, "selected.md");
        Document unselected = document(kbId, "unselected.md");
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(selected, unselected));
        when(caseRepository.findByKbIdOrderByCreatedAtAsc(kbId)).thenReturn(List.of());
        when(chunkRepository.countByDocId(selected.getId())).thenReturn(1L);
        when(chunkRepository.countByDocId(unselected.getId())).thenReturn(1L);
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(selected.getId()))
                .thenReturn(List.of(chunk(selected, "alpha beta evidence")));
        when(llmClient.chat(anyString(), contains("selected.md")))
                .thenReturn(jsonCases("selected.md", "alpha", "beta"));
        var request = new com.dupi.rag.dto.RagEvalAddGenerationPreviewRequest();
        request.setDocumentIds(List.of(selected.getId()));
        request.setCasesPerDocument(2);

        var preview = service().previewAdd(kbId, request);

        assertThat(preview.isConfirmable()).isTrue();
        assertThat(preview.getReplacedCases()).isEmpty();
        assertThat(preview.getDocuments()).singleElement().satisfies(item -> {
            assertThat(item.getDocumentId()).isEqualTo(selected.getId());
            assertThat(item.getProposals()).hasSize(2);
        });
        verify(llmClient, never()).chat(anyString(), contains("unselected.md"));
        verify(caseRepository, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirmAddAppendsCasesWithoutDeletingOrUpdatingExistingCases() {
        UUID kbId = UUID.randomUUID();
        Document document = document(kbId, "guide.md");
        RagEvalCase existing = singleSource(kbId, "existing", document);
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(document));
        when(caseRepository.findByKbIdOrderByCreatedAtAsc(kbId)).thenReturn(List.of(existing));
        when(chunkRepository.countByDocId(document.getId())).thenReturn(1L);
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(document.getId()))
                .thenReturn(List.of(chunk(document, "alpha beta evidence")));
        when(llmClient.chat(anyString(), contains("guide.md")))
                .thenReturn(jsonCases("guide.md", "alpha", "beta"));
        when(caseRepository.saveAll(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            Iterable<RagEvalCase> items = call.getArgument(0);
            items.forEach(item -> item.setId(UUID.randomUUID()));
            return items;
        });
        var previewRequest = new com.dupi.rag.dto.RagEvalAddGenerationPreviewRequest();
        previewRequest.setDocumentIds(List.of(document.getId()));
        previewRequest.setCasesPerDocument(2);
        var preview = service().previewAdd(kbId, previewRequest);
        var confirmRequest = new com.dupi.rag.dto.RagEvalAddGenerationConfirmRequest();
        confirmRequest.setDocumentFingerprint(preview.getDocumentFingerprint());
        confirmRequest.setCaseFingerprint(preview.getCaseFingerprint());
        confirmRequest.setDocumentIds(List.of(document.getId()));
        confirmRequest.setCasesPerDocument(2);
        confirmRequest.setGeneratedCases(preview.getDocuments().get(0).getProposals());

        var confirmed = service().confirmAdd(kbId, confirmRequest);

        assertThat(confirmed).extracting(com.dupi.rag.dto.RagEvalCaseResponse::getCaseKey)
                .contains("existing", "alpha-case", "beta-case");
        verify(caseRepository, never()).deleteAll(org.mockito.ArgumentMatchers.any());
        verify(caseRepository).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirmsIndependentCasesForSameNameDocumentsById() {
        UUID kbId = UUID.randomUUID();
        Document first = document(kbId, "guide.md");
        Document second = document(kbId, "guide.md");
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(first, second));
        when(caseRepository.findByKbIdOrderByCreatedAtAsc(kbId)).thenReturn(List.of());
        when(chunkRepository.countByDocId(first.getId())).thenReturn(1L);
        when(chunkRepository.countByDocId(second.getId())).thenReturn(1L);
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(first.getId()))
                .thenReturn(List.of(chunk(first, "alpha evidence")));
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(second.getId()))
                .thenReturn(List.of(chunk(second, "beta evidence")));
        when(llmClient.chat(anyString(), contains("guide.md")))
                .thenReturn(
                        "{\"cases\":[{\"caseKey\":\"alpha-case\",\"query\":\"shared question\",\"expectedFileName\":\"guide.md\",\"mustContainAny\":[\"alpha\",\"evidence\"]}]}",
                        "{\"cases\":[{\"caseKey\":\"beta-case\",\"query\":\"shared question\",\"expectedFileName\":\"guide.md\",\"mustContainAny\":[\"beta\",\"evidence\"]}]}");
        when(caseRepository.saveAll(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            Iterable<RagEvalCase> items = call.getArgument(0);
            items.forEach(item -> item.setId(UUID.randomUUID()));
            return items;
        });
        var previewRequest = new com.dupi.rag.dto.RagEvalAddGenerationPreviewRequest();
        previewRequest.setDocumentIds(List.of(first.getId(), second.getId()));
        previewRequest.setCasesPerDocument(1);
        var preview = service().previewAdd(kbId, previewRequest);
        var confirmRequest = new com.dupi.rag.dto.RagEvalAddGenerationConfirmRequest();
        confirmRequest.setDocumentFingerprint(preview.getDocumentFingerprint());
        confirmRequest.setCaseFingerprint(preview.getCaseFingerprint());
        confirmRequest.setDocumentIds(List.of(first.getId(), second.getId()));
        confirmRequest.setCasesPerDocument(1);
        confirmRequest.setGeneratedCases(preview.getDocuments().stream()
                .flatMap(item -> item.getProposals().stream()).toList());

        var confirmed = service().confirmAdd(kbId, confirmRequest);

        assertThat(confirmed).extracting(com.dupi.rag.dto.RagEvalCaseResponse::getExpectedDocumentId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    void confirmRejectsClientFilenameThatDiffersFromDocumentSnapshot() {
        UUID kbId = UUID.randomUUID();
        Document document = document(kbId, "guide.md");
        stubGenerationDocument(kbId, document, "alpha evidence");
        when(llmClient.chat(anyString(), contains("guide.md"))).thenReturn(jsonCases("guide.md", "alpha"));
        var previewRequest = new com.dupi.rag.dto.RagEvalAddGenerationPreviewRequest();
        previewRequest.setDocumentIds(List.of(document.getId()));
        previewRequest.setCasesPerDocument(1);
        var preview = service().previewAdd(kbId, previewRequest);
        var draft = preview.getDocuments().get(0).getProposals().get(0);
        draft.setExpectedFileName("forged.md");
        var request = new com.dupi.rag.dto.RagEvalAddGenerationConfirmRequest();
        request.setDocumentFingerprint(preview.getDocumentFingerprint());
        request.setCaseFingerprint(preview.getCaseFingerprint());
        request.setDocumentIds(List.of(document.getId()));
        request.setCasesPerDocument(1);
        request.setGeneratedCases(List.of(draft));

        assertThatThrownBy(() -> service().confirmAdd(kbId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filename");
    }

    @Test
    void previewAddRejectsUnavailableDocumentsAndOutOfRangeCounts() {
        UUID kbId = UUID.randomUUID();
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of());
        var request = new com.dupi.rag.dto.RagEvalAddGenerationPreviewRequest();
        request.setDocumentIds(List.of(UUID.randomUUID()));
        request.setCasesPerDocument(2);
        assertThatThrownBy(() -> service().previewAdd(kbId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable");

        request.setCasesPerDocument(6);
        assertThatThrownBy(() -> service().previewAdd(kbId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 5");
    }

    private RagEvalCaseGenerationService service() {
        return new RagEvalCaseGenerationService(knowledgeBaseService, documentRepository, chunkRepository,
                caseRepository, validationService, maintenanceService, llmClient, new ObjectMapper());
    }

    private void stubGenerationDocument(UUID kbId, Document document, String content) {
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(document));
        when(caseRepository.findByKbIdOrderByCreatedAtAsc(kbId)).thenReturn(List.of());
        when(chunkRepository.countByDocId(document.getId())).thenReturn(1L);
        when(chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(document.getId()))
                .thenReturn(List.of(chunk(document, content)));
    }

    private static Document document(UUID kbId, String fileName) {
        return Document.builder().id(UUID.randomUUID()).kbId(kbId).fileName(fileName)
                .objectKey("object").mimeType("text/markdown").status(DocumentStatus.COMPLETED)
                .createdAt(Instant.parse("2026-08-13T00:00:00Z"))
                .updatedAt(Instant.parse("2026-08-13T00:00:00Z")).build();
    }

    private static Chunk chunk(Document document, String content) {
        return Chunk.builder().id(UUID.randomUUID()).kbId(document.getKbId()).docId(document.getId())
                .chunkIndex(0).content(content).tokenCount(2).metadata(java.util.Map.of()).build();
    }

    private static RagEvalCase singleSource(UUID kbId, String key, String file) {
        return baseCase(kbId, key).expectedFileName(file).expectedFileNames(List.of()).build();
    }

    private static RagEvalCase singleSource(UUID kbId, String key, Document document) {
        return baseCase(kbId, key).expectedDocumentId(document.getId())
                .expectedFileName(document.getFileName()).expectedFileNames(List.of()).build();
    }

    private static RagEvalCase multiSource(UUID kbId, String key, String first, String second) {
        return baseCase(kbId, key).expectedFileName(first).expectedFileNames(List.of(second)).build();
    }

    private static RagEvalCase multiSource(UUID kbId, String key, Document first, Document second) {
        return baseCase(kbId, key).expectedDocumentIds(List.of(first.getId(), second.getId()))
                .expectedFileNames(List.of(first.getFileName(), second.getFileName())).build();
    }

    private static RagEvalCase sourceFree(UUID kbId, String key) {
        return baseCase(kbId, key).expectedFileNames(List.of()).build();
    }

    private static RagEvalCase.RagEvalCaseBuilder baseCase(UUID kbId, String key) {
        return RagEvalCase.builder().id(UUID.nameUUIDFromBytes(key.getBytes())).kbId(kbId).caseKey(key)
                .query("question").minHits(1).topK(5).mustContainAny(List.of())
                .createdAt(Instant.parse("2026-08-13T00:00:00Z"))
                .updatedAt(Instant.parse("2026-08-13T00:00:00Z"));
    }

    private static String jsonCases(String file, String... keywords) {
        StringBuilder cases = new StringBuilder();
        for (int i = 0; i < keywords.length; i++) {
            if (i > 0) cases.append(',');
            String keyword = keywords[i];
            cases.append("{\"caseKey\":\"").append(keyword).append("-case\",\"query\":\"What is ")
                    .append(keyword).append("?\",\"expectedFileName\":\"").append(file)
                    .append("\",\"mustContainAny\":[\"").append(keyword).append("\",\"evidence\"]}");
        }
        return "{\"cases\":[" + cases + "]}";
    }
}
