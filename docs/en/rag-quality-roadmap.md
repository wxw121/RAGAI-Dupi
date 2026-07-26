# RAG Quality Roadmap

Updated: 2026-07-26

V2.0 is the current packaged RAG Quality Closure release. It selects the highest-value items from the former V2.5-V3.0 quality roadmap and ships them as an MVP on top of the existing `RagEvalRun.metrics` JSON contract, without adding new database tables or production event streams.

## Current release: V2.0

| Area | V2.0 deliverable | Evidence |
| --- | --- | --- |
| Feedback candidates | Failed or degraded eval results are persisted as immutable feedback candidate snapshots under `realQueryFeedback.candidates` | `id`, `sourceRunId`, `sourceResultId`, `retrievalProfile`, `reviewStatus`, `suggestedAction`, `judgeStatus` |
| Deterministic answer judge | Citation-eligible misses and failed hard-negative/no-answer cases are reported as answer-quality risks | `answerQuality.judgeStatus`, `riskCases`, `hallucinationRiskCount`, `unsupportedAnswerRiskCount` |
| Retrieval experiments | Runs expose a structured matrix of TopK, profile, retrieval mode, case count, evaluation count, and rerank evidence | `experimentMatrix` |
| Data/index governance | Runs summarize expected-source coverage, missing sources, multi-document/ambiguous cases, embedding dimensions, and action status | `dataIndexGovernance` |
| Online quality SLO | Offline run metrics are converted into SLO-style pass/fallback/latency/profile-regression objectives | `onlineSlo` |
| Canary gate | Candidate profiles are promoted only when release metrics, SLOs, answer quality, and actual profile gate statuses are clean | `canaryGate`, including `profileGateStatuses` |
| Release report | Each run emits a compact release recommendation and evidence list | `v2QualityClosure`, `releaseReport` |

## Version convention

- `2.0.0` is the packaged API/Web release metadata.
- V1.6b-V2.4 remain completed local RAG quality milestones that feed V2.0; they are not standalone packages.
- The former V2.5-V3.0 labels are retired as planned package names. Their selected MVP scope is now V2.0.

## Post-V2.0 backlog

| Theme | Why it is deferred |
| --- | --- |
| Dedicated feedback workflow tables | V2.0 stores immutable metrics snapshots; mutable review assignment, bulk promotion, and workflow history require schema design and migration. |
| Production telemetry ingestion and alert routing | V2.0 computes SLO-style summaries from eval runs; always-on tenant/KB/profile telemetry, alert destinations, and escalation policy remain a production ops project. |
| Optional LLM judge | V2.0 uses deterministic, explainable rules; LLM rubric judging needs model/provider controls, review sampling, and cost governance. |
| Full experiment registry | V2.0 exposes an experiment matrix from run evidence; registry lifecycle, ownership, lineage UI, and best-candidate recommendations remain future work. |
| Broader platform releases | Multimodal OCR, visual pipeline DSL, K8s/Helm, multi-tenant compliance audit, high-concurrency load tests, and long-running cost optimization remain outside V2.0. |

## Seal interpretation

After V2.0 passes implementation and verification, the RAG quality closure MVP can be sealed for this release. There is no required follow-up version to satisfy the selected V2.0 scope; future versions should be driven by the Post-V2.0 backlog above, not by unfinished V2.0 items.
