ALTER TABLE rag_eval_cases
    ADD COLUMN category varchar(32) NOT NULL DEFAULT 'REAL_QUERY',
    ADD COLUMN expected_file_names jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE rag_eval_run_results
    ADD COLUMN category varchar(32) NOT NULL DEFAULT 'REAL_QUERY',
    ADD COLUMN expected_file_names jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN matched_file_names jsonb NOT NULL DEFAULT '[]'::jsonb;

UPDATE rag_eval_cases
SET category = 'HARD_NEGATIVE'
WHERE min_hits = 0
  AND (expected_file_name IS NULL OR btrim(expected_file_name) = '')
  AND (expected_file_names = '[]'::jsonb OR jsonb_array_length(expected_file_names) = 0)
  AND (must_contain_any = '[]'::jsonb OR jsonb_array_length(must_contain_any) = 0);
