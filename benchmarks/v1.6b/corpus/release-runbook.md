# Current Release Runbook

The current quality release is V1.6b and its delivery branch is `dev/v1.6b-rag-eval-cases`. The supported data stack is Milvus 2.5.4, PostgreSQL 16, Redis 7.2, and the MinIO `RELEASE.2025-04-22T22-12-26Z` image.

Release order is fixed: verify backups, apply database migrations, deploy the candidate, run smoke checks, run the 100-case RAG benchmark, approve the quality gate, promote the baseline, and only then shift user traffic. The deployment freeze window is 21:00-23:00 UTC.

The release quality gate requires at least 90% pass rate, no more than two percentage points of pass-rate regression, no new hard-negative failures, and warm HYBRID+RERANK P95 latency no higher than 250 ms. Any unfixed CRITICAL vulnerability blocks release.

Sparse migration moves through PREPARING, BACKFILLING, DUAL_WRITING, SHADOW_VALIDATING, CUTOVER, and COMPLETED. Legacy BM25 fallback is allowed only during DUAL_WRITING and SHADOW_VALIDATING. It is disabled after CUTOVER and in COMPLETED.

Before release approval, operators must archive the benchmark report, SBOM, license report, CVE report, migration evidence, and recovery rehearsal result.
