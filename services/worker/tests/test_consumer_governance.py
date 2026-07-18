import threading
from contextlib import contextmanager
from pathlib import Path

import pytest

from app import consumer as consumer_module
from app.consumer import process_ingest_job
from app.models import TextChunk


def _job():
    return {
        "jobId": "job-1",
        "executionId": "exec-1",
        "kbId": "kb",
        "docId": "doc",
        "objectKey": "obj",
        "fileName": "a.txt",
        "mimeType": "text/plain",
        "embeddingDimension": 2,
        "sparseProfileVersion": 7,
    }


def _active_state():
    return {
        "status": "PROCESSING",
        "executionCurrent": True,
        "terminal": False,
        "leaseExpired": False,
    }


def _cancelled_state():
    return {
        "status": "CANCEL_REQUESTED",
        "executionCurrent": True,
        "terminal": False,
        "leaseExpired": False,
    }


@pytest.fixture(autouse=True)
def _avoid_live_redis_vector_lock(monkeypatch):
    @contextmanager
    def unlocked(*_args):
        yield None

    monkeypatch.setattr(consumer_module, "_document_vector_lock", unlocked)


@pytest.mark.parametrize(
    "inactive_state",
    [
        {
            "status": "PROCESSING",
            "executionCurrent": True,
            "terminal": False,
            "leaseExpired": True,
            "requeueEligible": False,
        },
        {
            "status": "PENDING",
            "executionCurrent": True,
            "terminal": False,
            "leaseExpired": False,
            "requeueEligible": True,
        },
    ],
    ids=["expired-lease", "requeue-eligible"],
)
def test_process_ingest_job_does_not_revive_an_inactive_execution(monkeypatch, inactive_state):
    events = []
    monkeypatch.setattr("app.consumer.claim_job", lambda *args: None)
    monkeypatch.setattr("app.consumer.get_job_state", lambda *args: events.append("state") or inactive_state)
    monkeypatch.setattr("app.consumer.refresh_lease", lambda *args: events.append("lease"))
    monkeypatch.setattr("app.consumer.post_status", lambda payload: events.append(("status", payload)))
    monkeypatch.setattr("app.consumer.download_object", lambda *args: events.append("download"))

    process_ingest_job(_job())

    assert len(events) == 2
    assert events[0][0] == "status"
    assert events[0][1]["stage"] == "parsing"
    assert events[1] == "state"


def test_process_ingest_job_claims_before_work_and_sends_monotonic_execution_callbacks(monkeypatch):
    events = []
    chunks = [TextChunk("c1", 0, "content", 2, {})]

    monkeypatch.setattr("app.consumer.claim_job", lambda *args: events.append(("claim", args)))
    monkeypatch.setattr("app.consumer.refresh_lease", lambda *args: events.append(("lease", args)))
    monkeypatch.setattr("app.consumer.get_job_state", lambda *args: _active_state())
    monkeypatch.setattr("app.consumer.post_status", lambda payload: events.append(("status", payload)))
    monkeypatch.setattr("app.consumer.download_object", lambda object_key, dest: Path(dest).write_text("raw", encoding="utf-8"))
    monkeypatch.setattr("app.consumer.canonicalize", lambda path, mime, name: "body")
    monkeypatch.setattr("app.consumer.chunk_nodes", lambda nodes, **kwargs: chunks)

    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, text):
            return [1.0, 2.0]

        def embed_batch(self, texts):
            return [[1.0, 2.0]]

    class FakeIndexer:
        def __init__(self, dimension):
            self.dimension = dimension

        def delete_by_doc(self, doc_id, **kwargs):
            events.append(("delete", doc_id, kwargs))

        def delete_by_ids(self, chunk_ids, **kwargs):
            events.append(("delete-owned", tuple(chunk_ids), kwargs))

        def index_chunks(self, kb_id, doc_id, chunks_arg, vectors, **kwargs):
            chunks_arg[0].milvus_id = "m1"

    monkeypatch.setattr("app.consumer.Embedder", FakeEmbedder)
    monkeypatch.setattr("app.consumer.MilvusIndexer", FakeIndexer)
    monkeypatch.setattr("app.consumer.settings.worker_id", "worker-a")
    monkeypatch.setattr("app.consumer.settings.ingest_lease_seconds", 30)

    process_ingest_job(_job())

    assert events[0] == ("claim", ("job-1", "exec-1", "worker-a", 30))
    statuses = [event[1] for event in events if event[0] == "status"]
    assert [payload["sequence"] for payload in statuses] == [1, 2, 3, 4, 5]
    assert {payload["executionId"] for payload in statuses} == {"exec-1"}
    assert statuses[-1]["status"] == "completed"


def test_process_ingest_job_cancels_before_chunking_without_index_cleanup(monkeypatch):
    events = []
    parsed = {"done": False}

    monkeypatch.setattr("app.consumer.claim_job", lambda *args: events.append(("claim", args)))
    monkeypatch.setattr("app.consumer.refresh_lease", lambda *args: None)
    monkeypatch.setattr(
        "app.consumer.get_job_state",
        lambda *args: _cancelled_state() if parsed["done"] else _active_state(),
    )
    monkeypatch.setattr("app.consumer.post_status", lambda payload: events.append(("status", payload)))
    monkeypatch.setattr("app.consumer.download_object", lambda object_key, dest: Path(dest).write_text("raw", encoding="utf-8"))
    monkeypatch.setattr(
        "app.consumer.canonicalize",
        lambda path, mime, name: parsed.__setitem__("done", True) or "body",
    )

    process_ingest_job(_job())

    statuses = [event[1] for event in events if event[0] == "status"]
    assert statuses[-1]["status"] == "cancelled"
    assert statuses[-1]["stage"] == "cancelled"
    assert not any(event[0] == "delete" for event in events)


def test_process_ingest_job_cleans_vectors_when_cancel_arrives_after_index_started(monkeypatch):
    events = []
    chunks = [TextChunk("c1", 0, "content", 2, {})]
    indexed = {"done": False}

    monkeypatch.setattr("app.consumer.claim_job", lambda *args: None)
    monkeypatch.setattr("app.consumer.refresh_lease", lambda *args: None)
    monkeypatch.setattr(
        "app.consumer.get_job_state",
        lambda *args: _cancelled_state() if indexed["done"] else _active_state(),
    )
    monkeypatch.setattr("app.consumer.post_status", lambda payload: events.append(("status", payload)))
    monkeypatch.setattr("app.consumer.download_object", lambda object_key, dest: Path(dest).write_text("raw", encoding="utf-8"))
    monkeypatch.setattr("app.consumer.canonicalize", lambda path, mime, name: "body")
    monkeypatch.setattr("app.consumer.chunk_nodes", lambda nodes, **kwargs: chunks)

    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, text):
            return [1.0, 2.0]

        def embed_batch(self, texts):
            return [[1.0, 2.0]]

    class FakeIndexer:
        def __init__(self, dimension):
            self.dimension = dimension

        def delete_by_doc(self, doc_id, **kwargs):
            events.append(("delete", doc_id, kwargs))

        def delete_by_ids(self, chunk_ids, **kwargs):
            events.append(("delete-owned", tuple(chunk_ids), kwargs))

        def index_chunks(self, kb_id, doc_id, chunks_arg, vectors, **kwargs):
            events.append(("index", doc_id, kwargs))
            indexed["done"] = True

    monkeypatch.setattr("app.consumer.Embedder", FakeEmbedder)
    monkeypatch.setattr("app.consumer.MilvusIndexer", FakeIndexer)

    process_ingest_job(_job())

    assert [event[0] for event in events].count("delete") == 1
    assert [event[0] for event in events].count("delete-owned") == 1
    assert next(event[1] for event in events if event[0] == "delete-owned") == (chunks[0].id,)
    assert events[-1][0] == "status"
    assert events[-1][1]["status"] == "cancelled"


def test_process_ingest_job_stops_on_ignored_callback_and_continues_claim_sequence(monkeypatch):
    statuses = []
    downloads = []

    monkeypatch.setattr(
        "app.consumer.claim_job",
        lambda *args: {"callbackSequence": 4},
    )
    monkeypatch.setattr("app.consumer.refresh_lease", lambda *args: None)
    monkeypatch.setattr("app.consumer.get_job_state", lambda *args: _active_state())
    monkeypatch.setattr(
        "app.consumer.post_status",
        lambda payload: statuses.append(payload) or {
            "ignored": True,
            "reason": "stale_execution",
        },
    )
    monkeypatch.setattr(
        "app.consumer.download_object",
        lambda *args: downloads.append(args),
    )

    process_ingest_job(_job())

    assert [payload["sequence"] for payload in statuses] == [5]
    assert downloads == []


def test_process_ingest_job_checks_stale_state_before_refreshing_lease(monkeypatch):
    events = []
    statuses = []
    stale = {
        "status": "PROCESSING",
        "executionCurrent": False,
        "terminal": False,
        "leaseExpired": False,
    }

    monkeypatch.setattr("app.consumer.claim_job", lambda *args: None)
    monkeypatch.setattr(
        "app.consumer.get_job_state",
        lambda *args: events.append("state") or stale,
    )
    monkeypatch.setattr(
        "app.consumer.refresh_lease",
        lambda *args: events.append("lease"),
    )
    monkeypatch.setattr(
        "app.consumer.post_status",
        lambda payload: statuses.append(payload),
    )
    monkeypatch.setattr(
        "app.consumer.download_object",
        lambda *args: events.append("download"),
    )

    process_ingest_job(_job())

    assert events == ["state"]
    assert [payload["stage"] for payload in statuses] == ["parsing"]


def test_process_ingest_job_heartbeats_during_long_parse_chunk_and_index(monkeypatch):
    chunks = [TextChunk("c1", 0, "content", 2, {})]
    phase = {"value": None}
    heartbeat_seen = {
        name: threading.Event()
        for name in ("parse", "chunk", "index")
    }

    def wait_for_heartbeat(name, result):
        phase["value"] = name
        assert heartbeat_seen[name].wait(1), f"no heartbeat during {name}"
        phase["value"] = None
        return result

    monkeypatch.setattr("app.consumer.claim_job", lambda *args: None)
    monkeypatch.setattr("app.consumer.get_job_state", lambda *args: _active_state())

    def refresh(*args):
        if phase["value"] in heartbeat_seen:
            heartbeat_seen[phase["value"]].set()

    monkeypatch.setattr("app.consumer.refresh_lease", refresh)
    monkeypatch.setattr("app.consumer.post_status", lambda payload: None)
    monkeypatch.setattr(
        "app.consumer.download_object",
        lambda object_key, dest: Path(dest).write_text("raw", encoding="utf-8"),
    )
    monkeypatch.setattr(
        "app.consumer.canonicalize",
        lambda *args: wait_for_heartbeat("parse", "body"),
    )
    monkeypatch.setattr(
        "app.consumer.chunk_nodes",
        lambda *args, **kwargs: wait_for_heartbeat("chunk", chunks),
    )

    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, text):
            return [1.0, 2.0]

        def embed_batch(self, texts):
            return [[1.0, 2.0]]

    class FakeIndexer:
        def __init__(self, dimension):
            self.dimension = dimension

        def delete_by_doc(self, doc_id, **kwargs):
            return None

        def index_chunks(self, kb_id, doc_id, chunks_arg, vectors, **kwargs):
            wait_for_heartbeat("index", None)

    monkeypatch.setattr("app.consumer.Embedder", FakeEmbedder)
    monkeypatch.setattr("app.consumer.MilvusIndexer", FakeIndexer)
    monkeypatch.setattr("app.consumer.settings.ingest_heartbeat_interval_seconds", 0.01)

    process_ingest_job(_job())

    assert all(event.is_set() for event in heartbeat_seen.values())


def test_process_ingest_job_cleans_vectors_when_execution_turns_stale_during_index(monkeypatch):
    events = []
    chunks = [TextChunk("c1", 0, "content", 2, {})]
    indexing = threading.Event()
    stale_seen = threading.Event()

    def state(*args):
        if indexing.is_set():
            stale_seen.set()
            return {
                "status": "PROCESSING",
                "executionCurrent": False,
                "terminal": False,
                "leaseExpired": False,
            }
        return _active_state()

    monkeypatch.setattr("app.consumer.claim_job", lambda *args: None)
    monkeypatch.setattr("app.consumer.get_job_state", state)
    monkeypatch.setattr("app.consumer.refresh_lease", lambda *args: None)
    monkeypatch.setattr("app.consumer.post_status", lambda payload: events.append(("status", payload)))
    monkeypatch.setattr(
        "app.consumer.download_object",
        lambda object_key, dest: Path(dest).write_text("raw", encoding="utf-8"),
    )
    monkeypatch.setattr("app.consumer.canonicalize", lambda *args: "body")
    monkeypatch.setattr("app.consumer.chunk_nodes", lambda *args, **kwargs: chunks)

    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, text):
            return [1.0, 2.0]

        def embed_batch(self, texts):
            return [[1.0, 2.0]]

    class FakeIndexer:
        def __init__(self, dimension):
            self.dimension = dimension

        def delete_by_doc(self, doc_id, **kwargs):
            events.append(("delete", doc_id, kwargs))

        def delete_by_ids(self, chunk_ids, **kwargs):
            events.append(("delete-owned", tuple(chunk_ids), kwargs))

        def index_chunks(self, kb_id, doc_id, chunks_arg, vectors, **kwargs):
            events.append(("index", doc_id, kwargs))
            indexing.set()
            assert stale_seen.wait(1), "heartbeat did not observe stale execution"
            raise RuntimeError("index connection dropped")

    monkeypatch.setattr("app.consumer.Embedder", FakeEmbedder)
    monkeypatch.setattr("app.consumer.MilvusIndexer", FakeIndexer)
    monkeypatch.setattr("app.consumer.settings.ingest_heartbeat_interval_seconds", 0.01)

    process_ingest_job(_job())

    assert [event[0] for event in events].count("delete") == 1
    assert [event[0] for event in events].count("delete-owned") == 1
    assert not any(
        event[0] == "status" and event[1]["status"] in {"completed", "failed"}
        for event in events
    )


def test_process_ingest_job_cleans_vectors_when_completion_callback_is_stale(monkeypatch):
    events = []
    chunks = [TextChunk("c1", 0, "content", 2, {})]

    monkeypatch.setattr("app.consumer.claim_job", lambda *args: None)
    monkeypatch.setattr("app.consumer.get_job_state", lambda *args: _active_state())
    monkeypatch.setattr("app.consumer.refresh_lease", lambda *args: None)

    def post_status(payload):
        events.append(("status", payload))
        if payload["status"] == "completed":
            return {"ignored": True, "reason": "stale_execution"}
        return {"ignored": False}

    monkeypatch.setattr("app.consumer.post_status", post_status)
    monkeypatch.setattr(
        "app.consumer.download_object",
        lambda object_key, dest: Path(dest).write_text("raw", encoding="utf-8"),
    )
    monkeypatch.setattr("app.consumer.canonicalize", lambda *args: "body")
    monkeypatch.setattr("app.consumer.chunk_nodes", lambda *args, **kwargs: chunks)

    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, text):
            return [1.0, 2.0]

        def embed_batch(self, texts):
            return [[1.0, 2.0]]

    class FakeIndexer:
        def __init__(self, dimension):
            self.dimension = dimension

        def delete_by_doc(self, doc_id, **kwargs):
            events.append(("delete", doc_id, kwargs))

        def delete_by_ids(self, chunk_ids, **kwargs):
            events.append(("delete-owned", tuple(chunk_ids), kwargs))

        def index_chunks(self, kb_id, doc_id, chunks_arg, vectors, **kwargs):
            events.append(("index", doc_id, kwargs))

    monkeypatch.setattr("app.consumer.Embedder", FakeEmbedder)
    monkeypatch.setattr("app.consumer.MilvusIndexer", FakeIndexer)

    process_ingest_job(_job())

    assert [event[0] for event in events].count("delete") == 1
    assert [event[0] for event in events].count("delete-owned") == 1


def test_process_ingest_job_does_not_retain_when_cancel_callback_turns_stale(monkeypatch):
    statuses = []

    monkeypatch.setattr("app.consumer.claim_job", lambda *args: None)
    monkeypatch.setattr("app.consumer.get_job_state", lambda *args: _cancelled_state())
    monkeypatch.setattr("app.consumer.refresh_lease", lambda *args: None)

    def post_status(payload):
        statuses.append(payload)
        if payload["status"] == "cancelled":
            return {"ignored": True, "reason": "stale_execution"}
        return {"ignored": False}

    monkeypatch.setattr("app.consumer.post_status", post_status)

    process_ingest_job(_job())

    assert [payload["status"] for payload in statuses] == ["processing", "cancelled"]


def test_process_ingest_job_cleans_partial_index_when_failure_callback_is_stale(monkeypatch):
    events = []
    chunks = [TextChunk("c1", 0, "content", 2, {})]

    monkeypatch.setattr("app.consumer.claim_job", lambda *args: None)
    monkeypatch.setattr("app.consumer.get_job_state", lambda *args: _active_state())
    monkeypatch.setattr("app.consumer.refresh_lease", lambda *args: None)

    def post_status(payload):
        events.append(("status", payload))
        if payload["status"] == "failed":
            return {"ignored": True, "reason": "stale_execution"}
        return {"ignored": False}

    monkeypatch.setattr("app.consumer.post_status", post_status)
    monkeypatch.setattr(
        "app.consumer.download_object",
        lambda object_key, dest: Path(dest).write_text("raw", encoding="utf-8"),
    )
    monkeypatch.setattr("app.consumer.canonicalize", lambda *args: "body")
    monkeypatch.setattr("app.consumer.chunk_nodes", lambda *args, **kwargs: chunks)

    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, text):
            return [1.0, 2.0]

        def embed_batch(self, texts):
            return [[1.0, 2.0]]

    class FakeIndexer:
        def __init__(self, dimension):
            self.dimension = dimension

        def delete_by_doc(self, doc_id, **kwargs):
            events.append(("delete", doc_id, kwargs))

        def delete_by_ids(self, chunk_ids, **kwargs):
            events.append(("delete-owned", tuple(chunk_ids), kwargs))

        def index_chunks(self, kb_id, doc_id, chunks_arg, vectors, **kwargs):
            raise RuntimeError("partial index failure")

    monkeypatch.setattr("app.consumer.Embedder", FakeEmbedder)
    monkeypatch.setattr("app.consumer.MilvusIndexer", FakeIndexer)

    process_ingest_job(_job())

    assert [event[0] for event in events].count("delete") == 1
    assert [event[0] for event in events].count("delete-owned") == 1


def test_process_ingest_job_cleans_owned_partial_index_when_failure_is_accepted(monkeypatch):
    events = []
    chunks = [TextChunk("unscoped", 0, "content", 2, {})]

    monkeypatch.setattr("app.consumer.claim_job", lambda *args: None)
    monkeypatch.setattr("app.consumer.get_job_state", lambda *args: _active_state())
    monkeypatch.setattr("app.consumer.refresh_lease", lambda *args: None)
    monkeypatch.setattr(
        "app.consumer.post_status",
        lambda payload: events.append(("status", payload)) or {"ignored": False},
    )
    monkeypatch.setattr(
        "app.consumer.download_object",
        lambda object_key, dest: Path(dest).write_text("raw", encoding="utf-8"),
    )
    monkeypatch.setattr("app.consumer.canonicalize", lambda *args: "body")
    monkeypatch.setattr("app.consumer.chunk_nodes", lambda *args, **kwargs: chunks)

    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, text):
            return [1.0, 2.0]

        def embed_batch(self, texts):
            return [[1.0, 2.0]]

    class FakeIndexer:
        def __init__(self, dimension):
            self.dimension = dimension

        def delete_by_doc(self, doc_id, **kwargs):
            events.append(("delete-doc", doc_id, kwargs))

        def delete_by_ids(self, chunk_ids, **kwargs):
            events.append(("delete-owned", tuple(chunk_ids), kwargs))

        def index_chunks(self, kb_id, doc_id, chunks_arg, vectors, **kwargs):
            events.append(("partial-index", chunks_arg[0].id))
            raise RuntimeError("partial index failure")

    monkeypatch.setattr("app.consumer.Embedder", FakeEmbedder)
    monkeypatch.setattr("app.consumer.MilvusIndexer", FakeIndexer)

    process_ingest_job(_job())

    owned_deletes = [event for event in events if event[0] == "delete-owned"]
    assert len(owned_deletes) == 1
    assert owned_deletes[0][1] == (chunks[0].id,)
    assert [event[1]["status"] for event in events if event[0] == "status"][-1] == "failed"


def test_vector_mutations_are_serialized_and_stale_cleanup_cannot_erase_new_execution(monkeypatch):
    assert hasattr(consumer_module, "_mutate_document_vectors"), (
        "consumer must fence the complete delete/index operation"
    )

    rows = set()
    shared_lock = threading.Lock()
    a_inserted = threading.Event()
    b_waiting = threading.Event()
    release_a = threading.Event()
    current_execution = {"value": "exec-a"}
    failures = []

    @contextmanager
    def serialized_lock(*_args):
        if threading.current_thread().name == "execution-b":
            b_waiting.set()
        with shared_lock:
            yield

    monkeypatch.setattr(consumer_module, "_document_vector_lock", serialized_lock)

    class SharedIndexer:
        def delete_by_doc(self, doc_id, **kwargs):
            rows.clear()

        def delete_by_ids(self, chunk_ids, **kwargs):
            rows.difference_update(chunk_ids)

        def index_chunks(self, kb_id, doc_id, chunks, vectors, **kwargs):
            rows.update(chunk.id for chunk in chunks)
            if threading.current_thread().name == "execution-a":
                a_inserted.set()
                assert release_a.wait(1)

    indexer = SharedIndexer()
    chunks_a = [TextChunk("source-a", 0, "A", 1, {})]
    chunks_b = [TextChunk("source-b", 0, "B", 1, {})]

    def run(execution_id, chunks):
        def ensure_active():
            if current_execution["value"] != execution_id:
                raise consumer_module.IngestSuperseded()

        try:
            consumer_module._mutate_document_vectors(
                indexer,
                "kb",
                "doc",
                chunks,
                [[1.0, 2.0]],
                7,
                execution_id,
                ensure_active,
            )
        except Exception as exc:
            failures.append((execution_id, exc))

    thread_a = threading.Thread(target=run, args=("exec-a", chunks_a), name="execution-a")
    thread_a.start()
    assert a_inserted.wait(1)
    current_execution["value"] = "exec-b"
    thread_b = threading.Thread(target=run, args=("exec-b", chunks_b), name="execution-b")
    thread_b.start()
    assert b_waiting.wait(1)
    release_a.set()
    thread_a.join(1)
    thread_b.join(1)

    assert not thread_a.is_alive()
    assert not thread_b.is_alive()
    assert len(rows) == 1
    assert rows == {chunks_b[0].id}
    assert len(failures) == 1
    assert failures[0][0] == "exec-a"
    assert isinstance(failures[0][1], consumer_module.IngestSuperseded)
