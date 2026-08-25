CREATE TABLE operation_staging_attempts (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES operation_jobs(id) ON DELETE RESTRICT,
    tenant_id VARCHAR(128) NOT NULL,
    step_key VARCHAR(256) NOT NULL,
    storage_type VARCHAR(32) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    state VARCHAR(32) NOT NULL,
    owner_token UUID NOT NULL,
    owner_epoch BIGINT NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_operation_staging_attempt_owner UNIQUE (job_id, step_key, owner_epoch),
    CONSTRAINT uq_operation_staging_attempt_object UNIQUE (storage_type, object_key),
    CONSTRAINT ck_operation_staging_attempt_state
        CHECK (state IN ('ACTIVE', 'CLEANUP_PENDING', 'CLEANED')),
    CONSTRAINT ck_operation_staging_attempt_storage
        CHECK (storage_type IN ('MINIO', 'RECOVERY'))
);

CREATE INDEX idx_operation_staging_attempt_cleanup
    ON operation_staging_attempts(state, updated_at, id);

CREATE INDEX idx_operation_staging_attempt_job
    ON operation_staging_attempts(job_id, owner_epoch, state);
