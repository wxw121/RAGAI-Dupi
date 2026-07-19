import logging
import uuid

import httpx

from app.chunker.recursive_chunker import count_tokens
from app.config import settings
from app.models import TextChunk

logger = logging.getLogger(__name__)
QA_SOURCE_BATCH_SIZE = 16


def fetch_qa_candidates(
    kb_id: str,
    doc_id: str,
    sources: list[TextChunk],
) -> list[dict]:
    headers = {}
    if settings.dupi_internal_key:
        headers["X-Dupi-Internal-Key"] = settings.dupi_internal_key
    candidates: list[dict] = []
    with httpx.Client(base_url=settings.api_base_url, timeout=60.0) as client:
        for offset in range(0, len(sources), QA_SOURCE_BATCH_SIZE):
            batch = sources[offset:offset + QA_SOURCE_BATCH_SIZE]
            payload = {
                "docId": doc_id,
                "sources": [
                    {
                        "chunkId": source.id,
                        "content": source.content,
                        "metadata": source.metadata,
                    }
                    for source in batch
                ],
            }
            response = client.post(
                f"/api/v1/internal/knowledge-bases/{kb_id}/qa-candidates",
                json=payload,
                headers=headers,
            )
            response.raise_for_status()
            body = response.json()
            batch_candidates = body.get("candidates") if isinstance(body, dict) else None
            if not isinstance(batch_candidates, list):
                raise ValueError("QA candidate response is missing candidates")
            candidates.extend(batch_candidates)
    return candidates


def materialize_qa_chunks(
    candidates: list[dict],
    source_chunks: list[TextChunk],
    start_index: int,
) -> list[TextChunk]:
    sources = {source.id: source for source in source_chunks}
    chunks: list[TextChunk] = []
    seen: set[tuple[str, str]] = set()
    for candidate in candidates:
        if not isinstance(candidate, dict):
            continue
        source_id = str(candidate.get("sourceChunkId") or "").strip()
        question = str(candidate.get("question") or "").strip()
        answer = str(candidate.get("answer") or "").strip()
        source = sources.get(source_id)
        dedupe_key = (source_id, " ".join(question.lower().split()))
        if source is None or not question or not answer or dedupe_key in seen:
            continue
        seen.add(dedupe_key)

        source_kind = str(source.metadata.get("chunk_role") or "original_chunk")
        metadata = dict(source.metadata)
        metadata.update({
            "chunk_role": "qa",
            "source_chunk_id": source.id,
            "qa_question": question,
            "qa_answer": answer,
            "qa_source_kind": source_kind,
        })
        content = f"Question: {question}\nAnswer: {answer}"
        chunks.append(TextChunk(
            id=str(uuid.uuid4()),
            chunk_index=start_index + len(chunks),
            content=content,
            token_count=count_tokens(content),
            metadata=metadata,
        ))
    return chunks


def generate_qa_chunks(
    kb_id: str,
    doc_id: str,
    source_chunks: list[TextChunk],
    start_index: int,
) -> list[TextChunk]:
    if not source_chunks:
        return []
    try:
        candidates = fetch_qa_candidates(kb_id, doc_id, source_chunks)
        return materialize_qa_chunks(candidates, source_chunks, start_index)
    except Exception as exc:
        logger.warning("QA generation failed for document %s: %s", doc_id, exc)
        return []
