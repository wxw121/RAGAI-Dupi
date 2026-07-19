ALTER TABLE documents ADD COLUMN index_schema_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE knowledge_bases ADD COLUMN index_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_eval_runs ADD COLUMN index_revision BIGINT;
ALTER TABLE rag_eval_runs ADD COLUMN gate_summary JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE rag_eval_run_results ADD COLUMN hit_passed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rag_eval_run_results ADD COLUMN citation_eligible BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rag_eval_run_results ADD COLUMN citation_passed BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE vector_cleanup_tasks
SET target_type = 'LEGACY_KNOWLEDGE_BASE'
WHERE target_type = 'KNOWLEDGE_BASE';

UPDATE vector_cleanup_tasks
SET target_type = 'LEGACY_DOCUMENT'
WHERE target_type = 'DOCUMENT';
