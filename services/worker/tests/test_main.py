from app.main import normalize_ingest_job


def test_normalize_ingest_job_preserves_camel_case_retrieval_profile():
    normalized = normalize_ingest_job({
        "jobId": "job",
        "kbId": "kb",
        "docId": "doc",
        "objectKey": "object",
        "fileName": "doc.md",
        "mimeType": "text/markdown",
        "retrievalProfile": "qa-assisted",
    })

    assert normalized["retrievalProfile"] == "qa-assisted"


def test_normalize_ingest_job_maps_snake_case_retrieval_profile():
    normalized = normalize_ingest_job({
        "job_id": "job",
        "kb_id": "kb",
        "doc_id": "doc",
        "object_key": "object",
        "file_name": "doc.md",
        "mime_type": "text/markdown",
        "retrieval_profile": "combined",
    })

    assert normalized["retrievalProfile"] == "combined"
