# To-do List

<!-- language-switch -->
[中文](../zh-CN/todo.md) | **English**


## Before the official release of V1.3

- [x] 30 Items, Six-category RAG Benchmark List and Release of Access Control Automation - 2026-07-15
- [x] Rerank Startup Preheating, persistent Model caching and health status - 2026-07-15
- [x] PostgreSQL, etcd, MinIO, Milvus Backup/Upgrade /Cutover/Rollback Drill Script - 2026-07-15
- [x] CVE/ License/Volume Scan access control for PyTorch, sentence-transformers and Worker images - 2026-07-15
- [x] Sparse Migration Web Operations Page and Protected Cutover - 2026-07-15
- [] Conduct a 30-case benchmark, complete supply chain scan, and upgrade/rollback drill in the production environment of the same specification, and file the desensitized report
- [x] Expand the benchmark from 30 to 100 with real queries, hard negatives, multi-document assertions, and ambiguous questions - 2026-07-20

## Next RAG quality iterations

- [x] V1.7: category trends, drill-down diagnostics, and release quality gates in the dashboard - 2026-07-20
- [x] V1.8: retrieval parameter experiments, profile A/B comparison, and RetrievalService refactoring - 2026-07-20
- [x] V1.9: release readiness, evidence checklist, and blocker rollups - 2026-07-21
- [x] V2.0: real-query feedback candidates generated from failed/degraded eval signals - 2026-07-21
- [x] V2.1: retrieval experiment matrix for TopK, profiles, retrieval modes, and rerank evidence - 2026-07-21
- [x] V2.2: answer-quality proxy metrics for citation coverage, grounded pass rate, and hallucination risk - 2026-07-21
- [x] V2.3: online quality observability summary for fallback, no-answer, latency, and degraded profiles - 2026-07-21
- [x] V2.4: data/index governance summary for expected-source coverage, multi-document, ambiguous, and embedding evidence - 2026-07-21

## V2.0 packaged RAG quality closure

- [x] V2.0: package the selected RAG Quality Closure MVP in API/Web metadata 2.0.0 - 2026-07-26
- [x] V2.0: persist feedback candidates, deterministic answer judge evidence, experiment matrix, data/index governance, online SLOs, canary gates, and release reports inside RagEvalRun.metrics - 2026-07-26

## V2.0 packaged release and quality milestone convention

- [x] API/Web/lockfile metadata is current packaged release 2.0.0.
- [x] V1.6b-V2.4 are local RAG quality milestones, not standalone packages.
- [x] Former V2.5-V3.0 quality scope is selected into the V2.0 Closure MVP; heavier production feedback tables/events are Post-V2.0.
