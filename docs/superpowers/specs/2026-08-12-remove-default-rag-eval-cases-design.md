# Remove Default RAG Evaluation Cases

## Problem

When a knowledge base has no RAG evaluation cases, listing cases or starting an evaluation automatically persists three built-in cases. Those cases require `sample-knowledge.md` and fixed dupi-RAG keywords even when the knowledge base contains unrelated documents. Recovery archives preserve these cases, so restored knowledge bases can show a misleading `BLOCKED` release gate despite successful retrieval.

## Approved behavior

- An empty evaluation-case set remains empty.
- Listing evaluation cases must not create records.
- Starting an evaluation with no cases produces an empty run whose release rollup is `NO_CASES`; it must not create template cases.
- Users create evaluation cases explicitly against documents that actually belong to their knowledge base.
- Recovery continues restoring legitimate user-authored evaluation cases unchanged.

## Implementation

Replace the coordinator's load-or-seed behavior with a read-only load that retains the existing 100-case validation. Remove the hard-coded `sample-knowledge.md` definitions. Update callers and focused tests to express the new empty-set behavior.

For the affected restored knowledge base `d8ed80c8-dec3-31bd-a0cb-2309c1dcdd0c`, delete only the three rows that exactly match the former built-in templates. Do not alter documents, chunks, prior run evidence, or evaluation cases in other knowledge bases.

## Verification

- A coordinator test first demonstrates that an empty repository returns no cases and performs no save.
- Existing non-empty cases are still returned and the maximum-case guard still works.
- Service tests verify an empty evaluation has zero results and a `NO_CASES` release rollup.
- Run the focused API test suite, rebuild/restart the API container, and query the affected knowledge base to confirm the stale template cases are gone.

## Scope boundaries

This change does not generate evaluation cases from documents, rewrite restored user cases, delete historical evaluation runs, or change the pass/fail semantics of valid cases.
