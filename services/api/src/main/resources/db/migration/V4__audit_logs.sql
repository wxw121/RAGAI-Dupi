CREATE TABLE audit_logs (
    id            UUID PRIMARY KEY,
    tenant_id     VARCHAR(128) NOT NULL DEFAULT 'default',
    action        VARCHAR(64) NOT NULL,
    target_type   VARCHAR(64) NOT NULL,
    target_id     UUID,
    status        VARCHAR(32) NOT NULL,
    message       TEXT,
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_tenant_created
    ON audit_logs(tenant_id, created_at DESC);

CREATE INDEX idx_audit_logs_target
    ON audit_logs(target_type, target_id);
