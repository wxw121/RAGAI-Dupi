CREATE TABLE vector_cleanup_tasks (
    id              UUID PRIMARY KEY,
    target_type     VARCHAR(32) NOT NULL,
    target_id       UUID NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vector_cleanup_tasks_due
    ON vector_cleanup_tasks(status, next_attempt_at, created_at);

CREATE UNIQUE INDEX uq_vector_cleanup_tasks_pending_target
    ON vector_cleanup_tasks(target_type, target_id)
    WHERE status = 'PENDING';
