ALTER TABLE rag_eval_run_results
    ADD COLUMN matched_rank INTEGER,
    ADD COLUMN vector_rank INTEGER,
    ADD COLUMN sparse_rank INTEGER,
    ADD COLUMN fusion_rank INTEGER,
    ADD COLUMN rerank_rank INTEGER;
