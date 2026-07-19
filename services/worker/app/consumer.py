import json
import logging
import tempfile
from pathlib import Path

from app.callback import download_object, post_status
from app.canonical import canonicalize
from app.embedder import Embedder
from app.indexer import MilvusIndexer
from app.models import DocumentNode
from app.parser.document_parser import parse_document
from app.profile_index_plan import build_profile_index_plan

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
    embedding_dimension = int(job.get("embeddingDimension", 1536))
    index_schema_version = int(job.get("indexSchemaVersion", 2))

    def update(
        status: str,
        stage: str,
        error: str | None = None,
        chunks=None,
        completed_schema_version: int | None = None,
    ):
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
        if completed_schema_version is not None:
            payload["indexSchemaVersion"] = completed_schema_version
        post_status(payload)

    try:
        update("processing", "parsing")
        suffix = Path(file_name).suffix or ".bin"
        with tempfile.TemporaryDirectory() as tmp:
            local_path = Path(tmp) / f"doc{suffix}"
            download_object(object_key, str(local_path))
            try:
                md_text = canonicalize(local_path, mime_type, file_name)
                nodes = [
                    DocumentNode(
                        text=md_text,
                        metadata={"source": file_name, "format": "canonical_md"},
                    )
                ]
            except ValueError:
                logger.warning(
                    "canonicalize unsupported for %s, falling back to parse_document",
                    file_name,
                )
                nodes = parse_document(local_path, mime_type, file_name)

        update("processing", "chunking")
        embedder = Embedder(model=embedding_model)
        embed_fn = embedder.embed if chunk_strategy == "semantic" else None
        plan = build_profile_index_plan(
            nodes,
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
            strategy=chunk_strategy,
            embed_fn=embed_fn,
            kb_id=kb_id,
            doc_id=doc_id,
        )
        index_chunks = plan.v2_index_chunks

        if not index_chunks:
            raise ValueError("No chunks produced from document")

        update("processing", "embedding")
        vectors = embedder.embed_batch([c.content for c in index_chunks])
        if len(vectors) != len(index_chunks):
            raise ValueError(f"Embedding count mismatch: chunks={len(index_chunks)} vectors={len(vectors)}")

        bad_dims = [len(v) for v in vectors if len(v) != embedding_dimension]
        if bad_dims:
            raise ValueError(
                f"Embedding dimension mismatch: expected={embedding_dimension} actual={bad_dims[0]}"
            )

        update("processing", "indexing")
        indexer = MilvusIndexer(dimension=embedding_dimension)
        indexer.delete_by_doc(doc_id)
        indexer.index_chunks(kb_id, doc_id, index_chunks, vectors)

        update(
            "completed",
            "completed",
            chunks=plan.persisted_chunks,
            completed_schema_version=index_schema_version,
        )
        logger.info(
            "Ingest job %s completed with %d persisted chunks and %d indexed chunks",
            job_id,
            len(plan.persisted_chunks),
            len(index_chunks),
        )

    except Exception as exc:
        logger.exception("Ingest job %s failed", job_id)
        update("failed", "failed", error=str(exc))
