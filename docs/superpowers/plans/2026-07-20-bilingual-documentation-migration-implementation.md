# Bilingual Documentation Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use $superpower-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking via update_plan.

**Goal:** Split all non-benchmark Markdown documentation into complete English and Simplified Chinese counterparts with top-of-file language switching and backward-compatible legacy paths.

**Architecture:** Treat `README.md` and `docs/en/` as canonical English sources, mirror them under `README.zh-CN.md` and `docs/zh-CN/`, and replace old `docs/*.md` paths with redirect stubs. Preserve code blocks, commands, API identifiers, environment variables, and benchmark fixtures byte-for-byte where they are test inputs.

**Tech Stack:** Markdown, PowerShell repository scans, Git relative links, existing `.baoyu-skills/baoyu-translate/EXTEND.md` glossary.

---

### Task 1: Inventory and freeze the migration manifest

**Files:**
- Create: `scripts/docs/bilingual-manifest.ps1`
- Read: `README.md`, all `docs/**/*.md`, all non-benchmark `*.md`
- Test: `benchmarks/v1.3/corpus/*`

- [ ] **Step 1: Define the manifest inputs and exclusions**

  The script must enumerate Git-tracked `README.md` and Markdown files under `docs/`, exclude every path beginning with `benchmarks/`, and emit one TSV row per source with `source`, `english`, `chinese`, and `legacy` paths. The mapping keeps the relative path unchanged under `docs/en/` and `docs/zh-CN/`.

- [ ] **Step 2: Run the manifest and record the count**

  Run `powershell -NoProfile -File scripts/docs/bilingual-manifest.ps1 -Root .` and expect one deterministic row per included Markdown document and zero benchmark rows.

- [ ] **Step 3: Verify benchmark immutability baseline**

  Run `git -c safe.directory=D:/software/cld_project/dupi-RAG ls-files benchmarks | ForEach-Object { git hash-object $_ }` and save the output outside the repository for comparison after migration; no benchmark path may appear in the staged diff.

### Task 2: Create the canonical directory layout and language-switch convention

**Files:**
- Create: `README.zh-CN.md`
- Create: `docs/en/README.md`
- Create: `docs/zh-CN/README.md`
- Create: `docs/en/.language-switch.md`
- Create: `docs/zh-CN/.language-switch.md`
- Modify: `README.md`

- [ ] **Step 1: Move the English README content to its canonical location**

  Copy the current English README prose to `docs/en/README.md`, preserve commands/tables exactly, and add `[中文](../../README.zh-CN.md) | **English**` immediately after the H1.

- [ ] **Step 2: Make the root README a stable English entry point**

  Keep `README.md` as the GitHub default English entry, add `[中文](README.zh-CN.md) | **English**` after the H1, and update every relative documentation link to point to `docs/en/...`.

- [ ] **Step 3: Create the Chinese README counterpart**

  Translate the complete README into Simplified Chinese while preserving all code blocks, commands, identifiers, environment variable names, API routes, table values, and links. Add `[中文](README.zh-CN.md) | [English](README.md)` after the H1.

- [ ] **Step 4: Add the reusable switch convention note**

  Put the exact link pattern and translation rules in the two `.language-switch.md` files so future contributors use the same structure; these are contributor guidance files and must themselves have English/Chinese counterparts.

### Task 3: Migrate and translate user-facing documentation

**Files:**
- Create mirrored files under `docs/en/` and `docs/zh-CN/` for `docs/architecture.md`, `docs/decisions.md`, `docs/e2e-testing.md`, `docs/progress.md`, `docs/todo.md`, `docs/v1.3-release-runbook.md`, `docs/v1.4-recovery-runbook.md`, `docs/v1.4.1-release-runbook.md`, `docs/v1.4.2-governance-ops-runbook.md`, `docs/v1.5-release-notes.md`, and `docs/v1.5-release-runbook.md`.
- Replace each original `docs/<file>.md` with a compatibility stub linking to `docs/en/<file>.md` and `docs/zh-CN/<file>.md`.

- [ ] **Step 1: Copy English documents without inline Chinese summaries**

  Use the pre-summary English content from the last committed `main` version as the canonical English source. Remove the previous `中文说明` block from canonical English files; English prose must remain English-only.

- [ ] **Step 2: Translate each document completely**

  Translate headings, prose, tables, warnings, and list descriptions into technical Simplified Chinese. Preserve code fences, commands, API paths, environment variables, identifiers, numeric values, and URLs exactly. Use the project glossary for recurring terms such as retrieval profile, ingest, quality gate, and knowledge base.

- [ ] **Step 3: Add switch links and preserve relative assets**

  Add the language switch immediately after each H1. Rewrite internal links in each language to the same-language canonical directory, while keeping links to external URLs unchanged.

- [ ] **Step 4: Write compatibility stubs**

  Each old `docs/<file>.md` contains only a deprecation sentence plus links to the English and Chinese canonical files; it must not retain a second copy of the full body.

### Task 4: Migrate and translate internal design/specification documents

**Files:**
- Create mirrored files under `docs/en/superpowers/specs/` and `docs/zh-CN/superpowers/specs/` for every existing `docs/superpowers/specs/*.md`, including the 2026-07-20 bilingual design.
- Replace each original `docs/superpowers/specs/*.md` with a compatibility stub.

- [ ] **Step 1: Restore the canonical English specs**

  Remove inline Chinese summaries from the English copies and preserve all technical requirements, acceptance criteria, and code snippets exactly.

- [ ] **Step 2: Produce complete Chinese specs**

  Translate every heading, paragraph, table, and requirement while keeping identifiers and literal values unchanged. Keep section order identical so English and Chinese files remain structurally comparable.

- [ ] **Step 3: Add language switches and repair internal links**

  Ensure every spec points to same-language runbooks, plans, and README references where a counterpart exists.

### Task 5: Migrate and translate internal implementation plans

**Files:**
- Create mirrored files under `docs/en/superpowers/plans/` and `docs/zh-CN/superpowers/plans/` for every existing `docs/superpowers/plans/*.md`.
- Replace each original `docs/superpowers/plans/*.md` with a compatibility stub.

- [ ] **Step 1: Copy the English plans as canonical technical text**

  Preserve checkbox states, commands, file paths, test names, and code examples; remove inline Chinese summaries from the canonical English copies.

- [ ] **Step 2: Translate complete plan prose**

  Translate goals, architecture notes, task names, explanations, and verification text into Chinese while preserving checkbox syntax and literal commands. Keep task numbering and file paths identical between languages.

- [ ] **Step 3: Add switches and compatibility stubs**

  Add same-language links and replace legacy plan files with stubs that point to both canonical counterparts.

### Task 6: Normalize links and validate the bilingual tree

**Files:**
- Create: `scripts/docs/validate-bilingual-docs.ps1`
- Modify: all canonical Markdown files and compatibility stubs as required by validation output.

- [ ] **Step 1: Implement structural validation**

  The validator must assert: every included source has exactly one `docs/en` and `docs/zh-CN` counterpart; every canonical file has a language switch; no canonical prose file contains the other language outside code/identifiers; and no benchmark path is staged.

- [ ] **Step 2: Implement link validation**

  Parse Markdown links, resolve repository-relative paths from each file’s directory, ignore `http://`, `https://`, `mailto:`, anchors, and code blocks, and fail on missing local targets.

- [ ] **Step 3: Run the full validation suite**

  Run `powershell -NoProfile -File scripts/docs/validate-bilingual-docs.ps1 -Root .`, `git diff --check`, and `rg -n '^(<<<<<<<|=======|>>>>>>>)' README.md README.zh-CN.md docs`. Expected result: zero validation errors, zero whitespace errors, and zero conflict markers.

- [ ] **Step 4: Verify benchmark immutability**

  Compare the post-migration benchmark hashes against the Task 1 baseline and fail if any benchmark hash differs.

### Task 7: Review, commit, and prepare push

**Files:**
- Review: all staged files

- [ ] **Step 1: Review staged scope**

  Run `git diff --cached --stat`, confirm only README/docs, migration scripts, and translation configuration changed, and confirm no generated artifacts or benchmark files are staged.

- [ ] **Step 2: Run repository checks**

  Run the documentation validator, `git diff --cached --check`, and the repository’s existing documentation/test checks if available. Record exact pass/fail output.

- [ ] **Step 3: Commit with repository convention**

  Use `docs: split documentation into en and zh-CN trees` with a body noting canonical English, complete Chinese counterparts, compatibility stubs, and benchmark preservation.

- [ ] **Step 4: Push only after verification**

  Push the verified commit to the requested remote branch; never force-push and stop if `main` advanced.

## Verification summary

- Manifest count matches the number of canonical English/Chinese pairs.
- Every included Markdown has exactly two canonical language files plus, where applicable, one legacy stub.
- No benchmark file changes.
- All local Markdown links resolve.
- Language-switch links exist and point to the counterpart.
- `git diff --check` and conflict-marker scans pass.

