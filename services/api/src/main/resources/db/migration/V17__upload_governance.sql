CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE upload_quota_reservations (
    id                UUID PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    user_id           VARCHAR(128) NOT NULL,
    kb_id             UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    doc_id            UUID REFERENCES documents(id) ON DELETE SET NULL,
    attempt_id        UUID,
    attempt_expires_at TIMESTAMPTZ,
    idempotency_key   VARCHAR(256),
    file_fingerprint  VARCHAR(1024) NOT NULL,
    reserved_bytes    BIGINT NOT NULL DEFAULT 0,
    status            VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    release_reason    TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_upload_quota_idempotency
    ON upload_quota_reservations(tenant_id, user_id, kb_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_upload_quota_usage
    ON upload_quota_reservations(tenant_id, user_id, status);

ALTER TABLE documents
    ADD COLUMN quota_reservation_id UUID REFERENCES upload_quota_reservations(id) ON DELETE SET NULL;

WITH legacy_reservations AS (
    INSERT INTO upload_quota_reservations (
        id, tenant_id, user_id, kb_id, doc_id, idempotency_key, file_fingerprint,
        reserved_bytes, status, release_reason, created_at, updated_at
    )
    SELECT
        gen_random_uuid(),
        kb.tenant_id,
        'legacy-migration',
        d.kb_id,
        d.id,
        NULL,
        coalesce(d.file_name, 'unknown') || ':' || coalesce(d.file_size, 0) || ':' ||
            coalesce(d.mime_type, 'application/octet-stream'),
        coalesce(d.file_size, 0),
        'COMMITTED',
        'V1.4.1 legacy backfill',
        NOW(),
        NOW()
    FROM documents d
    JOIN knowledge_bases kb ON kb.id = d.kb_id
    WHERE d.quota_reservation_id IS NULL
    RETURNING id, doc_id
)
UPDATE documents d
SET quota_reservation_id = legacy_reservations.id
FROM legacy_reservations
WHERE d.id = legacy_reservations.doc_id;

CREATE TABLE upload_window_events (
    id                UUID PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    user_id           VARCHAR(128) NOT NULL,
    bytes             BIGINT NOT NULL DEFAULT 0,
    idempotency_key   VARCHAR(256),
    accepted_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_upload_window_usage
    ON upload_window_events(tenant_id, user_id, accepted_at DESC);

ALTER TABLE ingest_jobs
    ADD COLUMN execution_id UUID,
    ADD COLUMN callback_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN claimed_by VARCHAR(128),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN cancel_requested_at TIMESTAMPTZ;

UPDATE ingest_jobs SET execution_id = gen_random_uuid() WHERE execution_id IS NULL;

ALTER TABLE ingest_jobs
    ALTER COLUMN execution_id SET NOT NULL;

CREATE INDEX idx_ingest_jobs_execution
    ON ingest_jobs(id, execution_id);

CREATE TABLE ingest_failure_notifications (
    id                UUID PRIMARY KEY,
    event_key         VARCHAR(256) NOT NULL UNIQUE,
    tenant_id         VARCHAR(64) NOT NULL,
    kb_id             UUID NOT NULL,
    doc_id            UUID NOT NULL,
    job_id            UUID NOT NULL,
    execution_id      UUID NOT NULL,
    status            VARCHAR(32) NOT NULL,
    stage             VARCHAR(32),
    error_message     TEXT,
    delivery_status   VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count     INTEGER NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ingest_failure_notifications_due
    ON ingest_failure_notifications(delivery_status, next_attempt_at, created_at);
