ALTER TABLE knowledge_bases
    ADD COLUMN retrieval_profile VARCHAR(32) NOT NULL DEFAULT 'CLASSIC';

ALTER TABLE rag_eval_runs
    ADD COLUMN profile_set JSONB NOT NULL DEFAULT '["CLASSIC"]'::jsonb;

ALTER TABLE rag_eval_run_results
    ADD COLUMN retrieval_profile VARCHAR(32) NOT NULL DEFAULT 'CLASSIC';
