ALTER TABLE vector_cleanup_tasks
    ADD COLUMN knowledge_base_id UUID;

UPDATE vector_cleanup_tasks
SET knowledge_base_id = target_id
WHERE target_type IN ('KNOWLEDGE_BASE', 'PROFILE_KNOWLEDGE_BASE', 'LEGACY_KNOWLEDGE_BASE');

UPDATE vector_cleanup_tasks task
SET knowledge_base_id = document.kb_id
FROM documents document
WHERE task.target_id = document.id
  AND task.target_type IN ('DOCUMENT', 'PROFILE_DOCUMENT', 'LEGACY_DOCUMENT')
  AND task.knowledge_base_id IS NULL;

UPDATE vector_cleanup_tasks task
SET knowledge_base_id = tombstone.kb_id
FROM document_tombstones tombstone
WHERE task.target_id = tombstone.doc_id
  AND task.target_type IN ('DOCUMENT', 'PROFILE_DOCUMENT', 'LEGACY_DOCUMENT')
  AND task.knowledge_base_id IS NULL;

CREATE INDEX idx_vector_cleanup_tasks_kb_status_updated
    ON vector_cleanup_tasks(knowledge_base_id, status, updated_at DESC);
