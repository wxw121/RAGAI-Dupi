# AI-generated RAG Evaluation Cases Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make RAG evaluation usable against the current knowledge base by detecting stale source assertions, blocking misleading runs, and safely filling each completed document's valid single-source case count to two with AI-generated, user-confirmed proposals.

**Architecture:** Add a backend validity service as the single source of truth for list and run behavior, then add a separate generation service that builds bounded per-document contexts, calls the existing non-streaming LLM client, validates strict JSON, and creates a read-only preview. Confirmation revalidates document and case fingerprints inside one transaction before replacing only stale cases. The React UI consumes validity metadata, blocks invalid runs, and presents retained/replaced/generated groups in a dedicated preview dialog.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, Jackson, JUnit 5/Mockito, React 18, TypeScript, Vitest, Docker Compose.

## Global Constraints

- Generate only enough cases to bring each completed document to two valid single-source cases: deficit `2`, `1`, or `0`.
- Valid multi-document and source-free cases are retained but do not count toward a document's quota.
- Never silently edit or delete evaluation cases; preview is read-only and confirmation is explicit.
- Confirmation must be atomic and reject stale document or case fingerprints with HTTP `409`.
- Existing valid cases and all historical run evidence remain unchanged.
- Generate only `REAL_QUERY` cases with `minHits = 1`, `topK = 5`, one expected filename, and two to five literal source keywords.
- Do not add a database migration or a new model provider dependency.
- Preserve unrelated modifications already present in the dirty worktree; stage only task-owned files.

---

## File Structure

### Backend files

- Create `services/api/src/main/java/com/dupi/rag/service/RagEvalCaseValidationService.java` — normalize source assertions, compute validity, coverage, and stable case fingerprints.
- Create `services/api/src/main/java/com/dupi/rag/service/RagEvalCaseGenerationService.java` — select document context, call and validate AI output, create previews, and atomically confirm them.
- Create `services/api/src/main/java/com/dupi/rag/exception/RagEvalCaseConflictException.java` — structured conflict for invalid runs and stale confirmations.
- Create `services/api/src/main/java/com/dupi/rag/dto/RagEvalGenerationDraft.java` — validated generated case payload.
- Create `services/api/src/main/java/com/dupi/rag/dto/RagEvalGenerationDocumentPreview.java` — per-document deficit, proposals, and error.
- Create `services/api/src/main/java/com/dupi/rag/dto/RagEvalGenerationPreviewResponse.java` — preview fingerprints and retained/replaced/generated groups.
- Create `services/api/src/main/java/com/dupi/rag/dto/RagEvalGenerationConfirmRequest.java` — fingerprints, replacement IDs, and generated drafts submitted for confirmation.
- Modify `services/api/src/main/java/com/dupi/rag/dto/RagEvalCaseResponse.java` — expose `sourceValid` and `missingExpectedFileNames`.
- Modify `services/api/src/main/java/com/dupi/rag/service/RagEvalService.java` — decorate listed cases and reject runs with stale sources.
- Modify `services/api/src/main/java/com/dupi/rag/controller/KnowledgeBaseController.java` — expose preview and confirmation endpoints.
- Modify `services/api/src/main/java/com/dupi/rag/exception/GlobalExceptionHandler.java` — map evaluation conflicts to HTTP `409`.
- Modify `services/api/src/main/java/com/dupi/rag/config/ApiKeyAuthFilter.java` — explicitly keep preview and confirmation under `KB_WRITE`.
- Modify `services/api/src/main/java/com/dupi/rag/repository/DocumentRepository.java` — add completed-document lookup.
- Test `services/api/src/test/java/com/dupi/rag/service/RagEvalCaseValidationServiceTest.java`.
- Test `services/api/src/test/java/com/dupi/rag/service/RagEvalCaseGenerationServiceTest.java`.
- Modify `services/api/src/test/java/com/dupi/rag/service/RagEvalServiceTest.java`.
- Modify `services/api/src/test/java/com/dupi/rag/controller/ControllerLayerTest.java`.
- Modify `services/api/src/test/java/com/dupi/rag/config/ConfigAndExceptionTest.java`.

### Frontend files

- Modify `services/web/src/types/index.ts` — add validity and generation API types.
- Modify `services/web/src/api/knowledgeBase.ts` — add preview and confirm calls.
- Modify `services/web/src/api/resources.test.ts` — cover endpoint contracts.
- Create `services/web/src/components/RagEvalGenerationPreview.tsx` — render covered/retained/replaced/generated/error groups and confirmation controls.
- Create `services/web/src/components/RagEvalGenerationPreview.test.tsx` — component state and action coverage.
- Modify `services/web/src/components/RagEvalPanel.tsx` — validity badges, run blocker, generation lifecycle, and post-confirm refresh.
- Modify `services/web/src/components/RagEvalPanel.test.tsx` — integrated panel behavior.

### Documentation and verification

- Modify `README.zh-CN.md` and `README.md` — document source validation and AI generation workflow.
- Modify `docs/zh-CN/e2e-testing.md` and `docs/en/e2e-testing.md` — add the restored-stale-case integration scenario.

---

### Task 1: Source validity and server-side evaluation blocker

**Files:**
- Create: `services/api/src/main/java/com/dupi/rag/service/RagEvalCaseValidationService.java`
- Create: `services/api/src/main/java/com/dupi/rag/exception/RagEvalCaseConflictException.java`
- Modify: `services/api/src/main/java/com/dupi/rag/repository/DocumentRepository.java`
- Modify: `services/api/src/main/java/com/dupi/rag/dto/RagEvalCaseResponse.java`
- Modify: `services/api/src/main/java/com/dupi/rag/service/RagEvalService.java`
- Modify: `services/api/src/main/java/com/dupi/rag/exception/GlobalExceptionHandler.java`
- Test: `services/api/src/test/java/com/dupi/rag/service/RagEvalCaseValidationServiceTest.java`
- Test: `services/api/src/test/java/com/dupi/rag/service/RagEvalServiceTest.java`
- Test: `services/api/src/test/java/com/dupi/rag/config/ConfigAndExceptionTest.java`

**Interfaces:**
- Produces: `RagEvalCaseValidationService.validate(UUID, List<RagEvalCase>) -> ValidationReport`.
- Produces: `ValidationReport.byCaseId()`, `invalidCases()`, `hasInvalidCases()`, and `caseFingerprint()`.
- Produces: `RagEvalCaseConflictException.invalidSources(List<InvalidCase>)` mapped to error code `rag_eval_sources_invalid`.
- Later tasks consume the same validation report for quota calculation and confirmation.

- [ ] **Step 1: Write failing validity-service tests**

Create tests for exact matching against completed documents, source-free validity, non-completed rejection, duplicate assertion collapse, cross-KB rejection, and fingerprint stability:

```java
@Test
void validatesOnlyCompletedDocumentsInTheRequestedKnowledgeBase() {
    UUID kbId = UUID.randomUUID();
    when(documents.findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED))
            .thenReturn(List.of(document(kbId, "guide.md", DocumentStatus.COMPLETED)));

    RagEvalCase valid = evalCase(kbId, "valid", "guide.md", List.of("guide.md"));
    RagEvalCase missing = evalCase(kbId, "missing", "gone.md", List.of());
    RagEvalCase sourceFree = evalCase(kbId, "free", null, List.of());

    var report = service.validate(kbId, List.of(valid, missing, sourceFree));

    assertThat(report.byCaseId().get(valid.getId()).sourceValid()).isTrue();
    assertThat(report.byCaseId().get(missing.getId()).missingExpectedFileNames())
            .containsExactly("gone.md");
    assertThat(report.byCaseId().get(sourceFree.getId()).sourceValid()).isTrue();
    assertThat(report.invalidCases()).extracting(RagEvalCase::getCaseKey)
            .containsExactly("missing");
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
cd services/api
.\mvnw.cmd -Dtest=RagEvalCaseValidationServiceTest test
```

Expected: compilation failure because `RagEvalCaseValidationService` and the repository method do not exist.

- [ ] **Step 3: Implement the validity service and response metadata**

Add the repository method:

```java
List<Document> findByKbIdAndStatusOrderByCreatedAtDesc(UUID kbId, DocumentStatus status);
```

Use immutable result records and a SHA-256 fingerprint over sorted case fields:

```java
public ValidationReport validate(UUID kbId, List<RagEvalCase> cases) {
    Set<String> completed = documentRepository
            .findByKbIdAndStatusOrderByCreatedAtDesc(kbId, DocumentStatus.COMPLETED)
            .stream().map(Document::getFileName).map(String::trim)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Map<UUID, CaseValidity> byId = new LinkedHashMap<>();
    for (RagEvalCase item : cases) {
        List<String> expected = expectedFiles(item);
        List<String> missing = expected.stream().filter(name -> !completed.contains(name)).toList();
        byId.put(item.getId(), new CaseValidity(missing.isEmpty(), missing));
    }
    return new ValidationReport(byId, fingerprint(cases));
}
```

Extend `RagEvalCaseResponse`:

```java
private boolean sourceValid;
private List<String> missingExpectedFileNames;
```

Decorate `listCases` responses using the report. Before either `run(...)` path creates a `RagEvalRun`, load and validate cases and throw `RagEvalCaseConflictException.invalidSources(...)` when invalid.

- [ ] **Step 4: Add service and HTTP conflict regression tests**

Add to `RagEvalServiceTest`:

```java
@Test
void runRejectsInvalidSourcesBeforeCreatingARun() {
    when(caseCoordinator.loadCases(kbId)).thenReturn(List.of(caseEntity(kbId, caseId)));
    when(caseValidation.validate(eq(kbId), any())).thenReturn(invalidReport(caseId, "guide.md"));

    assertThatThrownBy(() -> service().run(kbId, new RagEvalRunRequest()))
            .isInstanceOf(RagEvalCaseConflictException.class)
            .hasMessageContaining("guide.md");
    verify(runRepository, never()).save(any());
}
```

Add exception-handler coverage asserting HTTP `409`, `error=rag_eval_sources_invalid`, stage `rag_eval_cases`, and a suggestion to edit, delete, or regenerate invalid cases.

- [ ] **Step 5: Run focused backend tests and verify GREEN**

Run:

```powershell
cd services/api
.\mvnw.cmd -Dtest=RagEvalCaseValidationServiceTest,RagEvalServiceTest,ConfigAndExceptionTest test
```

Expected: all focused tests pass.

- [ ] **Step 6: Commit only Task 1 files**

```powershell
git add services/api/src/main/java/com/dupi/rag/service/RagEvalCaseValidationService.java services/api/src/main/java/com/dupi/rag/exception/RagEvalCaseConflictException.java services/api/src/main/java/com/dupi/rag/repository/DocumentRepository.java services/api/src/main/java/com/dupi/rag/dto/RagEvalCaseResponse.java services/api/src/main/java/com/dupi/rag/service/RagEvalService.java services/api/src/main/java/com/dupi/rag/exception/GlobalExceptionHandler.java services/api/src/test/java/com/dupi/rag/service/RagEvalCaseValidationServiceTest.java services/api/src/test/java/com/dupi/rag/service/RagEvalServiceTest.java services/api/src/test/java/com/dupi/rag/config/ConfigAndExceptionTest.java
git commit -m "fix(rag): block evaluation with stale sources"
```

---

### Task 2: Grounded per-document AI generation preview

**Files:**
- Create: `services/api/src/main/java/com/dupi/rag/service/RagEvalCaseGenerationService.java`
- Create: `services/api/src/main/java/com/dupi/rag/dto/RagEvalGenerationDraft.java`
- Create: `services/api/src/main/java/com/dupi/rag/dto/RagEvalGenerationDocumentPreview.java`
- Create: `services/api/src/main/java/com/dupi/rag/dto/RagEvalGenerationPreviewResponse.java`
- Test: `services/api/src/test/java/com/dupi/rag/service/RagEvalCaseGenerationServiceTest.java`

**Interfaces:**
- Consumes: `RagEvalCaseValidationService.ValidationReport` from Task 1.
- Consumes: existing `LlmClient.chat(String systemPrompt, String userPrompt)`.
- Produces: `RagEvalCaseGenerationService.preview(UUID) -> RagEvalGenerationPreviewResponse`.
- Produces: `RagEvalGenerationPreviewResponse.documentFingerprint()` and `caseFingerprint()` for Task 3.

- [ ] **Step 1: Write failing quota and context tests**

Cover zero/one/two existing valid single-source cases, multi-document and source-free exclusion from quota, no completed documents, bounded context, and no LLM call for covered documents:

```java
@Test
void previewOnlyFillsEachDocumentsSingleSourceDeficit() {
    completedDocuments("one.md", "two.md", "covered.md");
    existingCases(
            singleSource("one-existing", "one.md"),
            singleSource("covered-a", "covered.md"),
            singleSource("covered-b", "covered.md"),
            multiSource("cross-doc", "one.md", "two.md"),
            sourceFree("generic"));
    when(llm.chat(anyString(), contains("one.md"))).thenReturn(jsonCases("one.md", 1));
    when(llm.chat(anyString(), contains("two.md"))).thenReturn(jsonCases("two.md", 2));

    var preview = service.preview(kbId);

    assertThat(preview.documents()).filteredOn(p -> p.fileName().equals("one.md"))
            .singleElement().extracting(RagEvalGenerationDocumentPreview::generatedCount).isEqualTo(1);
    assertThat(preview.documents()).filteredOn(p -> p.fileName().equals("two.md"))
            .singleElement().extracting(RagEvalGenerationDocumentPreview::generatedCount).isEqualTo(2);
    verify(llm, never()).chat(anyString(), contains("covered.md"));
}
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
cd services/api
.\mvnw.cmd -Dtest=RagEvalCaseGenerationServiceTest test
```

Expected: compilation failure for missing generation DTOs and service.

- [ ] **Step 3: Implement bounded context and document fingerprints**

Use completed documents ordered by creation time and `findTop20ByDocIdOrderByChunkIndexAsc`. Build at most `12_000` characters per document, deduplicating identical chunk content and retaining headings found in metadata. Fingerprint sorted rows using:

```text
documentId | filename | status | updatedAt | chunkCount
```

Do not include raw document content in fingerprints or logs.

- [ ] **Step 4: Implement strict AI request and parser**

Request one or two cases according to the deficit. The response contract is:

```json
{
  "cases": [
    {
      "caseKey": "virtual-env-purpose",
      "query": "Python 虚拟环境解决什么问题？",
      "expectedFileName": "1.python-virtual-env-tutorial.md",
      "mustContainAny": ["venv", "site-packages"]
    }
  ]
}
```

Parse with Jackson into a private strict payload (`FAIL_ON_UNKNOWN_PROPERTIES=true` on an isolated reader). Validate:

```java
if (payload.cases().size() != deficit) reject("expected " + deficit + " cases");
if (!document.getFileName().equals(draft.expectedFileName())) reject("wrong expected filename");
if (draft.mustContainAny().size() < 2 || draft.mustContainAny().size() > 5) reject("expected 2-5 keywords");
if (draft.mustContainAny().stream().anyMatch(token -> !context.contains(token))) reject("ungrounded keyword");
```

Normalize drafts to `category=REAL_QUERY`, `minHits=1`, and `topK=5`. Prefix colliding keys with a deterministic slug derived from the filename and append `-2`, `-3`, etc. only when still necessary.

- [ ] **Step 5: Add malformed-output and partial-failure tests**

Test unknown fields, markdown-wrapped JSON, wrong counts, duplicate keys, absent keywords, wrong filename, provider exception, and a preview where one document succeeds while another returns an error. Assert the preview is read-only with `verify(caseRepository, never()).save(any())` and `never().delete(any())`.

- [ ] **Step 6: Run generation tests and verify GREEN**

```powershell
cd services/api
.\mvnw.cmd -Dtest=RagEvalCaseGenerationServiceTest test
```

Expected: all generation preview tests pass.

- [ ] **Step 7: Commit only Task 2 files**

```powershell
git add services/api/src/main/java/com/dupi/rag/service/RagEvalCaseGenerationService.java services/api/src/main/java/com/dupi/rag/dto/RagEvalGenerationDraft.java services/api/src/main/java/com/dupi/rag/dto/RagEvalGenerationDocumentPreview.java services/api/src/main/java/com/dupi/rag/dto/RagEvalGenerationPreviewResponse.java services/api/src/test/java/com/dupi/rag/service/RagEvalCaseGenerationServiceTest.java
git commit -m "feat(rag): generate grounded evaluation previews"
```

---

### Task 3: Atomic confirmation and API endpoints

**Files:**
- Create: `services/api/src/main/java/com/dupi/rag/dto/RagEvalGenerationConfirmRequest.java`
- Modify: `services/api/src/main/java/com/dupi/rag/service/RagEvalCaseGenerationService.java`
- Modify: `services/api/src/main/java/com/dupi/rag/controller/KnowledgeBaseController.java`
- Modify: `services/api/src/main/java/com/dupi/rag/config/ApiKeyAuthFilter.java`
- Modify: `services/api/src/test/java/com/dupi/rag/service/RagEvalCaseGenerationServiceTest.java`
- Modify: `services/api/src/test/java/com/dupi/rag/controller/ControllerLayerTest.java`
- Modify: `services/api/src/test/java/com/dupi/rag/config/ConfigAndExceptionTest.java`

**Interfaces:**
- Consumes: preview DTOs and fingerprints from Task 2.
- Produces: `RagEvalCaseGenerationService.confirm(UUID, RagEvalGenerationConfirmRequest) -> List<RagEvalCaseResponse>`.
- Produces API endpoints `/rag-eval/cases/generation-preview` and `/rag-eval/cases/generation-confirm`.

- [ ] **Step 1: Write failing confirmation transaction tests**

Cover valid apply, changed document fingerprint, changed case fingerprint, replacement case no longer invalid, cross-KB case ID, invalid draft, duplicate key, and repository exception rollback:

```java
@Test
void confirmRetainsValidCasesAndReplacesOnlyPreviewedInvalidCases() {
    RagEvalGenerationConfirmRequest request = confirmRequest(documentFingerprint, caseFingerprint,
            List.of(staleCaseId), List.of(draft("new-case", "guide.md")));

    List<RagEvalCaseResponse> result = service.confirm(kbId, request);

    verify(caseRepository).deleteAll(argThat(items ->
            StreamSupport.stream(items.spliterator(), false)
                    .map(RagEvalCase::getId).toList().equals(List.of(staleCaseId))));
    verify(caseRepository).saveAll(argThat(items ->
            StreamSupport.stream(items.spliterator(), false).count() == 1));
    assertThat(result).extracting(RagEvalCaseResponse::getCaseKey)
            .contains("valid-existing", "new-case");
}
```

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
cd services/api
.\mvnw.cmd -Dtest=RagEvalCaseGenerationServiceTest test
```

Expected: missing `confirm` method and request DTO.

- [ ] **Step 3: Implement transactional confirmation**

Define the request:

```java
@Data
public class RagEvalGenerationConfirmRequest {
    @NotBlank private String documentFingerprint;
    @NotBlank private String caseFingerprint;
    @Size(max = 100) private List<UUID> replaceCaseIds = List.of();
    @Size(max = 100) @Valid private List<RagEvalGenerationDraft> generatedCases = List.of();
}
```

Implement `@Transactional confirm(...)` in this order:

1. `maintenanceService.assertMutationAllowed(kbId)` and `knowledgeBaseService.findForUpdateOrThrow(kbId)`.
2. Reload completed documents and all cases.
3. Recompute and compare both fingerprints with `MessageDigest.isEqual`.
4. Verify replacement IDs belong to the KB and exactly equal the currently invalid case IDs from the preview request.
5. Revalidate every draft's filename, literal keywords, fixed category/minHits/topK, and unique key.
6. Verify final single-source coverage is at least two cases per completed document.
7. Delete verified stale cases, `saveAll` generated entities, flush, and return a freshly validated list.

Throw `RagEvalCaseConflictException.stalePreview()` for fingerprint or replacement drift. Throw `IllegalArgumentException` for malformed drafts.

- [ ] **Step 4: Add controller endpoints and explicit permission coverage**

Add:

```java
@PostMapping("/{kbId}/rag-eval/cases/generation-preview")
public RagEvalGenerationPreviewResponse previewRagEvalCaseGeneration(@PathVariable UUID kbId) {
    return ragEvalCaseGenerationService.preview(kbId);
}

@PostMapping("/{kbId}/rag-eval/cases/generation-confirm")
public List<RagEvalCaseResponse> confirmRagEvalCaseGeneration(
        @PathVariable UUID kbId,
        @Valid @RequestBody RagEvalGenerationConfirmRequest request) {
    return ragEvalCaseGenerationService.confirm(kbId, request);
}
```

Both are POST routes and must resolve to `KB_WRITE`; add positive operator and negative reader assertions to `ConfigAndExceptionTest` so later route changes cannot accidentally weaken authorization.

- [ ] **Step 5: Run API-focused tests and verify GREEN**

```powershell
cd services/api
.\mvnw.cmd -Dtest=RagEvalCaseGenerationServiceTest,ControllerLayerTest,ConfigAndExceptionTest test
```

Expected: all tests pass, including 409 conflicts and authorization checks.

- [ ] **Step 6: Commit only Task 3 files**

```powershell
git add services/api/src/main/java/com/dupi/rag/dto/RagEvalGenerationConfirmRequest.java services/api/src/main/java/com/dupi/rag/service/RagEvalCaseGenerationService.java services/api/src/main/java/com/dupi/rag/controller/KnowledgeBaseController.java services/api/src/main/java/com/dupi/rag/config/ApiKeyAuthFilter.java services/api/src/test/java/com/dupi/rag/service/RagEvalCaseGenerationServiceTest.java services/api/src/test/java/com/dupi/rag/controller/ControllerLayerTest.java services/api/src/test/java/com/dupi/rag/config/ConfigAndExceptionTest.java
git commit -m "feat(rag): confirm generated evaluation cases"
```

---

### Task 4: Frontend API contracts and generation preview component

**Files:**
- Modify: `services/web/src/types/index.ts`
- Modify: `services/web/src/api/knowledgeBase.ts`
- Modify: `services/web/src/api/resources.test.ts`
- Create: `services/web/src/components/RagEvalGenerationPreview.tsx`
- Create: `services/web/src/components/RagEvalGenerationPreview.test.tsx`

**Interfaces:**
- Consumes backend JSON contracts from Tasks 1-3.
- Produces `previewRagEvalCaseGeneration(kbId)` and `confirmRagEvalCaseGeneration(kbId, request)`.
- Produces `<RagEvalGenerationPreview preview confirming onCancel onConfirm />` for Task 5.

- [ ] **Step 1: Write failing API contract tests**

Add assertions that preview posts without a body and confirmation posts both fingerprints, replacement IDs, and drafts:

```ts
await previewRagEvalCaseGeneration('kb1')
expect(fetch).toHaveBeenLastCalledWith(
  '/api/v1/knowledge-bases/kb1/rag-eval/cases/generation-preview',
  expect.objectContaining({ method: 'POST' }),
)

await confirmRagEvalCaseGeneration('kb1', confirmRequest)
expect(JSON.parse(fetchOptions().body as string)).toEqual(confirmRequest)
```

- [ ] **Step 2: Run frontend API tests and verify RED**

```powershell
cd services/web
npm test -- --run src/api/resources.test.ts
```

Expected: missing exports and types.

- [ ] **Step 3: Add exact TypeScript contracts and API functions**

Add:

```ts
export interface RagEvalGenerationDraft extends RagEvalCaseRequest {
  expectedFileName: string
  category: 'REAL_QUERY'
  minHits: 1
  topK: 5
}

export interface RagEvalGenerationDocumentPreview {
  documentId: string
  fileName: string
  existingSingleSourceCount: number
  deficit: number
  covered: boolean
  proposals: RagEvalGenerationDraft[]
  error?: string | null
}

export interface RagEvalGenerationPreview {
  documentFingerprint: string
  caseFingerprint: string
  retainedCases: RagEvalCase[]
  replacedCases: RagEvalCase[]
  documents: RagEvalGenerationDocumentPreview[]
  confirmable: boolean
}
```

Extend `RagEvalCase` with `sourceValid: boolean` and `missingExpectedFileNames: string[]`. Add API functions using the existing `apiPost` helper.

- [ ] **Step 4: Write failing preview component tests**

Test headings and counts for retained/replaced/generated, a covered document, a failed document, disabled confirm when `confirmable=false`, cancel behavior, and the confirming spinner.

- [ ] **Step 5: Implement the focused preview component**

Render a modal-style bordered panel using existing `Button`, `Badge`, and typography primitives. Do not allow inline editing in V1; cancel returns to the case list and confirm applies the entire validated preview. Include filename, question, keywords, minimum hits, and TopK for every proposal.

- [ ] **Step 6: Run focused frontend tests and verify GREEN**

```powershell
cd services/web
npm test -- --run src/api/resources.test.ts src/components/RagEvalGenerationPreview.test.tsx
```

Expected: all focused tests pass.

- [ ] **Step 7: Commit only Task 4 files**

```powershell
git add services/web/src/types/index.ts services/web/src/api/knowledgeBase.ts services/web/src/api/resources.test.ts services/web/src/components/RagEvalGenerationPreview.tsx services/web/src/components/RagEvalGenerationPreview.test.tsx
git commit -m "feat(rag-ui): add evaluation generation preview"
```

---

### Task 5: Evaluation panel lifecycle and invalid-source UX

**Files:**
- Modify: `services/web/src/components/RagEvalPanel.tsx`
- Modify: `services/web/src/components/RagEvalPanel.test.tsx`

**Interfaces:**
- Consumes API functions and preview component from Task 4.
- Produces the complete user workflow: detect blocker, generate, preview, cancel/confirm, refresh, and run.

- [ ] **Step 1: Write failing panel integration tests**

Cover:

```ts
it('blocks evaluation and explains stale sources', async () => {
  api.listRagEvalCases.mockResolvedValue([
    { ...caseDef, sourceValid: false, missingExpectedFileNames: ['sample-knowledge.md'] },
  ])
  // render and settle
  expect(button('运行评估')).toBeDisabled()
  expect(container.textContent).toContain('1 条评估用例引用了不存在或未完成的文档')
  expect(container.textContent).toContain('sample-knowledge.md')
})

it('previews and confirms generated replacements then enables evaluation', async () => {
  api.previewRagEvalCaseGeneration.mockResolvedValue(preview)
  api.confirmRagEvalCaseGeneration.mockResolvedValue(validGeneratedCases)
  // click generate, assert preview, click confirm
  expect(api.confirmRagEvalCaseGeneration).toHaveBeenCalledWith('kb-1', {
    documentFingerprint: preview.documentFingerprint,
    caseFingerprint: preview.caseFingerprint,
    replaceCaseIds: preview.replacedCases.map((item) => item.id),
    generatedCases: preview.documents.flatMap((item) => item.proposals),
  })
  expect(button('运行评估')).not.toBeDisabled()
})
```

- [ ] **Step 2: Run panel tests and verify RED**

```powershell
cd services/web
npm test -- --run src/components/RagEvalPanel.test.tsx
```

Expected: no generation action, invalid badge, or run blocker.

- [ ] **Step 3: Implement validity display and run guard**

Compute:

```ts
const invalidCases = useMemo(() => cases.filter((item) => item.sourceValid === false), [cases])
const canRun = !loading && !running && cases.length > 0 && invalidCases.length === 0
```

Disable the button with `disabled={!canRun}`. Show a red `Missing source` badge and missing filenames in each invalid case row. Add an inline blocker above the list with edit/delete/regenerate remediation. Keep the backend conflict handling because UI state can become stale.

- [ ] **Step 4: Implement generation and confirmation state**

Add `generating`, `confirming`, and `generationPreview` state. The generate action calls preview, surfaces per-document errors in the preview component, and never mutates `cases`. Confirmation constructs its request from the preview, replaces `cases` with the server response, clears preview, refreshes runs only if needed, and shows `评估用例已按当前知识库更新`.

If the preview is stale (`409`), close it, reload cases, and show `知识库或评估用例已发生变化，请重新生成预览`.

- [ ] **Step 5: Run panel and API/component tests and verify GREEN**

```powershell
cd services/web
npm test -- --run src/components/RagEvalPanel.test.tsx src/components/RagEvalGenerationPreview.test.tsx src/api/resources.test.ts
```

Expected: all tests pass.

- [ ] **Step 6: Run the complete Web test suite and production build**

```powershell
cd services/web
npm test
npm run build
```

Expected: all tests pass and Vite production build succeeds; the existing bundle-size warning is non-blocking unless it becomes an error.

- [ ] **Step 7: Commit only Task 5 files**

```powershell
git add services/web/src/components/RagEvalPanel.tsx services/web/src/components/RagEvalPanel.test.tsx
git commit -m "feat(rag-ui): guide stale evaluation replacement"
```

---

### Task 6: Full regression, documentation, and deployed verification

**Files:**
- Modify: `README.zh-CN.md`
- Modify: `README.md`
- Modify: `docs/zh-CN/e2e-testing.md`
- Modify: `docs/en/e2e-testing.md`
- Test: all task-owned backend and frontend files.

**Interfaces:**
- Consumes the completed feature from Tasks 1-5.
- Produces documented operator behavior and evidence that the deployed workflow works against restored stale cases.

- [ ] **Step 1: Update user and E2E documentation**

Document:

- evaluation is blocked when expected filenames are absent or not `COMPLETED`;
- **Generate from current knowledge base** fills each completed document to two valid single-source cases;
- generation is preview-only until confirmation;
- valid cases and historical runs are preserved;
- missing AI configuration fails without changing cases.

The E2E scenario must use a temporary knowledge base or a disposable restored copy, never delete the user's production cases as test cleanup.

- [ ] **Step 2: Run the complete API test suite**

```powershell
cd services/api
.\mvnw.cmd test
```

Expected: Maven exits `0` with all tests passing.

- [ ] **Step 3: Run the complete Web test suite and build again**

```powershell
cd services/web
npm test
npm run build
```

Expected: Vitest and TypeScript/Vite build exit `0`.

- [ ] **Step 4: Rebuild and restart API/Web containers**

From the repository root:

```powershell
docker compose -f deploy/docker-compose.yml up -d --build api web
```

Expected: both services are recreated and remain `Up`.

- [ ] **Step 5: Verify deployed health and workflow**

Check:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
docker compose -f deploy/docker-compose.yml ps api web
```

Expected: health status `UP`; API and Web containers are running.

Using a disposable restored KB whose cases reference `sample-knowledge.md` while current documents differ:

1. List cases and confirm `sourceValid=false` plus the missing filename.
2. Attempt a run and confirm HTTP `409 rag_eval_sources_invalid` with no new run row.
3. Request preview and confirm valid documents with 0/1/2 existing cases receive deficits 2/1/0.
4. Confirm the preview and verify only stale case IDs are gone.
5. List cases and confirm every completed document has at least two valid single-source cases.
6. Run evaluation and confirm there are no `MISSING_EXPECTED_FILE` failures. Other grounded retrieval failures, if any, remain legitimate evaluation results rather than workflow defects.

- [ ] **Step 6: Inspect task diff and ensure no unrelated files are staged**

```powershell
git diff --check
git status --short
git diff --cached --name-only
```

Expected: no whitespace errors; staged paths are only documentation or task-owned files. Do not clean, reset, or stage unrelated dirty-worktree changes.

- [ ] **Step 7: Commit documentation**

```powershell
git add README.zh-CN.md README.md docs/zh-CN/e2e-testing.md docs/en/e2e-testing.md
git commit -m "docs(rag): document AI evaluation generation"
```

- [ ] **Step 8: Record final verification evidence**

In the implementation handoff, report exact commands and counts for API tests, Web tests, build, container health, stale-run rejection, preview deficits, confirmation mutation counts, and the post-confirm evaluation result. Do not claim the feature complete if any required check failed.

---

## Plan Self-review

- Spec coverage: validity detection, UI badges, run blocking, per-document AI generation, deficit behavior, preview-only generation, explicit atomic confirmation, dual fingerprints, partial generation failure, permissions, historical-run preservation, and deployed verification are each mapped to tasks.
- Completeness scan: every step names its implementation, test, command, and expected result; no deferred work remains.
- Type consistency: backend `documentFingerprint`, `caseFingerprint`, `replaceCaseIds`, `generatedCases`, and frontend request fields use the same names throughout Tasks 2-5.
- Scope check: no database migration, ranking change, recovery rewrite, automatic background regeneration, or new evaluation category is included.
