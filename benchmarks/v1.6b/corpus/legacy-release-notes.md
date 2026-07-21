# Superseded Release Notes

This document is retained only for ambiguity and conflicting-source tests. It is not authoritative.

The old environment used Milvus 2.4.1, PostgreSQL 15, Redis 6, and a 400 ms rerank target. It allowed legacy BM25 fallback after migration completion and used the branch `dev/v1.3-rag-quality-recovery`.

The old release checklist ran the benchmark before backup verification and allowed traffic shifting without a promoted baseline. Every statement in this file is superseded by `release-runbook.md`.
