package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RagEvalCaseValidationService {

    private final DocumentRepository documentRepository;

    public ValidationReport validate(UUID kbId, List<RagEvalCase> cases) {
        Map<UUID, Document> completedDocuments = documentRepository
                .findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED)
                .stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        Map<UUID, CaseValidity> byCaseId = new LinkedHashMap<>();
        List<RagEvalCase> invalidCases = new ArrayList<>();

        for (RagEvalCase evalCase : cases) {
            List<UUID> expectedIds = expectedDocumentIds(evalCase);
            List<String> snapshots = expectedFiles(evalCase);
            List<String> missing = new ArrayList<>();
            if (expectedIds.isEmpty() && !snapshots.isEmpty()) {
                missing.addAll(snapshots);
            } else if (expectedIds.size() != snapshots.size()) {
                missing.addAll(snapshots.isEmpty()
                        ? expectedIds.stream().map(UUID::toString).toList()
                        : snapshots);
            } else {
                for (int index = 0; index < expectedIds.size(); index++) {
                    Document document = completedDocuments.get(expectedIds.get(index));
                    String snapshot = snapshots.get(index);
                    if (document == null || !java.util.Objects.equals(document.getFileName(), snapshot)) {
                        missing.add(snapshot == null || snapshot.isBlank()
                                ? expectedIds.get(index).toString() : snapshot);
                    }
                }
            }
            byCaseId.put(evalCase.getId(), new CaseValidity(missing.isEmpty(), missing));
            if (!missing.isEmpty()) {
                invalidCases.add(evalCase);
            }
        }

        return new ValidationReport(Map.copyOf(byCaseId), List.copyOf(invalidCases), fingerprint(cases));
    }

    public List<String> expectedFiles(RagEvalCase evalCase) {
        List<String> files = new ArrayList<>();
        addFile(files, evalCase.getExpectedFileName());
        if (evalCase.getExpectedFileNames() != null) {
            evalCase.getExpectedFileNames().forEach(value -> addFile(files, value));
        }
        if (!expectedDocumentIds(evalCase).isEmpty()) {
            return List.copyOf(files);
        }
        return files.stream().distinct().toList();
    }

    public List<UUID> expectedDocumentIds(RagEvalCase evalCase) {
        if (evalCase.getExpectedDocumentIds() != null && !evalCase.getExpectedDocumentIds().isEmpty()) {
            return evalCase.getExpectedDocumentIds().stream()
                    .filter(java.util.Objects::nonNull).distinct().toList();
        }
        return evalCase.getExpectedDocumentId() == null
                ? List.of() : List.of(evalCase.getExpectedDocumentId());
    }

    private void addFile(List<String> files, String value) {
        if (value != null && !value.isBlank()) {
            files.add(value.trim());
        }
    }

    private String fingerprint(List<RagEvalCase> cases) {
        String canonical = cases.stream()
                .sorted(Comparator.comparing(item -> item.getId().toString()))
                .map(item -> String.join("|",
                        item.getId().toString(),
                        value(item.getUpdatedAt()),
                        value(item.getCaseKey()),
                        expectedDocumentIds(item).stream().map(UUID::toString)
                                .collect(java.util.stream.Collectors.joining(","))))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to fingerprint RAG evaluation cases", ex);
        }
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record CaseValidity(boolean sourceValid, List<String> missingExpectedFileNames) {
    }

    public record ValidationReport(
            Map<UUID, CaseValidity> byCaseId,
            List<RagEvalCase> invalidCases,
            String caseFingerprint
    ) {
        public boolean hasInvalidCases() {
            return !invalidCases.isEmpty();
        }
    }
}
