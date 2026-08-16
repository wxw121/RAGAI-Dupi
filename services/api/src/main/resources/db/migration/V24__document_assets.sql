CREATE TABLE document_assets (
    id            UUID PRIMARY KEY,
    kb_id         UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    doc_id        UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    relative_path VARCHAR(2048) NOT NULL,
    object_key    VARCHAR(2048) NOT NULL,
    mime_type     VARCHAR(128) NOT NULL,
    file_name     VARCHAR(512) NOT NULL,
    file_size     BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_document_assets_doc_path UNIQUE (doc_id, relative_path)
);

CREATE INDEX idx_document_assets_kb_doc ON document_assets(kb_id, doc_id);
