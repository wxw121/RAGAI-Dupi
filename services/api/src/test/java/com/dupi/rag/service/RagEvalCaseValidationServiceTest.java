package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagEvalCaseValidationServiceTest {

    @Mock
    DocumentRepository documentRepository;

    @Test
    void marksOnlyMissingCompletedSourcesInvalidAndCollapsesDuplicateAssertions() {
        UUID kbId = UUID.randomUUID();
        Document guide = document(kbId, "guide.md");
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(guide));

        RagEvalCase valid = evalCase(kbId, "valid", "guide.md", List.of("guide.md"));
        valid.setExpectedDocumentId(guide.getId());
        valid.setExpectedFileNames(List.of());
        RagEvalCase missing = evalCase(kbId, "missing", "gone.md", List.of("gone.md"));
        missing.setExpectedDocumentId(UUID.randomUUID());
        missing.setExpectedFileNames(List.of());
        RagEvalCase sourceFree = evalCase(kbId, "free", null, List.of());

        var report = new RagEvalCaseValidationService(documentRepository)
                .validate(kbId, List.of(valid, missing, sourceFree));

        assertThat(report.byCaseId().get(valid.getId()).sourceValid()).isTrue();
        assertThat(report.byCaseId().get(missing.getId()).missingExpectedFileNames())
                .containsExactly("gone.md");
        assertThat(report.byCaseId().get(sourceFree.getId()).sourceValid()).isTrue();
        assertThat(report.invalidCases()).extracting(RagEvalCase::getCaseKey)
                .containsExactly("missing");
    }

    @Test
    void caseFingerprintIsStableAcrossInputOrderAndChangesWithAssertions() {
        UUID kbId = UUID.randomUUID();
        Document guide = document(kbId, "guide.md");
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(guide));
        RagEvalCase first = evalCase(kbId, "a", "guide.md", List.of());
        first.setExpectedDocumentId(guide.getId());
        RagEvalCase second = evalCase(kbId, "b", null, List.of());
        RagEvalCaseValidationService service = new RagEvalCaseValidationService(documentRepository);

        String ordered = service.validate(kbId, List.of(first, second)).caseFingerprint();
        String reversed = service.validate(kbId, List.of(second, first)).caseFingerprint();
        second.setExpectedDocumentId(guide.getId());
        second.setExpectedFileName("guide.md");
        String changed = service.validate(kbId, List.of(first, second)).caseFingerprint();

        assertThat(reversed).isEqualTo(ordered);
        assertThat(changed).isNotEqualTo(ordered);
    }

    @Test
    void validatesSourcesByDocumentIdAndKeepsAmbiguousLegacyRowsVisibleAsInvalid() {
        UUID kbId = UUID.randomUUID();
        Document first = document(kbId, "guide.md");
        Document second = document(kbId, "guide.md");
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(first, second));
        RagEvalCase firstCase = evalCase(kbId, "first", "guide.md", List.of());
        firstCase.setExpectedDocumentId(first.getId());
        RagEvalCase secondCase = evalCase(kbId, "second", "guide.md", List.of());
        secondCase.setExpectedDocumentId(second.getId());
        RagEvalCase ambiguousLegacy = evalCase(kbId, "legacy", "guide.md", List.of());

        var report = new RagEvalCaseValidationService(documentRepository)
                .validate(kbId, List.of(firstCase, secondCase, ambiguousLegacy));

        assertThat(report.byCaseId().get(firstCase.getId()).sourceValid()).isTrue();
        assertThat(report.byCaseId().get(secondCase.getId()).sourceValid()).isTrue();
        assertThat(report.byCaseId().get(ambiguousLegacy.getId()).sourceValid()).isFalse();
        assertThat(report.invalidCases()).containsExactly(ambiguousLegacy);
    }

    private static Document document(UUID kbId, String fileName) {
        return Document.builder()
                .id(UUID.randomUUID()).kbId(kbId).fileName(fileName)
                .objectKey("object").mimeType("text/markdown")
                .status(DocumentStatus.COMPLETED)
                .createdAt(Instant.parse("2026-08-13T00:00:00Z"))
                .updatedAt(Instant.parse("2026-08-13T00:00:00Z"))
                .build();
    }

    private static RagEvalCase evalCase(UUID kbId, String key, String expectedFile, List<String> additional) {
        return RagEvalCase.builder()
                .id(UUID.nameUUIDFromBytes(key.getBytes()))
                .kbId(kbId).caseKey(key).query("question")
                .expectedFileName(expectedFile).expectedFileNames(additional)
                .mustContainAny(List.of()).minHits(1).topK(5)
                .createdAt(Instant.parse("2026-08-13T00:00:00Z"))
                .updatedAt(Instant.parse("2026-08-13T00:00:00Z"))
                .build();
    }
}
