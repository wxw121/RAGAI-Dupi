CREATE TABLE sparse_migrations (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    kb_id UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    profile_id UUID NOT NULL,
    state VARCHAR(32) NOT NULL,
    source_chunk_count BIGINT NOT NULL DEFAULT 0,
    indexed_chunk_count BIGINT NOT NULL DEFAULT 0,
    expected_dimension INTEGER,
    actual_dimension INTEGER,
    baseline_p95_ms DOUBLE PRECISION,
    candidate_p95_ms DOUBLE PRECISION,
    baseline_fallback_rate DOUBLE PRECISION,
    candidate_fallback_rate DOUBLE PRECISION,
    legacy_bm25_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_sparse_migration_profile FOREIGN KEY (profile_id, kb_id)
        REFERENCES retrieval_profiles(id, kb_id),
    CONSTRAINT uk_sparse_migration_profile UNIQUE (kb_id, profile_id),
    CONSTRAINT ck_sparse_migration_state CHECK (state IN (
        'PREPARING','BACKFILLING','DUAL_WRITING','SHADOW_VALIDATING','CUTOVER','COMPLETED','FAILED'
    ))
);

CREATE INDEX idx_sparse_migrations_kb_created ON sparse_migrations(kb_id, created_at DESC);
