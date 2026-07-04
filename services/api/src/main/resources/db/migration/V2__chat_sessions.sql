CREATE TABLE chat_sessions (
    id          UUID PRIMARY KEY,
    kb_id       UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default',
    title       VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_sessions_kb_updated ON chat_sessions(kb_id, updated_at DESC);

CREATE TABLE chat_messages (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL,
    role            VARCHAR(32) NOT NULL,
    content         TEXT NOT NULL,
    citations       JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chat_messages_session_sequence UNIQUE (session_id, sequence_number)
);

CREATE INDEX idx_chat_messages_session_sequence ON chat_messages(session_id, sequence_number ASC);
