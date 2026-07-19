import hashlib
import json
import logging
import tempfile
import threading
from contextlib import contextmanager, suppress
from pathlib import Path

import redis
from redis.exceptions import LockError

from app.callback import (
    claim_job,
    download_object,
    get_job_state,
    post_status,
    refresh_lease,
)
from app.canonical import canonicalize
from app.chunker.recursive_chunker import chunk_nodes
from app.embedder import Embedder
from app.indexer import MilvusIndexer, scope_chunk_ids
from app.models import DocumentNode
from app.parser.document_parser import parse_document
from app.config import settings
from app.profile_index_plan import build_profile_index_plan
from app.retrieval.sparse import SparseMilvusAdapter

logger = logging.getLogger(__name__)


class StatusCallbackError(RuntimeError):
    pass


class VectorLockUnavailable(StatusCallbackError):
    pass


class VectorLockLost(StatusCallbackError):
    pass


class StatusIgnored(RuntimeError):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


class IngestCancelled(RuntimeError):
    pass


class IngestSuperseded(RuntimeError):
    pass


class IngestTerminal(RuntimeError):
    pass


class _VectorLockLease:
    def __init__(self, lock, lost: list[Exception]):
        self.lock = lock
        self.lost = lost

    def held(self) -> bool:
        if self.lost:
            return False
        try:
            return bool(self.lock.owned())
        except Exception:
            return False


@contextmanager
def _document_vector_lock(kb_id: str, doc_id: str):
    client = redis.Redis(
        host=settings.redis_host,
        port=settings.redis_port,
        decode_responses=True,
    )
    lock_timeout = max(30.0, float(settings.ingest_lease_seconds) * 2)
    lock_key = hashlib.sha256(f"{kb_id}\0{doc_id}".encode("utf-8")).hexdigest()
    lock = client.lock(
        f"dupi:ingest:vector-lock:{lock_key}",
        timeout=lock_timeout,
        blocking_timeout=lock_timeout,
        thread_local=False,
    )
    try:
        if not lock.acquire(blocking=True):
            raise VectorLockUnavailable(f"Timed out acquiring vector lock for document {doc_id}")

        stopped = threading.Event()
        lost: list[Exception] = []

        def renew_lock():
            while not stopped.wait(max(1.0, lock_timeout / 3)):
                try:
                    if not lock.extend(lock_timeout, replace_ttl=True):
                        lost.append(VectorLockLost(f"Lost vector lock for document {doc_id}"))
                        return
                except Exception as exc:
                    lost.append(exc)
                    return

        renewal = threading.Thread(target=renew_lock, daemon=True)
        renewal.start()
        lease = _VectorLockLease(lock, lost)
        try:
            yield lease
            if not lease.held():
                raise VectorLockLost(f"Lost vector lock for document {doc_id}") from (
                    lost[0] if lost else None
                )
        finally:
            stopped.set()
            renewal.join()
            if lease.held():
                with suppress(LockError):
                    lock.release()
    finally:
        with suppress(Exception):
            client.close()


def _mutate_document_vectors(
    indexer,
    kb_id: str,
    doc_id: str,
    chunks,
    vectors,
    sparse_profile_version: int,
    execution_id: str,
    ensure_active,
    owned_ids: list[str] | None = None,
):
    owned_ids = owned_ids or scope_chunk_ids(chunks, execution_id, doc_id)
    with _document_vector_lock(kb_id, doc_id) as lock_lease:
        ensure_active()
        try:
            indexer.delete_by_doc(
                doc_id,
                kb_id=kb_id,
                sparse_profile_version=sparse_profile_version,
                strict=True,
            )
            ensure_active()
            indexer.index_chunks(
                kb_id,
                doc_id,
                chunks,
                vectors,
                sparse_profile_version=sparse_profile_version,
            )
            ensure_active()
        except Exception as exc:
            if lock_lease is not None and not lock_lease.held():
                raise VectorLockLost(f"Lost vector lock for document {doc_id}") from exc
            indexer.delete_by_ids(
                owned_ids,
                kb_id=kb_id,
                sparse_profile_version=sparse_profile_version,
                strict=True,
            )
            raise
    return owned_ids


def _mutate_profile_vectors(
    profile_indexer,
    legacy_indexer,
    sparse_adapter,
    kb_id: str,
    doc_id: str,
    profile_chunks,
    profile_vectors,
    legacy_chunks,
    legacy_vectors,
    sparse_profile_version: int,
    ensure_active,
):
    profile_ids = [chunk.id for chunk in profile_chunks]
    legacy_ids = [chunk.id for chunk in legacy_chunks]
    with _document_vector_lock(kb_id, doc_id) as lock_lease:
        ensure_active()
        try:
            profile_indexer.delete_by_doc(doc_id, kb_id=kb_id, strict=True)
            ensure_active()
            profile_indexer.index_profile_chunks(kb_id, doc_id, profile_chunks, profile_vectors)
            ensure_active()
            if legacy_indexer is not None:
                legacy_indexer.delete_by_doc(
                    doc_id,
                    kb_id=kb_id,
                    sparse_profile_version=sparse_profile_version,
                    strict=True,
                )
                ensure_active()
                legacy_indexer.index_chunks(
                    kb_id,
                    doc_id,
                    legacy_chunks,
                    legacy_vectors,
                    sparse_profile_version=sparse_profile_version,
                )
            elif sparse_adapter is not None:
                sparse_adapter.delete_by_doc(doc_id)
                sparse_adapter.upsert(kb_id, doc_id, legacy_chunks)
            ensure_active()
        except Exception as exc:
            if lock_lease is not None and not lock_lease.held():
                raise VectorLockLost(f"Lost vector lock for document {doc_id}") from exc
            profile_indexer.delete_by_ids(profile_ids, kb_id=kb_id, strict=True)
            if legacy_indexer is not None:
                legacy_indexer.delete_by_ids(
                    legacy_ids,
                    kb_id=kb_id,
                    sparse_profile_version=sparse_profile_version,
                    strict=True,
                )
            elif sparse_adapter is not None:
                sparse_adapter.delete_by_ids(legacy_ids)
            raise


def _scope_plan_chunks(plan, execution_id: str | None, doc_id: str):
    if not execution_id:
        return
    original_ids = [chunk.id for chunk in plan.persisted_chunks]
    scope_chunk_ids(plan.persisted_chunks, execution_id, doc_id)
    id_map = {
        original_id: chunk.id
        for original_id, chunk in zip(original_ids, plan.persisted_chunks)
    }
    for chunk in plan.persisted_chunks:
        for key in ("parent_chunk_id", "source_chunk_id"):
            referenced_id = chunk.metadata.get(key)
            if referenced_id in id_map:
                chunk.metadata[key] = id_map[referenced_id]


def _run_with_heartbeat(operation, heartbeat, interval_seconds: float):
    heartbeat()
    stopped = threading.Event()
    failures = []

    def heartbeat_loop():
        while not stopped.wait(interval_seconds):
            try:
                heartbeat()
            except Exception as exc:
                failures.append(exc)
                stopped.set()
                return

    thread = threading.Thread(target=heartbeat_loop, daemon=True)
    thread.start()
    operation_failure = None
    try:
        result = operation()
    except Exception as exc:
        operation_failure = exc
    finally:
        stopped.set()
        thread.join()

    control_failure = next(
        (
            failure
            for failure in failures
            if isinstance(failure, (IngestCancelled, IngestSuperseded, IngestTerminal))
        ),
        None,
    )
    if control_failure is not None:
        raise control_failure from operation_failure
    if operation_failure is not None:
        raise operation_failure
    if failures:
        raise failures[0]
    heartbeat()
    return result


def process_ingest_job(job: dict):
    job_id = job["jobId"]
    execution_id = job.get("executionId")
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
    sparse_profile_version = int(
        job.get("sparseProfileVersion") or job.get("sparse_profile_version") or 0
    )
    index_schema_version = int(job.get("indexSchemaVersion") or 2)
    legacy_write_required = bool(job.get("legacyWriteRequired", True))
    sequence = 0
    profile_indexer = None
    legacy_indexer = None
    sparse_adapter = None
    index_started = False
    index_committed = False
    profile_vector_ids = []
    legacy_vector_ids = []

    if execution_id:
        claim_response = claim_job(
            job_id,
            execution_id,
            settings.worker_id,
            settings.ingest_lease_seconds,
        )
        if isinstance(claim_response, dict):
            sequence = int(claim_response.get("callbackSequence") or 0)

    def update(
        status: str,
        stage: str,
        error: str | None = None,
        chunks=None,
        completed_schema_version: int | None = None,
    ):
        nonlocal sequence
        sequence += 1
        payload = {
            "jobId": job_id,
            "docId": doc_id,
            "executionId": execution_id,
            "sequence": sequence,
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
        try:
            ack = post_status(payload)
        except Exception as exc:
            raise StatusCallbackError("Failed to acknowledge ingest status callback") from exc
        if isinstance(ack, dict) and ack.get("ignored"):
            raise StatusIgnored(str(ack.get("reason") or "ignored"))
        return ack

    def ensure_job_active():
        if not execution_id:
            return
        state = get_job_state(job_id, execution_id)
        if state.get("missing") or state.get("executionCurrent") is False:
            raise IngestSuperseded()
        status = str(state.get("status") or "").upper()
        if status in {"CANCEL_REQUESTED", "CANCELLED"}:
            raise IngestCancelled()
        if state.get("terminal") is True:
            raise IngestTerminal(status)
        if state.get("leaseExpired") is True or state.get("requeueEligible") is True:
            raise IngestSuperseded()
        refresh_lease(
            job_id,
            execution_id,
            settings.worker_id,
            settings.ingest_lease_seconds,
        )

    heartbeat_interval = min(
        max(0.01, float(settings.ingest_heartbeat_interval_seconds)),
        max(0.01, float(settings.ingest_lease_seconds) / 3),
    )

    def run_guarded(operation):
        if not execution_id:
            return operation()
        return _run_with_heartbeat(operation, ensure_job_active, heartbeat_interval)

    def cleanup_vectors():
        if profile_indexer is None or not profile_vector_ids:
            return
        with _document_vector_lock(kb_id, doc_id):
            profile_indexer.delete_by_ids(profile_vector_ids, kb_id=kb_id, strict=True)
            if legacy_indexer is not None and legacy_vector_ids:
                legacy_indexer.delete_by_ids(
                    legacy_vector_ids,
                    kb_id=kb_id,
                    sparse_profile_version=sparse_profile_version,
                    strict=True,
                )
            elif sparse_adapter is not None and legacy_vector_ids:
                sparse_adapter.delete_by_ids(legacy_vector_ids)

    try:
        update("processing", "parsing")

        def parse_source():
            suffix = Path(file_name).suffix or ".bin"
            with tempfile.TemporaryDirectory() as tmp:
                local_path = Path(tmp) / f"doc{suffix}"
                download_object(object_key, str(local_path))
                try:
                    md_text = canonicalize(local_path, mime_type, file_name)
                    return [
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
                    return parse_document(local_path, mime_type, file_name)

        nodes = run_guarded(parse_source)

        update("processing", "chunking")
        embedder = Embedder(model=embedding_model)
        plan = run_guarded(
            lambda: build_profile_index_plan(
                nodes,
                chunk_size=chunk_size,
                chunk_overlap=chunk_overlap,
                strategy=chunk_strategy,
                embed_fn=embedder.embed if chunk_strategy == "semantic" else None,
                kb_id=kb_id,
                doc_id=doc_id,
            )
        )
        _scope_plan_chunks(plan, execution_id, doc_id)
        chunks = plan.v2_index_chunks

        if not chunks:
            raise ValueError("No chunks produced from document")

        update("processing", "embedding")
        vectors = []
        batch_size = max(1, settings.embedding_batch_size)
        contents = [c.content for c in chunks]
        for offset in range(0, len(contents), batch_size):
            batch = contents[offset:offset + batch_size]
            vectors.extend(run_guarded(lambda batch=batch: embedder.embed_batch(batch)))
        if len(vectors) != len(chunks):
            raise ValueError(f"Embedding count mismatch: chunks={len(chunks)} vectors={len(vectors)}")

        bad_dims = [len(v) for v in vectors if len(v) != embedding_dimension]
        if bad_dims:
            raise ValueError(
                f"Embedding dimension mismatch: expected={embedding_dimension} actual={bad_dims[0]}"
            )

        update("processing", "indexing")
        profile_indexer = run_guarded(lambda: MilvusIndexer(
            dimension=embedding_dimension,
            collection_name=settings.milvus_profile_collection,
            profile_schema=True,
        ))
        if legacy_write_required:
            legacy_indexer = run_guarded(lambda: MilvusIndexer(dimension=embedding_dimension))
        if sparse_profile_version > 0 and legacy_indexer is None:
            sparse_adapter = SparseMilvusAdapter(kb_id, sparse_profile_version)
        index_started = True
        profile_vector_ids = [chunk.id for chunk in chunks]
        legacy_vector_ids = [chunk.id for chunk in plan.legacy_chunks]
        vector_by_chunk_id = {
            chunk.id: vector
            for chunk, vector in zip(chunks, vectors)
        }
        legacy_vectors = [vector_by_chunk_id[chunk.id] for chunk in plan.legacy_chunks]

        if execution_id:
            def mutate_vectors():
                nonlocal index_committed
                _mutate_profile_vectors(
                    profile_indexer,
                    legacy_indexer,
                    sparse_adapter,
                    kb_id,
                    doc_id,
                    chunks,
                    vectors,
                    plan.legacy_chunks,
                    legacy_vectors,
                    sparse_profile_version,
                    ensure_job_active,
                )
                index_committed = True

            run_guarded(mutate_vectors)
        else:
            profile_indexer.delete_by_doc(doc_id)
            profile_indexer.index_profile_chunks(kb_id, doc_id, chunks, vectors)
            if legacy_indexer is not None:
                legacy_indexer.delete_by_doc(
                    doc_id,
                    kb_id=kb_id,
                    sparse_profile_version=sparse_profile_version,
                )
                legacy_indexer.index_chunks(
                    kb_id,
                    doc_id,
                    plan.legacy_chunks,
                    legacy_vectors,
                    sparse_profile_version=sparse_profile_version,
                )
            elif sparse_adapter is not None:
                sparse_adapter.delete_by_doc(doc_id)
                sparse_adapter.upsert(kb_id, doc_id, plan.legacy_chunks)
            index_committed = True

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
            len(chunks),
        )

    except IngestCancelled:
        if index_committed:
            cleanup_vectors()
        try:
            update("cancelled", "cancelled")
        except StatusIgnored as ignored:
            logger.info(
                "Cancellation callback for ingest job %s was ignored: %s",
                job_id,
                ignored.reason,
            )
        logger.info("Ingest job %s cancelled", job_id)
    except IngestSuperseded:
        if index_committed:
            cleanup_vectors()
        logger.info("Stopped superseded ingest execution %s for job %s", execution_id, job_id)
    except IngestTerminal as terminal:
        logger.info("Stopped ingest job %s already in terminal state %s", job_id, terminal)
    except StatusIgnored as ignored:
        if ignored.reason == "cancel_requested":
            if index_committed:
                cleanup_vectors()
            try:
                update("cancelled", "cancelled")
            except StatusIgnored as terminal_ignored:
                logger.info(
                    "Cancellation callback for ingest job %s was ignored: %s",
                    job_id,
                    terminal_ignored.reason,
                )
        elif ignored.reason in {"document_tombstoned", "stale_execution"} and index_committed:
            cleanup_vectors()
        logger.info("Stopped ingest job %s after ignored callback: %s", job_id, ignored.reason)
    except StatusCallbackError:
        raise
    except Exception as exc:
        logger.exception("Ingest job %s failed", job_id)
        if index_committed:
            cleanup_vectors()
        try:
            update("failed", "failed", error=str(exc))
        except StatusIgnored as ignored:
            logger.info(
                "Failure callback for ingest job %s was ignored: %s",
                job_id,
                ignored.reason,
            )
