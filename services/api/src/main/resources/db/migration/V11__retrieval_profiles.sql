CREATE TABLE retrieval_profiles (
    id                       UUID PRIMARY KEY,
    kb_id                    UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    name                     VARCHAR(128) NOT NULL,
    version                  INTEGER NOT NULL,
    vector_candidate_count   INTEGER NOT NULL,
    sparse_candidate_count   INTEGER NOT NULL,
    rrf_constant             INTEGER NOT NULL,
    sparse_index_params      JSONB NOT NULL DEFAULT '{}'::jsonb,
    sparse_search_params     JSONB NOT NULL DEFAULT '{}'::jsonb,
    rerank_enabled           BOOLEAN NOT NULL DEFAULT FALSE,
    rerank_candidate_limit   INTEGER NOT NULL,
    final_top_k              INTEGER NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_retrieval_profiles_kb_version UNIQUE (kb_id, version),
    CONSTRAINT uk_retrieval_profiles_id_kb UNIQUE (id, kb_id),
    CONSTRAINT ck_retrieval_profile_candidates CHECK (
        vector_candidate_count > 0 AND sparse_candidate_count > 0
        AND rerank_candidate_limit > 0 AND final_top_k > 0
    ),
    CONSTRAINT ck_retrieval_profile_rrf CHECK (rrf_constant > 0)
);

ALTER TABLE knowledge_bases
    ADD COLUMN active_retrieval_profile_id UUID,
    ADD CONSTRAINT fk_knowledge_base_active_profile
        FOREIGN KEY (active_retrieval_profile_id, id)
        REFERENCES retrieval_profiles(id, kb_id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_retrieval_profiles_kb_id_version
    ON retrieval_profiles(kb_id, version DESC);
