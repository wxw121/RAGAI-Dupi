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
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(document(kbId, "guide.md")));

        RagEvalCase valid = evalCase(kbId, "valid", "guide.md", List.of("guide.md"));
        RagEvalCase missing = evalCase(kbId, "missing", "gone.md", List.of("gone.md"));
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
        when(documentRepository.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
                .thenReturn(List.of(document(kbId, "guide.md")));
        RagEvalCase first = evalCase(kbId, "a", "guide.md", List.of());
        RagEvalCase second = evalCase(kbId, "b", null, List.of());
        RagEvalCaseValidationService service = new RagEvalCaseValidationService(documentRepository);

        String ordered = service.validate(kbId, List.of(first, second)).caseFingerprint();
        String reversed = service.validate(kbId, List.of(second, first)).caseFingerprint();
        second.setExpectedFileName("guide.md");
        String changed = service.validate(kbId, List.of(first, second)).caseFingerprint();

        assertThat(reversed).isEqualTo(ordered);
        assertThat(changed).isNotEqualTo(ordered);
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
