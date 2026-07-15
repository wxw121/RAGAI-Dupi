ALTER TABLE knowledge_bases
    ADD COLUMN lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'READY';

CREATE INDEX idx_knowledge_bases_tenant_lifecycle
    ON knowledge_bases(tenant_id, lifecycle_status, created_at DESC);

CREATE TABLE recovery_archives (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    tenant_id VARCHAR(128) NOT NULL,
    source_kb_id UUID NOT NULL REFERENCES knowledge_bases(id),
    status VARCHAR(32) NOT NULL,
    schema_version INTEGER NOT NULL,
    bucket VARCHAR(255) NOT NULL,
    object_prefix TEXT NOT NULL,
    source_revision TIMESTAMPTZ,
    item_count BIGINT NOT NULL DEFAULT 0,
    total_bytes BIGINT NOT NULL DEFAULT 0,
    manifest_checksum VARCHAR(64),
    error_code VARCHAR(64),
    error_message TEXT,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_recovery_archives_tenant_source
    ON recovery_archives(tenant_id, source_kb_id, created_at DESC);

CREATE UNIQUE INDEX uq_recovery_archives_active_source
    ON recovery_archives(source_kb_id)
    WHERE status IN ('PREPARING', 'CAPTURING', 'VERIFYING');

CREATE TABLE recovery_archive_items (
    id UUID PRIMARY KEY,
    archive_id UUID NOT NULL REFERENCES recovery_archives(id) ON DELETE CASCADE,
    item_key VARCHAR(512) NOT NULL,
    item_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(255),
    object_key TEXT NOT NULL,
    byte_size BIGINT,
    sha256 VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_recovery_archive_item_key UNIQUE (archive_id, item_key)
);

CREATE TABLE recovery_restore_jobs (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    archive_id UUID NOT NULL REFERENCES recovery_archives(id),
    tenant_id VARCHAR(128) NOT NULL,
    target_kb_id UUID UNIQUE REFERENCES knowledge_bases(id),
    status VARCHAR(32) NOT NULL,
    completed_items BIGINT NOT NULL DEFAULT 0,
    total_items BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_message TEXT,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_recovery_restore_jobs_tenant_archive
    ON recovery_restore_jobs(tenant_id, archive_id, created_at DESC);

CREATE UNIQUE INDEX uq_recovery_restore_active_archive
    ON recovery_restore_jobs(archive_id)
    WHERE status IN ('VALIDATING', 'RESTORING_OBJECTS', 'RESTORING_RECORDS', 'RESTORING_VECTORS', 'VERIFYING');

CREATE TABLE recovery_restore_items (
    id UUID PRIMARY KEY,
    restore_job_id UUID NOT NULL REFERENCES recovery_restore_jobs(id) ON DELETE CASCADE,
    archive_item_id UUID NOT NULL REFERENCES recovery_archive_items(id),
    target_id VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_recovery_restore_archive_item UNIQUE (restore_job_id, archive_item_id)
);
