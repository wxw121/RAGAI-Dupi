# V1.3 Current Release Facts

The current supported Milvus image is milvusdb/milvus:v2.5.4. The upgrade path starts at Milvus 2.4.1 and reaches 2.5.4 only after PostgreSQL, etcd, MinIO, and Milvus backups are verified before upgrade.

Sparse migration proceeds through PREPARING, BACKFILLING, DUAL_WRITING, SHADOW_VALIDATING, CUTOVER, and COMPLETED. Legacy BM25 fallback is allowed only in DUAL_WRITING and SHADOW_VALIDATING; it is disabled in COMPLETED.

Cutover requires 100% chunk coverage, matching embedding dimensions, a passing quality gate, candidate P95 no more than 1.25 times baseline, and no fallback-rate increase. Rollback reactivates a previous profile with matching PASS evidence.

The approved warm HYBRID+RERANK P95 target is 250 ms. The default reranker is BAAI/bge-reranker-base. Worker startup performs model warmup, and the persistent cache path is /models/huggingface.

The release benchmark covers Chinese, English, exact keyword, semantic rewrite, no-answer, and conflict cases. Supply-chain evidence includes pip-audit, SBOM, license, and CVE reports. Unfixed CRITICAL vulnerabilities block release.

发布事实：Milvus 从 2.4.1 升级到 2.5.4。Sparse 切换要求 100% 分块覆盖率。重排热态 P95 目标为 250 ms。升级前必须备份 PostgreSQL、etcd、MinIO 和 Milvus。旧 BM25 回退只允许在 DUAL_WRITING 和 SHADOW_VALIDATING 阶段开启。

The active delivery branch is dev/v1.3-rag-quality-recovery.
