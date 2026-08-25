ALTER TABLE rag_eval_cases
    ADD COLUMN expected_document_id UUID REFERENCES documents(id) ON DELETE SET NULL,
    ADD COLUMN expected_document_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

WITH unique_documents AS (
    SELECT kb_id, file_name, MIN(id::text)::uuid AS document_id
    FROM documents
    GROUP BY kb_id, file_name
    HAVING COUNT(*) = 1
), single_source_cases AS (
    SELECT cases.id AS case_id, unique_documents.document_id
    FROM rag_eval_cases cases
    JOIN unique_documents
      ON unique_documents.kb_id = cases.kb_id
     AND unique_documents.file_name = cases.expected_file_name
    WHERE COALESCE(jsonb_array_length(cases.expected_file_names), 0) = 0
)
UPDATE rag_eval_cases
SET expected_document_id = single_source_cases.document_id
FROM single_source_cases
WHERE rag_eval_cases.id = single_source_cases.case_id;

WITH unique_documents AS (
    SELECT kb_id, file_name, MIN(id::text)::uuid AS document_id
    FROM documents
    GROUP BY kb_id, file_name
    HAVING COUNT(*) = 1
), source_names AS (
    SELECT cases.id AS case_id, cases.kb_id, cases.expected_file_name AS file_name, 0 AS source_order
    FROM rag_eval_cases cases
    WHERE cases.expected_file_name IS NOT NULL
      AND NOT (COALESCE(jsonb_array_length(cases.expected_file_names), 0) = 0)
    UNION ALL
    SELECT cases.id, cases.kb_id, names.file_name, names.source_order
    FROM rag_eval_cases cases
    CROSS JOIN LATERAL jsonb_array_elements_text(cases.expected_file_names)
        WITH ORDINALITY AS names(file_name, source_order)
), resolved_sources AS (
    SELECT source_names.case_id,
           COUNT(*) AS source_count,
           COUNT(unique_documents.document_id) AS resolved_count,
           jsonb_agg(to_jsonb(unique_documents.document_id) ORDER BY source_names.source_order)
               FILTER (WHERE unique_documents.document_id IS NOT NULL) AS document_ids
    FROM source_names
    LEFT JOIN unique_documents
      ON unique_documents.kb_id = source_names.kb_id
     AND unique_documents.file_name = source_names.file_name
    GROUP BY source_names.case_id
)
UPDATE rag_eval_cases
SET expected_document_ids = resolved_sources.document_ids
FROM resolved_sources
WHERE rag_eval_cases.id = resolved_sources.case_id
  AND resolved_sources.source_count = resolved_sources.resolved_count
  AND resolved_sources.source_count > 1;

-- Ambiguous names intentionally remain unresolved so validation can surface them for regeneration.
