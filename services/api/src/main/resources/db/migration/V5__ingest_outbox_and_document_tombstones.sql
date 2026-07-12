CREATE TABLE ingest_outbox_events (
    id              UUID PRIMARY KEY,
    job_id          UUID NOT NULL REFERENCES ingest_jobs(id) ON DELETE CASCADE,
    kb_id           UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    doc_id          UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    object_key      VARCHAR(1024) NOT NULL,
    file_name       VARCHAR(512) NOT NULL,
    mime_type       VARCHAR(128) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ingest_outbox_due
    ON ingest_outbox_events(status, next_attempt_at, created_at);

CREATE UNIQUE INDEX uq_ingest_outbox_active_job
    ON ingest_outbox_events(job_id)
    WHERE status IN ('PENDING', 'FAILED');

CREATE TABLE document_tombstones (
    doc_id      UUID PRIMARY KEY,
    kb_id       UUID NOT NULL,
    object_key  VARCHAR(1024),
    file_name   VARCHAR(512),
    reason      VARCHAR(128) NOT NULL DEFAULT 'DOCUMENT_DELETE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_tombstones_kb_created
    ON document_tombstones(kb_id, created_at DESC);
