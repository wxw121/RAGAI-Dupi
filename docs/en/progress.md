# Progress Record

<!-- language-switch -->
[中文](../zh-CN/progress.md) | **English**

## 2026-07-21 V1.9-V2.4 RAG quality loop

- Added six versioned quality-loop maps to evaluation run metrics: releaseReadiness, realQueryFeedback, experimentMatrix, answerQuality, onlineObservability, and dataIndexGovernance.
- Added six Quality dashboard cards for release readiness, real-query feedback candidates, retrieval experiment matrix, answer quality, online observability, and data/index governance.
- The completed design and implementation status is retained in this tracked progress log.
- Deferred resource-heavy post-V2.4 checks: real browser E2E, Docker Compose release rehearsal, true load/latency experiments, and persistent production chat-log feedback queues.

## 2026-07-26 V2.0 RAG Quality Closure packaged release

- Selected the former V2.5-V3.0 follow-up scope into the V2.0 Closure MVP: feedback candidates, deterministic answer judging, experiment matrix, data/index governance, online quality SLOs, canary gates, and release reports.
- Unified the version convention: current packaged API/Web metadata is V2.0 (`2.0.0`); V1.6b-V2.4 are completed local quality milestones and not standalone packages.
- Roadmap doc: `docs/en/rag-quality-roadmap.md`; Chinese roadmap doc: `docs/zh-CN/rag-quality-roadmap.md`.
- Design doc: `docs/en/v2.0-rag-quality-closure-design.md`; implementation doc: `docs/en/v2.0-rag-quality-closure-implementation.md`.
- Kept the implementation inside the existing metrics JSON and Web dashboard; heavier production feedback tables/events remain Post-V2.0.

## 2026-07-20 V1.7/V1.8 RAG quality dashboard and retrieval experiments

- Added category summaries/trends, diagnostic filters by category/status/failure type, and release gate rollups to make recent eval regressions easier to isolate.
- Evaluation runs now support `topKOverride` and `experimentLabel` for consistent case TopK overrides and labeled retrieval experiments.
- Added profile summaries and Profile A/B comparisons showing pass-rate, hit-rate, citation-rate, and latency deltas versus baseline/classic.
- The completed design and implementation status is retained in this tracked progress log.
- Full browser E2E/Compose release rehearsal and true load/latency experiments were deferred to control local resource use.
## 2026-07-20 V1.6b RAG evaluation corpus

- Added persisted evaluation categories: `REAL_QUERY`, `HARD_NEGATIVE`, `MULTI_DOCUMENT`, and `AMBIGUOUS`.
- Added backward-compatible multi-source assertions. A case keeps its primary `expectedFileName` and may require additional `expectedFileNames`; run results retain all expected and matched sources.
- Added Flyway V22, deterministic fingerprints for non-default categories and additional sources, and scenario-specific request validation.
- Added `benchmarks/v1.6b/` with seven realistic/conflicting corpus documents and 100 cases distributed as 40/20/20/20.
- Updated benchmark synchronization and reporting scripts to use V1.6b, reject out-of-manifest cases without deleting them, and include category/failure/source evidence.
- Updated the Web evaluation editor and result table to manage and display categories and multiple sources.
- Full verification: API 488/488, Web 82/82, Pester manifest policy 5/5, manifest validation, PowerShell syntax checks, TypeScript compile, and Vite production build.


## 2026-07-15 V1.3 Release hardening

A new 30-item, six-category search list has been added, supporting idempotent synchronization, cold/hot three-mode benchmarks, and fallback/ ranking evidence access control.
- New features such as Rerank startup preheating, desensitization health status, and `hf_model_cache` persistent volume have been added; The default model maintains `BAAI/bge-reranker-base`.
- New Milvus 2.4.1 to 2.5.4 backup/recovery drill scripts and production confirmation, backup first, and checklist strategy tests have been added.
- pip-audit, Syft, Trivy, license deny list and 3 GB Worker image access control script have been added.
The Sparse Migration Web operation panel, Cutover evidence dialog box and browser Gate process have been newly added.
- Has passed the newly added Worker, API DTO, Web component, Pester policy testing and Web build; Production of the same specification drill, 30-case benchmark in real environment, full scan and real browser Gate release environment execution.
