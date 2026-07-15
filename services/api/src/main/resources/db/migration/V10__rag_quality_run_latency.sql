ALTER TABLE rag_eval_run_results
    ADD COLUMN latency_ms BIGINT NOT NULL DEFAULT 0;

ALTER TABLE rag_eval_runs
    ADD COLUMN baseline_run_id UUID REFERENCES rag_eval_runs(id) ON DELETE SET NULL,
    ADD COLUMN policy_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;
