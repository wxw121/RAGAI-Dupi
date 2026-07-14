import logging
import threading
from contextlib import asynccontextmanager

import redis
import uvicorn
from fastapi import FastAPI
from pydantic import BaseModel, Field

from app.config import settings
from app.consumer import process_ingest_job
from app.retrieval.hybrid import hybrid_retrieve

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


def redis_consumer_loop():
    client = redis.Redis(host=settings.redis_host, port=settings.redis_port, decode_responses=True)
    logger.info("Worker listening on queue %s", settings.ingest_queue)
    while True:
        try:
            result = client.brpop(settings.ingest_queue, timeout=5)
            if result is None:
                continue
            _, payload = result
            job = __import__("json").loads(payload)
            camel_job = {
                "jobId": job.get("jobId") or job.get("job_id"),
                "kbId": job.get("kbId") or job.get("kb_id"),
                "docId": job.get("docId") or job.get("doc_id"),
                "objectKey": job.get("objectKey") or job.get("object_key"),
                "fileName": job.get("fileName") or job.get("file_name"),
                "mimeType": job.get("mimeType") or job.get("mime_type"),
                "chunkSize": job.get("chunkSize") or job.get("chunk_size", 512),
                "chunkOverlap": job.get("chunkOverlap") or job.get("chunk_overlap", 64),
                "chunkStrategy": job.get("chunkStrategy") or job.get("chunk_strategy", "recursive"),
                "embeddingModel": job.get("embeddingModel") or job.get("embedding_model"),
                "embeddingDimension": job.get("embeddingDimension") or job.get("embedding_dimension", 1536),
            }
            process_ingest_job(camel_job)
        except Exception:
            logger.exception("Consumer error")


@asynccontextmanager
async def lifespan(app: FastAPI):
    thread = threading.Thread(target=redis_consumer_loop, daemon=True)
    thread.start()
    yield


app = FastAPI(title="dupi-RAG Worker", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "ok"}


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
    )
    return {
        "query": req.query,
        "retrieval_mode": "hybrid_rerank" if req.use_rerank else "hybrid",
        "profile_version": req.profile_version,
        "hits": hits,
    }


if __name__ == "__main__":
    uvicorn.run("app.main:app", host=settings.worker_host, port=settings.worker_port, reload=False)
