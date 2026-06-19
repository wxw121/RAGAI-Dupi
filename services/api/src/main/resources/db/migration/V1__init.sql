CREATE TABLE knowledge_bases (
    id              UUID PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    chunk_size      INTEGER NOT NULL DEFAULT 512,
    chunk_overlap   INTEGER NOT NULL DEFAULT 64,
    top_k           INTEGER NOT NULL DEFAULT 5,
    embedding_model VARCHAR(128) NOT NULL DEFAULT 'text-embedding-3-small',
    embedding_dimension INTEGER NOT NULL DEFAULT 1536,
    chunk_strategy  VARCHAR(32) NOT NULL DEFAULT 'recursive',
    retrieval_mode  VARCHAR(32) NOT NULL DEFAULT 'vector',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE documents (
    id              UUID PRIMARY KEY,
    kb_id           UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    file_name       VARCHAR(512) NOT NULL,
    object_key      VARCHAR(1024) NOT NULL,
    mime_type       VARCHAR(128) NOT NULL,
    file_size       BIGINT NOT NULL DEFAULT 0,
    status          VARCHAR(32) NOT NULL DEFAULT 'pending',
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_documents_kb_id ON documents(kb_id);

CREATE TABLE chunks (
    id              UUID PRIMARY KEY,
    kb_id           UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    doc_id          UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index     INTEGER NOT NULL,
    content         TEXT NOT NULL,
    token_count     INTEGER NOT NULL DEFAULT 0,
    metadata        JSONB,
    milvus_id       VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chunks_kb_doc ON chunks(kb_id, doc_id);

CREATE TABLE ingest_jobs (
    id              UUID PRIMARY KEY,
    kb_id           UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    doc_id          UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    status          VARCHAR(32) NOT NULL DEFAULT 'pending',
    stage           VARCHAR(32) NOT NULL DEFAULT 'queued',
    retry_count     INTEGER NOT NULL DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ingest_jobs_doc ON ingest_jobs(doc_id);
