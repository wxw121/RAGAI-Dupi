CREATE TABLE chat_sessions (
    id          UUID PRIMARY KEY,
    kb_id       UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default',
    title       VARCHAR(255) NOT NULL,
    status      VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_sessions_kb_updated ON chat_sessions(kb_id, updated_at DESC);

CREATE TABLE chat_messages (
    id          UUID PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role        VARCHAR(32) NOT NULL,
    content     TEXT NOT NULL,
    citations   JSONB,
    status      VARCHAR(32) NOT NULL DEFAULT 'completed',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_messages_session_created ON chat_messages(session_id, created_at ASC);
