package com.dupi.rag.service;

import com.dupi.rag.client.LlmClient;
import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.RagEvalCaseCategory;
import com.dupi.rag.dto.RagEvalCaseResponse;
import com.dupi.rag.dto.RagEvalAddGenerationConfirmRequest;
import com.dupi.rag.dto.RagEvalAddGenerationPreviewRequest;
import com.dupi.rag.dto.RagEvalGenerationDocumentPreview;
import com.dupi.rag.dto.RagEvalGenerationConfirmRequest;
import com.dupi.rag.dto.RagEvalGenerationDraft;
import com.dupi.rag.dto.RagEvalGenerationPreviewResponse;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.RagEvalCaseRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RagEvalCaseGenerationService {

    private static final int CASES_PER_DOCUMENT = 2;
    private static final int MAX_CONTEXT_CHARS = 12_000;
    private static final int MAX_GENERATION_ATTEMPTS = 3;

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final RagEvalCaseRepository caseRepository;
    private final RagEvalCaseValidationService validationService;
    private final KnowledgeBaseMaintenanceService maintenanceService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public RagEvalGenerationPreviewResponse previewAdd(
            UUID kbId,
            RagEvalAddGenerationPreviewRequest request
    ) {
        knowledgeBaseService.findOrThrow(kbId);
        List<Document> documents = documentRepository
                .findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED);
        List<Document> selected = selectedDocuments(documents, request.getDocumentIds(), request.getCasesPerDocument());
        List<RagEvalCase> cases = caseRepository.findByKbIdOrderByCreatedAtAsc(kbId);
        RagEvalCaseValidationService.ValidationReport validation = validationService.validate(kbId, cases);
        Set<String> usedKeys = cases.stream().map(RagEvalCase::getCaseKey)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        Set<String> usedQuestions = cases.stream().map(this::questionIdentity)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        List<RagEvalGenerationDocumentPreview> previews = new ArrayList<>();

        for (Document document : selected) {
            RagEvalGenerationDocumentPreview.RagEvalGenerationDocumentPreviewBuilder builder =
                    RagEvalGenerationDocumentPreview.builder()
                            .documentId(document.getId())
                            .fileName(document.getFileName())
                            .existingSingleSourceCount(existingSingleSourceCount(document.getFileName(), cases, validation))
                            .deficit(request.getCasesPerDocument())
                            .covered(false)
                            .proposals(List.of());
            try {
                List<RagEvalGenerationDraft> proposals = generate(
                        document, representativeContext(document.getId()), request.getCasesPerDocument(), usedKeys);
                validateUniqueQuestions(proposals, usedQuestions);
                builder.proposals(proposals);
            } catch (Exception ex) {
                builder.error("Invalid AI response: " + safeMessage(ex));
            }
            previews.add(builder.build());
        }

        boolean confirmable = previews.stream().allMatch(item -> item.getError() == null
                && item.getProposals().size() == request.getCasesPerDocument());
        return RagEvalGenerationPreviewResponse.builder()
                .documentFingerprint(documentFingerprint(documents))
                .caseFingerprint(validation.caseFingerprint())
                .retainedCases(cases.stream().map(item -> toResponse(item, validation.byCaseId().get(item.getId()))).toList())
                .replacedCases(List.of())
                .documents(previews)
                .confirmable(confirmable)
                .build();
    }

    @Transactional
    public List<RagEvalCaseResponse> confirmAdd(UUID kbId, RagEvalAddGenerationConfirmRequest request) {
        maintenanceService.assertMutationAllowed(kbId);
        knowledgeBaseService.findForUpdateOrThrow(kbId);
        List<Document> documents = documentRepository
                .findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED);
        List<Document> selected = selectedDocuments(documents, request.getDocumentIds(), request.getCasesPerDocument());
        List<RagEvalCase> existing = caseRepository.findByKbIdOrderByCreatedAtAsc(kbId);
        RagEvalCaseValidationService.ValidationReport validation = validationService.validate(kbId, existing);
        if (!constantEquals(request.getDocumentFingerprint(), documentFingerprint(documents))
                || !constantEquals(request.getCaseFingerprint(), validation.caseFingerprint())) {
            throw com.dupi.rag.exception.RagEvalCaseConflictException.stalePreview();
        }

        Set<String> selectedFiles = selected.stream().map(Document::getFileName)
                .collect(java.util.stream.Collectors.toSet());
        List<RagEvalGenerationDraft> drafts = safeList(request.getGeneratedCases());
        if (drafts.size() != selected.size() * request.getCasesPerDocument()) {
            throw new IllegalArgumentException("Generated case count does not match the preview request");
        }
        for (String fileName : selectedFiles) {
            long count = drafts.stream().filter(item -> fileName.equals(item.getExpectedFileName())).count();
            if (count != request.getCasesPerDocument()) {
                throw new IllegalArgumentException("Generated case count does not match document: " + fileName);
            }
        }

        Set<String> usedKeys = existing.stream().map(RagEvalCase::getCaseKey)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        Set<String> usedQuestions = existing.stream().map(this::questionIdentity)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        List<RagEvalCase> generated = new ArrayList<>();
        for (RagEvalGenerationDraft draft : drafts) {
            if (!selectedFiles.contains(draft.getExpectedFileName())) {
                throw new IllegalArgumentException("Generated case references an unselected document");
            }
            validateConfirmDraft(draft, selectedFiles, usedKeys, selected);
            String identity = questionIdentity(draft.getExpectedFileName(), draft.getQuery());
            if (!usedQuestions.add(identity)) {
                throw new IllegalArgumentException("Duplicate evaluation question for document: " + draft.getExpectedFileName());
            }
            usedKeys.add(draft.getCaseKey().trim());
            generated.add(RagEvalCase.builder()
                    .kbId(kbId).caseKey(draft.getCaseKey().trim()).query(draft.getQuery().trim())
                    .minHits(1).topK(5).category(RagEvalCaseCategory.REAL_QUERY)
                    .expectedFileName(draft.getExpectedFileName()).expectedFileNames(List.of())
                    .mustContainAny(draft.getMustContainAny().stream().map(String::trim).distinct().toList())
                    .build());
        }

        List<RagEvalCase> saved = new ArrayList<>();
        caseRepository.saveAll(generated).forEach(saved::add);
        List<RagEvalCase> result = new ArrayList<>(existing);
        result.addAll(saved);
        RagEvalCaseValidationService.ValidationReport finalValidation = validationService.validate(kbId, result);
        return result.stream().map(item -> toResponse(item, finalValidation.byCaseId().get(item.getId()))).toList();
    }

    @Transactional
    public List<RagEvalCaseResponse> confirm(UUID kbId, RagEvalGenerationConfirmRequest request) {
        maintenanceService.assertMutationAllowed(kbId);
        knowledgeBaseService.findForUpdateOrThrow(kbId);
        List<Document> documents = documentRepository
                .findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED);
        List<RagEvalCase> existing = caseRepository.findByKbIdOrderByCreatedAtAsc(kbId);
        RagEvalCaseValidationService.ValidationReport validation = validationService.validate(kbId, existing);
        if (!constantEquals(request.getDocumentFingerprint(), documentFingerprint(documents))
                || !constantEquals(request.getCaseFingerprint(), validation.caseFingerprint())) {
            throw com.dupi.rag.exception.RagEvalCaseConflictException.stalePreview();
        }

        Set<UUID> requestedReplacementIds = new LinkedHashSet<>(safeList(request.getReplaceCaseIds()));
        Set<UUID> currentInvalidIds = validation.invalidCases().stream()
                .map(RagEvalCase::getId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!requestedReplacementIds.equals(currentInvalidIds)) {
            throw com.dupi.rag.exception.RagEvalCaseConflictException.stalePreview();
        }

        Set<String> completedFiles = documents.stream().map(Document::getFileName)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> usedKeys = existing.stream()
                .filter(item -> !requestedReplacementIds.contains(item.getId()))
                .map(RagEvalCase::getCaseKey).collect(java.util.stream.Collectors.toCollection(HashSet::new));
        List<RagEvalCase> generated = new ArrayList<>();
        for (RagEvalGenerationDraft draft : safeList(request.getGeneratedCases())) {
            validateConfirmDraft(draft, completedFiles, usedKeys, documents);
            usedKeys.add(draft.getCaseKey());
            generated.add(RagEvalCase.builder()
                    .kbId(kbId).caseKey(draft.getCaseKey().trim()).query(draft.getQuery().trim())
                    .minHits(1).topK(5).category(RagEvalCaseCategory.REAL_QUERY)
                    .expectedFileName(draft.getExpectedFileName()).expectedFileNames(List.of())
                    .mustContainAny(draft.getMustContainAny().stream().map(String::trim).distinct().toList())
                    .build());
        }

        List<RagEvalCase> retained = existing.stream()
                .filter(item -> !requestedReplacementIds.contains(item.getId())).toList();
        assertFinalCoverage(documents, retained, generated);
        caseRepository.deleteAll(validation.invalidCases());
        List<RagEvalCase> saved = new ArrayList<>();
        caseRepository.saveAll(generated).forEach(saved::add);

        List<RagEvalCase> result = new ArrayList<>(retained);
        result.addAll(saved);
        RagEvalCaseValidationService.ValidationReport finalValidation = validationService.validate(kbId, result);
        return result.stream()
                .map(item -> toResponse(item, finalValidation.byCaseId().get(item.getId())))
                .toList();
    }

    public RagEvalGenerationPreviewResponse preview(UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        List<Document> documents = documentRepository
                .findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED);
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("No completed documents are available for evaluation generation");
        }
        List<RagEvalCase> cases = caseRepository.findByKbIdOrderByCreatedAtAsc(kbId);
        RagEvalCaseValidationService.ValidationReport validation = validationService.validate(kbId, cases);
        Set<String> usedKeys = new HashSet<>();
        cases.stream().map(RagEvalCase::getCaseKey).filter(java.util.Objects::nonNull).forEach(usedKeys::add);
        List<RagEvalGenerationDocumentPreview> documentPreviews = new ArrayList<>();

        for (Document document : documents) {
            int existing = existingSingleSourceCount(document.getFileName(), cases, validation);
            int deficit = Math.max(0, CASES_PER_DOCUMENT - existing);
            RagEvalGenerationDocumentPreview.RagEvalGenerationDocumentPreviewBuilder builder =
                    RagEvalGenerationDocumentPreview.builder()
                            .documentId(document.getId())
                            .fileName(document.getFileName())
                            .existingSingleSourceCount(existing)
                            .deficit(deficit)
                            .covered(deficit == 0)
                            .proposals(List.of());
            if (deficit > 0) {
                try {
                    String context = representativeContext(document.getId());
                    builder.proposals(generate(document, context, deficit, usedKeys));
                } catch (Exception ex) {
                    builder.error("Invalid AI response: " + safeMessage(ex));
                }
            }
            documentPreviews.add(builder.build());
        }

        boolean confirmable = documentPreviews.stream()
                .allMatch(item -> item.getError() == null && item.getProposals().size() == item.getDeficit());
        List<RagEvalCaseResponse> retained = cases.stream()
                .filter(item -> !validation.invalidCases().contains(item))
                .map(item -> toResponse(item, validation.byCaseId().get(item.getId())))
                .toList();
        List<RagEvalCaseResponse> replaced = validation.invalidCases().stream()
                .map(item -> toResponse(item, validation.byCaseId().get(item.getId())))
                .toList();
        return RagEvalGenerationPreviewResponse.builder()
                .documentFingerprint(documentFingerprint(documents))
                .caseFingerprint(validation.caseFingerprint())
                .retainedCases(retained)
                .replacedCases(replaced)
                .documents(documentPreviews)
                .confirmable(confirmable)
                .build();
    }

    private int existingSingleSourceCount(
            String fileName,
            List<RagEvalCase> cases,
            RagEvalCaseValidationService.ValidationReport validation
    ) {
        return (int) cases.stream()
                .filter(item -> validation.byCaseId().get(item.getId()).sourceValid())
                .filter(item -> validationService.expectedFiles(item).equals(List.of(fileName)))
                .count();
    }

    private List<Document> selectedDocuments(
            List<Document> completedDocuments,
            List<UUID> requestedIds,
            Integer casesPerDocument
    ) {
        if (requestedIds == null || requestedIds.isEmpty() || requestedIds.size() > 20) {
            throw new IllegalArgumentException("Select between 1 and 20 completed documents");
        }
        if (casesPerDocument == null || casesPerDocument < 1 || casesPerDocument > 5) {
            throw new IllegalArgumentException("Cases per document must be between 1 and 5");
        }
        Set<UUID> uniqueIds = new LinkedHashSet<>(requestedIds);
        if (uniqueIds.size() != requestedIds.size()) {
            throw new IllegalArgumentException("Selected documents must be unique");
        }
        List<Document> selected = completedDocuments.stream()
                .filter(item -> uniqueIds.contains(item.getId()))
                .toList();
        if (selected.size() != uniqueIds.size()) {
            throw new IllegalArgumentException("Selected document is unavailable or not completed");
        }
        return selected;
    }

    private void validateUniqueQuestions(
            List<RagEvalGenerationDraft> proposals,
            Set<String> usedQuestions
    ) {
        Set<String> next = new HashSet<>(usedQuestions);
        for (RagEvalGenerationDraft proposal : proposals) {
            if (!next.add(questionIdentity(proposal.getExpectedFileName(), proposal.getQuery()))) {
                throw new IllegalArgumentException(
                        "duplicate question for document " + proposal.getExpectedFileName());
            }
        }
        usedQuestions.clear();
        usedQuestions.addAll(next);
    }

    private String questionIdentity(RagEvalCase evalCase) {
        return questionIdentity(
                String.join("\u001f", validationService.expectedFiles(evalCase)),
                evalCase.getQuery());
    }

    private String questionIdentity(String fileName, String query) {
        String normalizedFile = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        String normalizedQuery = query == null ? "" : query.trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        return normalizedFile + "\u001e" + normalizedQuery;
    }

    private String representativeContext(UUID documentId) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Chunk chunk : chunkRepository.findTop20ByDocIdOrderByChunkIndexAsc(documentId)) {
            if (chunk.getContent() != null && !chunk.getContent().isBlank()) {
                unique.add(chunk.getContent().trim());
            }
        }
        StringBuilder context = new StringBuilder();
        for (String value : unique) {
            int remaining = MAX_CONTEXT_CHARS - context.length();
            if (remaining <= 0) break;
            if (context.length() > 0) context.append("\n\n");
            context.append(value, 0, Math.min(value.length(), remaining));
        }
        if (context.isEmpty()) {
            throw new IllegalArgumentException("Document has no indexed content");
        }
        return context.toString();
    }

    private List<RagEvalGenerationDraft> generate(
            Document document,
            String context,
            int deficit,
            Set<String> usedKeys
    ) throws Exception {
        String system = "Generate grounded RAG evaluation cases. Return strict JSON only, without Markdown.";
        Exception lastFailure = null;
        String feedback = "";
        List<RagEvalGenerationDraft> collected = new ArrayList<>();
        Set<String> workingKeys = new HashSet<>(usedKeys);
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            int remaining = deficit - collected.size();
            String user = "Generate exactly " + remaining + " cases for file " + document.getFileName()
                    + ". Each case needs caseKey, query, expectedFileName, and 2-5 mustContainAny literal keywords.\n"
                    + "Every keyword must be copied verbatim from the document context. Do not paraphrase or add symbols.\n"
                    + "Return {\"cases\":[...]}.\nDocument context:\n" + context + feedback;
            try {
                List<RagEvalGenerationDraft> result = generateOnce(
                        document, context, remaining, workingKeys, system, user);
                collected.addAll(result);
                if (collected.size() == deficit) {
                    usedKeys.clear();
                    usedKeys.addAll(workingKeys);
                    return List.copyOf(collected);
                }
                feedback = "\nYou returned only " + result.size() + " valid case(s). Return exactly the remaining "
                        + (deficit - collected.size()) + " new case(s), without repeating earlier cases.";
            } catch (Exception ex) {
                lastFailure = ex;
                feedback = "\nYour previous response was rejected: " + safeMessage(ex)
                        + ". Return a completely corrected JSON response and copy every keyword exactly from context.";
            }
        }
        if (!collected.isEmpty()) {
            throw new IllegalArgumentException("expected " + deficit + " cases but received " + collected.size());
        }
        throw lastFailure;
    }

    private List<RagEvalGenerationDraft> generateOnce(
            Document document,
            String context,
            int deficit,
            Set<String> usedKeys,
            String system,
            String user
    ) throws Exception {
        String raw = llmClient.chat(system, user);
        ObjectMapper strict = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        List<GeneratedCase> cases = parseGeneratedCases(raw, strict);
        if (cases.isEmpty() || cases.size() > deficit) {
            throw new IllegalArgumentException("expected at most " + deficit + " cases but received " + cases.size());
        }
        List<RagEvalGenerationDraft> result = new ArrayList<>();
        for (GeneratedCase generated : cases) {
            validateGenerated(document, context, generated);
            String key = uniqueKey(generated.getCaseKey(), document.getFileName(), usedKeys);
            result.add(RagEvalGenerationDraft.builder()
                    .caseKey(key)
                    .query(generated.getQuery().trim())
                    .expectedFileName(document.getFileName())
                    .mustContainAny(generated.getMustContainAny().stream().map(String::trim).distinct().toList())
                    .category(RagEvalCaseCategory.REAL_QUERY)
                    .minHits(1)
                    .topK(5)
                    .build());
        }
        return List.copyOf(result);
    }

    private List<GeneratedCase> parseGeneratedCases(String raw, ObjectMapper strict) throws Exception {
        JsonNode root = strict.readTree(raw);
        JsonNode caseNodes;
        if (root.isArray()) {
            caseNodes = root;
        } else if (root.isObject() && root.has("cases")) {
            caseNodes = root.get("cases");
        } else if (root.isObject() && root.has("generatedPayload")) {
            caseNodes = root.get("generatedPayload");
        } else if (root.isObject() && root.has("caseKey")) {
            caseNodes = strict.createArrayNode().add(root);
        } else {
            throw new IllegalArgumentException("response must contain cases");
        }
        if (!caseNodes.isArray()) {
            throw new IllegalArgumentException("cases must be an array");
        }
        List<GeneratedCase> cases = new ArrayList<>();
        for (JsonNode node : caseNodes) {
            cases.add(strict.treeToValue(node, GeneratedCase.class));
        }
        return cases;
    }

    private void validateGenerated(Document document, String context, GeneratedCase generated) {
        if (generated.getCaseKey() == null || generated.getCaseKey().isBlank()
                || generated.getQuery() == null || generated.getQuery().isBlank()) {
            throw new IllegalArgumentException("caseKey and query are required");
        }
        if (!document.getFileName().equals(generated.getExpectedFileName())) {
            throw new IllegalArgumentException("wrong expected filename");
        }
        List<String> keywords = generated.getMustContainAny();
        if (keywords == null || keywords.size() < 2 || keywords.size() > 5) {
            throw new IllegalArgumentException("expected 2-5 keywords");
        }
        String lowerContext = context.toLowerCase(Locale.ROOT);
        List<String> ungrounded = keywords.stream()
                .filter(value -> value == null || value.isBlank()
                        || !lowerContext.contains(value.trim().toLowerCase(Locale.ROOT)))
                .map(value -> value == null || value.isBlank() ? "<blank>" : value.trim())
                .distinct().toList();
        if (!ungrounded.isEmpty()) {
            throw new IllegalArgumentException("ungrounded keywords: " + String.join(", ", ungrounded));
        }
    }

    private String uniqueKey(String requested, String fileName, Set<String> usedKeys) {
        String base = slug(requested);
        if (base.isBlank()) base = slug(fileName) + "-case";
        String candidate = base;
        if (usedKeys.contains(candidate)) candidate = slug(fileName) + "-" + base;
        int suffix = 2;
        while (usedKeys.contains(candidate)) candidate = slug(fileName) + "-" + base + "-" + suffix++;
        usedKeys.add(candidate);
        return candidate;
    }

    private String slug(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private String documentFingerprint(List<Document> documents) {
        String canonical = documents.stream()
                .sorted(Comparator.comparing(item -> item.getId().toString()))
                .map(item -> String.join("|", item.getId().toString(), item.getFileName(),
                        item.getStatus().name(), String.valueOf(item.getUpdatedAt()),
                        String.valueOf(chunkRepository.countByDocId(item.getId()))))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to fingerprint documents", ex);
        }
    }

    private void validateConfirmDraft(
            RagEvalGenerationDraft draft,
            Set<String> completedFiles,
            Set<String> usedKeys,
            List<Document> documents
    ) {
        if (draft == null || draft.getCaseKey() == null || draft.getCaseKey().isBlank()
                || draft.getQuery() == null || draft.getQuery().isBlank()) {
            throw new IllegalArgumentException("Generated case key and query are required");
        }
        if (usedKeys.contains(draft.getCaseKey().trim())) {
            throw new IllegalArgumentException("Duplicate evaluation case key: " + draft.getCaseKey());
        }
        if (!completedFiles.contains(draft.getExpectedFileName())) {
            throw new IllegalArgumentException("Generated case references an unavailable document");
        }
        if (draft.getCategory() != RagEvalCaseCategory.REAL_QUERY
                || !Integer.valueOf(1).equals(draft.getMinHits())
                || !Integer.valueOf(5).equals(draft.getTopK())) {
            throw new IllegalArgumentException("Generated case settings must remain REAL_QUERY, minHits 1, TopK 5");
        }
        List<String> keywords = draft.getMustContainAny();
        if (keywords == null || keywords.size() < 2 || keywords.size() > 5) {
            throw new IllegalArgumentException("Generated cases require 2-5 keywords");
        }
        Document document = documents.stream().filter(item -> item.getFileName().equals(draft.getExpectedFileName()))
                .findFirst().orElseThrow();
        String context = representativeContext(document.getId()).toLowerCase(Locale.ROOT);
        if (keywords.stream().anyMatch(keyword -> keyword == null || keyword.isBlank()
                || !context.contains(keyword.trim().toLowerCase(Locale.ROOT)))) {
            throw new IllegalArgumentException("Generated case contains an ungrounded keyword");
        }
    }

    private void assertFinalCoverage(
            List<Document> documents,
            List<RagEvalCase> retained,
            List<RagEvalCase> generated
    ) {
        List<RagEvalCase> all = new ArrayList<>(retained);
        all.addAll(generated);
        for (Document document : documents) {
            long count = all.stream()
                    .filter(item -> validationService.expectedFiles(item).equals(List.of(document.getFileName())))
                    .count();
            if (count < CASES_PER_DOCUMENT) {
                throw new IllegalArgumentException(
                        "Document does not have two valid single-source cases: " + document.getFileName());
            }
        }
    }

    private boolean constantEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private RagEvalCaseResponse toResponse(
            RagEvalCase item,
            RagEvalCaseValidationService.CaseValidity validity
    ) {
        return RagEvalCaseResponse.builder()
                .id(item.getId()).kbId(item.getKbId()).caseKey(item.getCaseKey()).query(item.getQuery())
                .minHits(item.getMinHits()).topK(item.getTopK()).category(item.getCategory())
                .expectedFileName(item.getExpectedFileName()).expectedFileNames(item.getExpectedFileNames())
                .mustContainAny(item.getMustContainAny()).sourceValid(validity.sourceValid())
                .missingExpectedFileNames(validity.missingExpectedFileNames())
                .createdAt(item.getCreatedAt()).updatedAt(item.getUpdatedAt()).build();
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    @Data
    private static class GeneratedPayload {
        @JsonAlias("generatedPayload")
        private List<GeneratedCase> cases;
    }

    @Data
    private static class GeneratedCase {
        private String caseKey;
        private String query;
        private String expectedFileName;
        private List<String> mustContainAny;
    }
}
