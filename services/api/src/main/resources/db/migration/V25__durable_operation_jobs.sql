CREATE TABLE operation_jobs (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    tenant_id VARCHAR(128) NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    input JSONB NOT NULL DEFAULT '{}'::jsonb,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_operation_job_idempotency UNIQUE (tenant_id, operation_type, idempotency_key)
);

CREATE TABLE operation_steps (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES operation_jobs(id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL,
    step_key VARCHAR(256) NOT NULL,
    step_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    resource_ref TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_operation_step_key UNIQUE (job_id, step_key)
);

CREATE INDEX idx_operation_jobs_status_next_attempt_created
    ON operation_jobs(status, next_attempt_at, created_at);

CREATE INDEX idx_operation_steps_job_sequence
    ON operation_steps(job_id, sequence_number);
