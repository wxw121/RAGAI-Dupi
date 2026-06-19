import json
import logging
import tempfile
from pathlib import Path

from app.callback import download_object, post_status
from app.chunker.recursive_chunker import chunk_nodes
from app.embedder import Embedder
from app.indexer import MilvusIndexer
from app.parser.document_parser import parse_document

logger = logging.getLogger(__name__)


def process_ingest_job(job: dict):
    job_id = job["jobId"]
    kb_id = job["kbId"]
    doc_id = job["docId"]
    object_key = job["objectKey"]
    file_name = job["fileName"]
    mime_type = job["mimeType"]
    chunk_size = job.get("chunkSize", 512)
    chunk_overlap = job.get("chunkOverlap", 64)
    chunk_strategy = job.get("chunkStrategy", "recursive")
    embedding_model = job.get("embeddingModel")
    embedding_dimension = job.get("embeddingDimension", 1536)

    def update(status: str, stage: str, error: str | None = None, chunks=None):
        payload = {
            "jobId": job_id,
            "docId": doc_id,
            "status": status,
            "stage": stage,
            "errorMessage": error,
        }
        if chunks is not None:
            payload["chunks"] = [
                {
                    "id": c.id,
                    "chunkIndex": c.chunk_index,
                    "content": c.content,
                    "tokenCount": c.token_count,
                    "metadata": c.metadata,
                    "milvusId": c.milvus_id,
                }
                for c in chunks
            ]
        post_status(payload)

    try:
        update("processing", "parsing")
        suffix = Path(file_name).suffix or ".bin"
        with tempfile.TemporaryDirectory() as tmp:
            local_path = Path(tmp) / f"doc{suffix}"
            download_object(object_key, str(local_path))
            nodes = parse_document(local_path, mime_type, file_name)

        update("processing", "chunking")
        embedder = Embedder(model=embedding_model)
        chunks = chunk_nodes(
            nodes,
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
            strategy=chunk_strategy,
            embed_fn=embedder.embed if chunk_strategy == "semantic" else None,
        )

        if not chunks:
            raise ValueError("No chunks produced from document")

        update("processing", "embedding")
        vectors = embedder.embed_batch([c.content for c in chunks])

        update("processing", "indexing")
        indexer = MilvusIndexer(dimension=embedding_dimension)
        indexer.delete_by_doc(doc_id)
        indexer.index_chunks(kb_id, doc_id, chunks, vectors)

        update("completed", "completed", chunks=chunks)
        logger.info("Ingest job %s completed with %d chunks", job_id, len(chunks))

    except Exception as exc:
        logger.exception("Ingest job %s failed", job_id)
        update("failed", "failed", error=str(exc))
