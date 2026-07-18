import logging
import threading
import json
import time
from contextlib import asynccontextmanager
from types import SimpleNamespace

import redis
import uvicorn
from fastapi import FastAPI
from pydantic import BaseModel, Field

from app.callback import get_job_state
from app.config import settings
from app.consumer import process_ingest_job
from app.indexer import MilvusIndexer
from app.retrieval.hybrid import hybrid_retrieve, _reranker_lifecycle
from app.retrieval.sparse import SparseMilvusAdapter

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


class HybridRetrieveRequest(BaseModel):
    kb_id: str
    query: str
    top_k: int = 5
    use_rerank: bool = False
    embedding_model: str = "text-embedding-3-small"
    embedding_dimension: int = 1536
    profile_version: int | None = None
    vector_candidate_count: int | None = None
    sparse_candidate_count: int | None = None
    rrf_constant: int = 60
    sparse_index_params: dict = Field(default_factory=dict)
    sparse_search_params: dict = Field(default_factory=dict)
    rerank_candidate_limit: int | None = None
    final_top_k: int | None = None
    allow_legacy_bm25_fallback: bool = False
    shadow_profile_version: int | None = None
    shadow_sparse_index_params: dict = Field(default_factory=dict)
    shadow_sparse_search_params: dict = Field(default_factory=dict)


class SparseBackfillChunk(BaseModel):
    chunk_id: str
    doc_id: str
    content: str


class SparseBackfillRequest(BaseModel):
    kb_id: str
    profile_version: int
    embedding_dimension: int
    sparse_index_params: dict = Field(default_factory=dict)
    chunks: list[SparseBackfillChunk]


def redis_consumer_loop():
    client = redis.Redis(host=settings.redis_host, port=settings.redis_port, decode_responses=True)
    logger.info("Worker listening on queue %s", settings.ingest_queue)
    next_reap_at = 0.0
    while True:
        try:
            now = time.monotonic()
            if now >= next_reap_at:
                reap_processing_entries(client)
                next_reap_at = now + max(
                    1.0,
                    settings.ingest_processing_reap_interval_seconds,
                )
            consume_once(client)
        except Exception:
            logger.exception("Redis consumer iteration failed; retrying")
            time.sleep(max(0.0, settings.redis_retry_delay_seconds))


def _message_value(message: dict, camel: str, snake: str, default=None):
    value = message.get(camel)
    if value is None:
        value = message.get(snake)
    return default if value is None else value


def _normalize_job(message: dict) -> dict:
    return {
        "jobId": _message_value(message, "jobId", "job_id"),
        "executionId": _message_value(message, "executionId", "execution_id"),
        "kbId": _message_value(message, "kbId", "kb_id"),
        "docId": _message_value(message, "docId", "doc_id"),
        "objectKey": _message_value(message, "objectKey", "object_key"),
        "fileName": _message_value(message, "fileName", "file_name"),
        "mimeType": _message_value(message, "mimeType", "mime_type"),
        "chunkSize": _message_value(message, "chunkSize", "chunk_size", 512),
        "chunkOverlap": _message_value(message, "chunkOverlap", "chunk_overlap", 64),
        "chunkStrategy": _message_value(message, "chunkStrategy", "chunk_strategy", "recursive"),
        "embeddingModel": _message_value(message, "embeddingModel", "embedding_model"),
        "embeddingDimension": _message_value(
            message, "embeddingDimension", "embedding_dimension", 1536
        ),
        "sparseProfileVersion": _message_value(
            message, "sparseProfileVersion", "sparse_profile_version", 0
        ),
    }


_REQUIRED_JOB_FIELDS = (
    "jobId",
    "executionId",
    "kbId",
    "docId",
    "objectKey",
    "fileName",
    "mimeType",
)


def _decode_job(payload: str) -> dict:
    message = json.loads(payload)
    if not isinstance(message, dict):
        raise ValueError("Ingest queue payload must be a JSON object")
    job = _normalize_job(message)
    missing = [field for field in _REQUIRED_JOB_FIELDS if not job.get(field)]
    if missing:
        raise ValueError(f"Ingest queue payload missing fields: {', '.join(missing)}")
    return job


def _remove_processing_payload(client, payload: str) -> bool:
    return bool(client.lrem(settings.ingest_processing_queue, 1, payload))


_REQUEUE_PROCESSING_SCRIPT = """
local removed = redis.call('LREM', KEYS[1], 1, ARGV[1])
if removed == 1 then
    redis.call('LPUSH', KEYS[2], ARGV[1])
end
return removed
"""


def _requeue_processing_payload(client, payload: str) -> bool:
    return bool(client.eval(
        _REQUEUE_PROCESSING_SCRIPT,
        2,
        settings.ingest_processing_queue,
        settings.ingest_queue,
        payload,
    ))


def reap_processing_entries(client) -> int:
    reaped = 0
    batch_size = max(1, settings.ingest_processing_reap_batch_size)
    for payload in client.lrange(settings.ingest_processing_queue, -batch_size, -1):
        try:
            job = _decode_job(payload)
        except (json.JSONDecodeError, TypeError, ValueError):
            logger.warning("Removing malformed payload from processing queue")
            reaped += int(_remove_processing_payload(client, payload))
            continue

        try:
            state = get_job_state(job["jobId"], job["executionId"])
        except Exception:
            logger.warning(
                "Could not inspect ingest job %s; retaining processing payload",
                job["jobId"],
                exc_info=True,
            )
            continue

        if state.get("requeueEligible") is True:
            reaped += int(_requeue_processing_payload(client, payload))
            continue
        if (
            state.get("missing")
            or state.get("terminal") is True
            or state.get("executionCurrent") is False
        ):
            reaped += int(_remove_processing_payload(client, payload))
    return reaped


def consume_once(client) -> bool:
    payload = client.brpoplpush(
        settings.ingest_queue,
        settings.ingest_processing_queue,
        timeout=5,
    )
    if payload is None:
        return False
    try:
        job = _decode_job(payload)
    except (json.JSONDecodeError, TypeError, ValueError):
        logger.exception("Discarding malformed ingest queue payload")
        _remove_processing_payload(client, payload)
        return False
    try:
        process_ingest_job(job)
        _remove_processing_payload(client, payload)
        return True
    except Exception:
        logger.exception("Consumer error; payload retained in processing queue")
        time.sleep(max(0.0, settings.redis_retry_delay_seconds))
        return False


@asynccontextmanager
async def lifespan(app: FastAPI):
    if settings.rerank_warmup_enabled:
        status = _reranker_lifecycle.warmup()
        if not status.ready:
            logger.warning("Reranker warmup failed: %s", status.error)
    thread = threading.Thread(target=redis_consumer_loop, daemon=True)
    thread.start()
    yield


app = FastAPI(title="dupi-RAG Worker", lifespan=lifespan)


@app.get("/health")
def health():
    reranker = _reranker_lifecycle.status()
    return {
        "status": "ok",
        "rerankerConfigured": settings.rerank_warmup_enabled,
        "rerankerReady": reranker.ready,
        "rerankerModel": reranker.model,
    }


@app.post("/api/v1/retrieve/hybrid")
def retrieve_hybrid(req: HybridRetrieveRequest):
    hits = hybrid_retrieve(
        kb_id=req.kb_id,
        query=req.query,
        top_k=req.top_k,
        embedding_model=req.embedding_model,
        embedding_dimension=req.embedding_dimension,
        use_rerank=req.use_rerank,
        vector_candidate_count=req.vector_candidate_count,
        sparse_candidate_count=req.sparse_candidate_count,
        rrf_constant=req.rrf_constant,
        rerank_candidate_limit=req.rerank_candidate_limit,
        final_top_k=req.final_top_k,
        sparse_index_params=req.sparse_index_params,
        sparse_search_params=req.sparse_search_params,
        profile_version=req.profile_version,
        allow_legacy_bm25_fallback=req.allow_legacy_bm25_fallback,
        shadow_profile_version=req.shadow_profile_version,
        shadow_sparse_index_params=req.shadow_sparse_index_params,
        shadow_sparse_search_params=req.shadow_sparse_search_params,
    )
    rerank_applied = bool(req.use_rerank and any(hit.get("rerank_rank") is not None for hit in hits))
    return {
        "query": req.query,
        "retrieval_mode": "hybrid_rerank" if rerank_applied else "hybrid",
        "rerank_applied": rerank_applied,
        "fallback_reason": "reranker_unavailable" if req.use_rerank and not rerank_applied else None,
        "profile_version": req.profile_version,
        "hits": hits,
    }


@app.post("/api/v1/retrieve/sparse/backfill")
def backfill_sparse(req: SparseBackfillRequest):
    MilvusIndexer(dimension=req.embedding_dimension)
    adapter = SparseMilvusAdapter(req.kb_id, req.profile_version, req.sparse_index_params)
    grouped: dict[str, list] = {}
    for chunk in req.chunks:
        grouped.setdefault(chunk.doc_id, []).append(SimpleNamespace(id=chunk.chunk_id, content=chunk.content))
    indexed = 0
    for doc_id, chunks in grouped.items():
        indexed += len(adapter.upsert(req.kb_id, doc_id, chunks))
    return {
        "indexed_count": indexed,
        "collection_count": adapter.count(req.kb_id),
        "verified_dimension": req.embedding_dimension,
        "dual_write_enabled": True,
    }


if __name__ == "__main__":
    uvicorn.run("app.main:app", host=settings.worker_host, port=settings.worker_port, reload=False)
