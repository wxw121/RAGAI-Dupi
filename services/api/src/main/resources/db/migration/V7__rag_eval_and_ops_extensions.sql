CREATE TABLE rag_eval_cases (
    id                 UUID PRIMARY KEY,
    kb_id              UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    case_key           VARCHAR(128) NOT NULL,
    query              TEXT NOT NULL,
    min_hits           INTEGER NOT NULL DEFAULT 1,
    top_k              INTEGER NOT NULL DEFAULT 5,
    expected_file_name VARCHAR(512),
    must_contain_any   JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_rag_eval_cases_kb_key UNIQUE (kb_id, case_key)
);

CREATE TABLE rag_eval_runs (
    id           UUID PRIMARY KEY,
    kb_id        UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    use_rerank   BOOLEAN NOT NULL DEFAULT FALSE,
    passed_count INTEGER NOT NULL DEFAULT 0,
    total_count  INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE rag_eval_run_results (
    id                  UUID PRIMARY KEY,
    run_id              UUID NOT NULL REFERENCES rag_eval_runs(id) ON DELETE CASCADE,
    case_id             UUID,
    case_key            VARCHAR(128) NOT NULL,
    query               TEXT NOT NULL,
    passed              BOOLEAN NOT NULL DEFAULT FALSE,
    failure_reasons     JSONB NOT NULL DEFAULT '[]'::jsonb,
    hit_count           INTEGER NOT NULL DEFAULT 0,
    expected_file_name  VARCHAR(512),
    matched_file_name   VARCHAR(512),
    matched_token       VARCHAR(512),
    retrieval_mode      VARCHAR(64),
    fallback_reason     TEXT,
    embedding_model     VARCHAR(255),
    embedding_dimension INTEGER,
    top_k               INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rag_eval_cases_kb_id ON rag_eval_cases(kb_id);
CREATE INDEX idx_rag_eval_runs_kb_id_created_at ON rag_eval_runs(kb_id, created_at DESC);
CREATE INDEX idx_rag_eval_run_results_run_id ON rag_eval_run_results(run_id);
