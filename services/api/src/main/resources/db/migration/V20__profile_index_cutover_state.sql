ALTER TABLE knowledge_bases
ADD COLUMN profile_index_activated BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE knowledge_bases kb
SET profile_index_activated = TRUE
WHERE EXISTS (
    SELECT 1 FROM documents d WHERE d.kb_id = kb.id
)
AND NOT EXISTS (
    SELECT 1
    FROM documents d
    WHERE d.kb_id = kb.id
      AND (d.status <> 'COMPLETED' OR d.index_schema_version < 2)
);
