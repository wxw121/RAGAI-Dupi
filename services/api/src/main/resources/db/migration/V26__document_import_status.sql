ALTER TABLE documents
    ADD COLUMN import_job_id UUID REFERENCES operation_jobs(id) ON DELETE SET NULL;

CREATE INDEX idx_documents_import_job
    ON documents(import_job_id);

CREATE INDEX idx_documents_kb_visible_created
    ON documents(kb_id, created_at DESC)
    WHERE status <> 'IMPORTING';
