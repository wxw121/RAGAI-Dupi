CREATE TABLE rag_quality_policies (
    id                       UUID PRIMARY KEY,
    kb_id                    UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    minimum_pass_rate        INTEGER NOT NULL DEFAULT 80,
    maximum_pass_rate_drop   INTEGER NOT NULL DEFAULT 5,
    maximum_new_failures     INTEGER NOT NULL DEFAULT 0,
    block_when_unbaselined   BOOLEAN NOT NULL DEFAULT FALSE,
    baseline_run_id          UUID REFERENCES rag_eval_runs(id) ON DELETE SET NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_rag_quality_policies_kb UNIQUE (kb_id),
    CONSTRAINT ck_rag_quality_minimum_pass_rate CHECK (minimum_pass_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_rag_quality_maximum_pass_rate_drop CHECK (maximum_pass_rate_drop BETWEEN 0 AND 100),
    CONSTRAINT ck_rag_quality_maximum_new_failures CHECK (maximum_new_failures >= 0)
);

ALTER TABLE rag_eval_runs
    ADD COLUMN gate_status VARCHAR(32),
    ADD COLUMN metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN profile_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE rag_eval_run_results
    ADD COLUMN case_fingerprint VARCHAR(128),
    ADD COLUMN comparison_status VARCHAR(32);

CREATE INDEX idx_rag_quality_policies_baseline_run_id
    ON rag_quality_policies(baseline_run_id);
