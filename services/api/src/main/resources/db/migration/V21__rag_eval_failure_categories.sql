ALTER TABLE rag_eval_run_results
    ADD COLUMN failure_categories jsonb NOT NULL DEFAULT '[]'::jsonb;
