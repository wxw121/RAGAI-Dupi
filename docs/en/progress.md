# Progress Record

<!-- language-switch -->
[中文](../zh-CN/progress.md) | **English**


# 2026-07-15 V1.3 Release hardening

A new 30-item, six-category search list has been added, supporting idempotent synchronization, cold/hot three-mode benchmarks, and fallback/ ranking evidence access control.
- New features such as Rerank startup preheating, desensitization health status, and `hf_model_cache` persistent volume have been added; The default model maintains `BAAI/bge-reranker-base`.
- New Milvus 2.4.1 to 2.5.4 backup/recovery drill scripts and production confirmation, backup first, and checklist strategy tests have been added.
- pip-audit, Syft, Trivy, license deny list and 3 GB Worker image access control script have been added.
The Sparse Migration Web operation panel, Cutover evidence dialog box and browser Gate process have been newly added.
- Has passed the newly added Worker, API DTO, Web component, Pester policy testing and Web build; Production of the same specification drill, 30-case benchmark in real environment, full scan and real browser Gate release environment execution.
