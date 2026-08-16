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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RagEvalCaseValidationService {

    private final DocumentRepository documentRepository;

    public ValidationReport validate(UUID kbId, List<RagEvalCase> cases) {
        Set<String> completedFiles = new LinkedHashSet<>(documentRepository
                .findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED)
                .stream()
                .map(Document::getFileName)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList());
        Map<UUID, CaseValidity> byCaseId = new LinkedHashMap<>();
        List<RagEvalCase> invalidCases = new ArrayList<>();

        for (RagEvalCase evalCase : cases) {
            List<String> missing = expectedFiles(evalCase).stream()
                    .filter(fileName -> !completedFiles.contains(fileName))
                    .toList();
            byCaseId.put(evalCase.getId(), new CaseValidity(missing.isEmpty(), missing));
            if (!missing.isEmpty()) {
                invalidCases.add(evalCase);
            }
        }

        return new ValidationReport(Map.copyOf(byCaseId), List.copyOf(invalidCases), fingerprint(cases));
    }

    public List<String> expectedFiles(RagEvalCase evalCase) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        addFile(files, evalCase.getExpectedFileName());
        List<String> additional = evalCase.getExpectedFileNames() == null
                ? List.of()
                : evalCase.getExpectedFileNames();
        additional.forEach(value -> addFile(files, value));
        return List.copyOf(files);
    }

    private void addFile(Set<String> files, String value) {
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
                        String.join(",", expectedFiles(item))))
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
