CREATE TABLE roles (
    id             UUID PRIMARY KEY,
    code           VARCHAR(64) NOT NULL UNIQUE,
    name           VARCHAR(128) NOT NULL,
    description    TEXT,
    permissions    TEXT NOT NULL DEFAULT '',
    system_builtin BOOLEAN NOT NULL DEFAULT FALSE,
    disabled       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE user_accounts (
    id                 UUID PRIMARY KEY,
    username           VARCHAR(128) NOT NULL UNIQUE,
    password_hash      TEXT NOT NULL,
    tenant_id          VARCHAR(128) NOT NULL DEFAULT 'default',
    role_code          VARCHAR(64) NOT NULL REFERENCES roles(code),
    knowledge_base_ids TEXT NOT NULL DEFAULT '',
    token_version      VARCHAR(64) NOT NULL DEFAULT '1',
    disabled           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_accounts_role_code ON user_accounts(role_code);

INSERT INTO roles (id, code, name, description, permissions, system_builtin, disabled)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'ADMIN', '管理员', '拥有全部系统权限', '*', TRUE, FALSE),
    ('00000000-0000-0000-0000-000000000002', 'OPERATOR', '运维人员', '可维护知识库、任务和审计', 'KB_READ,DOCUMENT_UPLOAD,MAINTENANCE,DOCUMENT_DELETE,CHAT_DELETE,OPS_AUDIT_READ', TRUE, FALSE),
    ('00000000-0000-0000-0000-000000000003', 'ANALYST', '分析用户', '可读取知识库并发起问答', 'KB_READ,DOCUMENT_UPLOAD,CHAT_WRITE', TRUE, FALSE),
    ('00000000-0000-0000-0000-000000000004', 'VIEWER', '只读用户', '仅可查看知识库内容', 'KB_READ', TRUE, FALSE);
