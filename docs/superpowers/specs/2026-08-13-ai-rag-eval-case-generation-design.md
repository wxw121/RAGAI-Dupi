# AI-generated RAG Evaluation Cases

## Problem

RAG evaluation cases are durable quality assertions, but they can outlive the documents they reference. A restored knowledge base can therefore contain valid indexed documents alongside evaluation cases that still expect an absent fixture such as `sample-knowledge.md`. Retrieval may return enough hits while every case fails its source and keyword assertions, making the evaluation workflow appear unusable.

The earlier design in `2026-08-12-remove-default-rag-eval-cases-design.md` stops empty knowledge bases from receiving fixed sample cases. This design adds the missing lifecycle for existing and restored cases: validity detection, safe AI-assisted replacement, preview, and confirmation.

## Approved behavior

- Evaluation cases are validated against the current knowledge base before a run.
- A case is source-invalid when any configured expected filename does not identify a currently `COMPLETED` document in that knowledge base.
- Cases without expected filenames remain valid; keyword-only and hit-count-only assertions are supported.
- Invalid cases are visibly marked with their missing filenames.
- Evaluation cannot start while source-invalid cases exist. The UI explains the blocker instead of producing a misleading failed quality report.
- The user can ask AI to ensure each currently completed document has two valid single-source evaluation cases.
- Generation only fills a document's deficit: zero existing valid single-source cases generates two, one generates one, and two or more generates none.
- Valid multi-document and source-free cases are retained but do not count toward a document's two-case quota.
- Existing valid cases are retained. Only source-invalid cases are candidates for replacement.
- AI output is shown as one preview containing retained, replaced, and newly generated cases. Nothing is persisted until the user confirms.
- Confirmation atomically removes the source-invalid cases shown in the preview and creates the generated cases.
- Generation or confirmation failure leaves all existing cases unchanged.

## User experience

The evaluation-case section adds a **Generate from current knowledge base** action.

Before generation, the case list shows one of two source states:

- **Valid source** for cases whose configured source files are completed documents, including cases with no source assertion.
- **Missing source** with the absent filename or filenames for invalid cases.

When invalid cases exist, **Run evaluation** is disabled. An inline message states how many cases are invalid and directs the user to edit, delete, or regenerate them.

Generation opens a loading state while the server processes completed documents independently. The resulting confirmation view contains:

1. **Retained** — existing valid cases that will not change.
2. **Replaced** — existing invalid cases that will be deleted on confirmation.
3. **Generated** — only the proposals needed to bring each completed document to two valid single-source cases.

Each generated case displays its question, expected filename, keywords, minimum hit count, and TopK. The user can cancel without mutation or confirm the complete preview. After confirmation, the case list refreshes and evaluation becomes available when no invalid cases remain.

If there are no completed documents, generation is unavailable with a direct explanation. Documents already covered by at least two valid single-source cases are shown as covered and do not trigger an AI call. A failure for one uncovered document does not discard successful proposals for other documents; the preview identifies failed documents, and confirmation is disabled until existing cases plus proposals bring every completed document to two valid single-source cases.

## Backend design

### Case validity

A dedicated evaluation-case validation service compares normalized expected filenames with completed documents belonging to the requested knowledge base. It returns validity metadata without changing the stored case schema:

- `sourceValid`
- `missingExpectedFileNames`

The list-cases response includes this metadata. The run endpoint repeats the same server-side validation and rejects invalid input with a structured `409 Conflict`; UI validation is not the security or consistency boundary.

Filename comparison uses the stored document filename after trimming and exact case-sensitive matching, consistent with existing evaluation matching semantics. Duplicate expected filenames are collapsed before validation.

### AI proposal generation

A new generation service loads completed documents and selects a bounded representative context for each document from its indexed chunks. Context selection favors early chunks and distinct headings while enforcing a per-document character budget. Full documents are not sent to the model.

Each document with a positive case deficit is sent to the existing configured chat model in a separate non-streaming request. The prompt requires a strict JSON object containing exactly the requested number of `REAL_QUERY` cases (one or two). Every proposal must include:

- a concise, stable case key;
- a question answerable from the supplied document;
- `expectedFileName` fixed to that document;
- two to five literal evidence keywords present in the supplied context;
- `minHits = 1`;
- `topK = 5`.

The server parses and validates AI output rather than trusting it. It rejects unknown fields, malformed JSON, duplicate case keys, missing questions, incorrect filenames, keywords absent from source context, or a count different from the requested deficit. Case keys are made unique against retained cases and other proposals using a deterministic filename-derived prefix when necessary.

Generation is read-only. The response contains:

- a knowledge-base document fingerprint derived from completed document IDs, filenames, update/status information, and indexed chunk counts;
- retained case summaries;
- invalid cases proposed for replacement;
- generated case drafts grouped by document;
- per-document generation errors.

### Confirmation

Confirmation sends the document fingerprint, the invalid case IDs from the preview, and the generated drafts back to the server. Within one transaction, the server:

1. locks or consistently reloads the knowledge base, completed documents, and affected cases;
2. recomputes the document fingerprint;
3. verifies every deletion target still belongs to the knowledge base and is still source-invalid;
4. revalidates all generated drafts and uniqueness constraints;
5. aborts with `409 Conflict` if documents or cases changed after preview;
6. deletes only the verified invalid cases and inserts all generated cases.

Historical evaluation runs and their result snapshots are not deleted or rewritten.

## API shape

The knowledge-base RAG evaluation API gains three capabilities:

- `GET /api/v1/knowledge-bases/{kbId}/rag-eval/cases` returns existing case fields plus source-validity metadata.
- `POST /api/v1/knowledge-bases/{kbId}/rag-eval/cases/generation-preview` generates and returns a non-persistent preview.
- `POST /api/v1/knowledge-bases/{kbId}/rag-eval/cases/generation-confirm` validates the preview payload and atomically applies it.

The existing run endpoint returns a structured conflict when invalid source assertions remain. Authorization follows the existing RAG case permissions: preview requires read access plus the ability to manage evaluation cases; confirmation requires evaluation-case mutation access.

## Error handling

- Missing or unavailable chat configuration produces a generation error and no mutations.
- Model timeouts and invalid output are reported per document without exposing API keys or raw provider responses.
- No completed documents produces a validation response, not an empty successful preview.
- Document ingestion, deletion, rename, recovery, or case edits between preview and confirmation invalidate the fingerprint and require regeneration.
- Partial database writes are prevented by the confirmation transaction.
- Running an evaluation through a direct API call while invalid cases exist returns the same structured blocker shown by the UI.

## Testing

Backend tests cover:

- valid, source-free, missing, non-completed, duplicate, and cross-knowledge-base source assertions;
- run rejection when invalid sources exist and normal execution when all sources are valid;
- representative context limits and per-document isolation;
- strict parsing and rejection of malformed or ungrounded AI proposals;
- zero, one, or two proposals according to each completed document's valid single-source case deficit;
- preview being read-only;
- confirmation retaining valid cases, deleting only previewed invalid cases, and inserting generated cases atomically;
- stale fingerprint, changed cases, duplicate keys, partial AI failure, and transaction rollback.

Frontend tests cover:

- validity badges and missing-source details;
- disabled evaluation with a clear remediation message;
- generation loading, error, preview, cancellation, and confirmation states;
- retained/replaced/generated group rendering;
- case refresh and run enablement after successful confirmation.

An integration scenario restores a knowledge base whose archived cases reference an absent fixture, verifies that evaluation is blocked before generation, generates two grounded cases per current document, confirms replacement, and completes an evaluation without missing-source failures.

## Scope boundaries

- AI does not silently edit or delete cases.
- Valid user-authored cases are never replaced by this workflow.
- The feature does not generate hard-negative, ambiguous, or multi-document cases in its first version.
- The feature does not automatically regenerate cases after every upload or deletion; it detects drift and asks the user to act.
- The feature does not change retrieval ranking, citation matching, quality thresholds, historical runs, or recovery archive contents.
