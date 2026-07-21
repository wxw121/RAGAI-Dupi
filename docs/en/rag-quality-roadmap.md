# RAG Quality Roadmap

Updated: 2026-07-21

This roadmap follows the V1.6b-V2.4 benchmark, dashboard, and quality-loop work. The next focus is moving from “observe quality problems” to “capture feedback, diagnose root cause, recommend experiments, and release safely.”

## Recommended sequence

| Version | Theme | Goal | Main deliverables | Acceptance signal |
| --- | --- | --- | --- | --- |
| V2.5 | Persistent real-feedback loop | Persist failed, low-confidence, user-negative, and reviewed feedback as candidate eval cases | Feedback queue, review states, sampling rules, case-promotion script, Web feedback list | A failed eval or online feedback item can generate a candidate case without polluting the official benchmark |
| V2.6 | Answer-quality judge layer | Audit groundedness, citation completeness, refusal correctness, and hallucination risk with explainable rules | Deterministic citation verifier, rubric schema, optional LLM judge adapter, reviewer fields | Each case has explainable quality scores and failure reasons instead of a single opaque score |
| V2.7 | Retrieval experiment registry | Manage profile, TopK, mode, rerank, chunk, and index experiments as structured runs | Experiment registry, run lineage, best-candidate recommendation, diff report | The same benchmark can compare multiple experiments and output a recommended config plus blockers |
| V2.8 | Data/index governance automation | Convert quality failures into corpus, chunk, source, embedding, and index actions | Corpus drift scanner, conflict detector, chunk quality report, reindex recommendation | The system can list KBs/docs that need source fixes, rechunking, reindexing, or manual disambiguation |
| V2.9 | Online quality SLO and alerts | Add fallback, no-answer, latency, degraded profile, and regression signals to ops gates | Quality telemetry API, SLO summary, alert rules, dashboard trends | Quality SLOs are visible by KB/profile/tenant and threshold breaches include suggested actions |
| V3.0 | Canary release and rollback | Combine offline eval, online observation, and release readiness into controlled promotion | Canary policy, shadow eval, promote/rollback gate, release report | New profiles or index strategies only become defaults after passing the canary gate |

## Code-improvement track

- Split the growing `RagEvalService` metrics assembly into focused calculators to reduce coupling.
- Document and contract-test the `RagEvalRun.metrics` schema while keeping JSON extensibility.
- Extract reusable Web Quality dashboard card components before adding more quality dimensions.
- Add small fixtures and artifact contracts for benchmark scripts, keeping local verification lightweight.
- Introduce production feedback tables or events gradually, with sampling, desensitization, and candidate status before promotion into the official benchmark.

## Resource-control strategy

- V2.5-V2.8 should default to unit tests, component tests, Pester, TypeScript build, and small fixtures; do not run Docker Compose, browser E2E, or load tests by default.
- V2.9-V3.0 may require real-environment evidence, but should start with runbooks and dry-run scripts before manual scheduling of heavier checks.
- If an issue remains unresolved after five attempts, record it in the implementation doc under Deferred / Known Issues and continue the current safe increment.

## Post-V3.0 deferred themes

- Multimodal OCR and image/table retrieval quality.
- Visual Knowledge Pipeline DSL.
- K8s Helm, production multi-tenant compliance audit, and full disaster-recovery release rehearsal.
- True high-concurrency load/latency testing and long-running cost-optimization experiments.
