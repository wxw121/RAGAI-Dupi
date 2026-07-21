# Retrieval Operations

Vector mode uses dense embeddings only. Hybrid mode combines vector and BM25 sparse results with weighted reciprocal-rank fusion. The default RRF constant is 60, the vector weight is 1.0, and the sparse weight is 0.8.

When reranking is enabled, the service reranks up to 20 candidates and returns the requested topK, normally 5. The warm HYBRID+RERANK P95 target is 250 ms.

The Classic profile reads the legacy chunk index. Parent-Child retrieves child chunks and expands parent context. QA-assisted retrieves generated question-and-answer records with source provenance. Combined uses both Parent-Child and QA-assisted candidates.

Query normalization preserves exact identifiers such as `DUAL_WRITING`, `/models/huggingface`, image tags, and version numbers. Chinese and English terms can participate in the same hybrid query.

Transient embedding-provider failures may use local text fallback for availability, but fallback usage is recorded in diagnostics and quality metrics. A successful quality run must not hide increased fallback rate.
