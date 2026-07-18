import json

import pytest

from app import main


class FakeRedis:
    def __init__(self, payload=None, processing_entries=None):
        self.payload = payload
        self.processing_entries = list(processing_entries or [])
        self.moves = []
        self.acks = []
        self.ranges = []
        self.eval_calls = []

    def brpoplpush(self, ready, processing, timeout=0):
        self.moves.append((ready, processing, timeout))
        return self.payload

    def lrem(self, queue, count, payload):
        self.acks.append((queue, count, payload))
        return 1

    def lrange(self, queue, start, end):
        self.ranges.append((queue, start, end))
        if end == -1:
            return list(self.processing_entries[start:])
        return list(self.processing_entries[start : end + 1])

    def eval(self, script, numkeys, *args):
        self.eval_calls.append((script, numkeys, args))
        return 1


def test_consume_once_moves_ready_payload_to_processing_and_acks_after_success(monkeypatch):
    payload = json.dumps({
        "jobId": "j1",
        "executionId": "e1",
        "kbId": "kb",
        "docId": "doc",
        "objectKey": "obj",
        "fileName": "a.txt",
        "mimeType": "text/plain",
    })
    redis = FakeRedis(payload)
    processed = []
    monkeypatch.setattr(main, "process_ingest_job", lambda job: processed.append(job))
    monkeypatch.setattr(main.settings, "ingest_queue", "ready")
    monkeypatch.setattr(main.settings, "ingest_processing_queue", "processing")

    assert main.consume_once(redis) is True

    assert redis.moves == [("ready", "processing", 5)]
    assert processed[0]["executionId"] == "e1"
    assert redis.acks == [("processing", 1, payload)]


def test_consume_once_keeps_processing_payload_when_worker_raises(monkeypatch):
    payload = json.dumps({
        "jobId": "j1",
        "executionId": "e1",
        "kbId": "kb",
        "docId": "doc",
        "objectKey": "obj",
        "fileName": "a.txt",
        "mimeType": "text/plain",
    })
    redis = FakeRedis(payload)

    def fail(_job):
        raise RuntimeError("boom")

    monkeypatch.setattr(main, "process_ingest_job", fail)
    monkeypatch.setattr(main.settings, "ingest_queue", "ready")
    monkeypatch.setattr(main.settings, "ingest_processing_queue", "processing")
    monkeypatch.setattr(main.settings, "redis_retry_delay_seconds", 0.25)
    sleeps = []
    monkeypatch.setattr(main.time, "sleep", lambda seconds: sleeps.append(seconds))

    assert main.consume_once(redis) is False

    assert redis.acks == []
    assert sleeps == [0.25]


def test_consume_once_discards_malformed_payload(monkeypatch):
    redis = FakeRedis("not-json")
    monkeypatch.setattr(main.settings, "ingest_queue", "ready")
    monkeypatch.setattr(main.settings, "ingest_processing_queue", "processing")

    assert main.consume_once(redis) is False

    assert redis.acks == [("processing", 1, "not-json")]


def test_reap_processing_entries_removes_only_malformed_missing_terminal_or_stale(monkeypatch):
    def payload(job_id, execution_id):
        return json.dumps({
            "jobId": job_id,
            "executionId": execution_id,
            "kbId": "kb",
            "docId": "doc",
            "objectKey": "obj",
            "fileName": "a.txt",
            "mimeType": "text/plain",
        })

    malformed = "not-json"
    missing_execution = json.dumps({"jobId": "invalid"})
    terminal = payload("terminal", "exec-terminal")
    stale = payload("stale", "exec-stale")
    missing = payload("missing", "exec-missing")
    active = payload("active", "exec-active")
    expired = payload("expired", "exec-expired")
    redis = FakeRedis(
        processing_entries=[
            malformed,
            missing_execution,
            terminal,
            stale,
            missing,
            active,
            expired,
        ]
    )
    states = {
        "terminal": {
            "status": "COMPLETED",
            "executionCurrent": True,
            "terminal": True,
            "leaseExpired": False,
        },
        "stale": {
            "status": "PROCESSING",
            "executionCurrent": False,
            "terminal": False,
            "leaseExpired": False,
        },
        "missing": {"missing": True},
        "active": {
            "status": "PROCESSING",
            "executionCurrent": True,
            "terminal": False,
            "leaseExpired": False,
        },
        "expired": {
            "status": "PROCESSING",
            "executionCurrent": True,
            "terminal": False,
            "leaseExpired": True,
        },
    }
    monkeypatch.setattr(
        main,
        "get_job_state",
        lambda job_id, execution_id: states[job_id],
    )
    monkeypatch.setattr(main.settings, "ingest_processing_queue", "processing")

    assert main.reap_processing_entries(redis) == 5

    assert redis.acks == [
        ("processing", 1, malformed),
        ("processing", 1, missing_execution),
        ("processing", 1, terminal),
        ("processing", 1, stale),
        ("processing", 1, missing),
    ]


def test_reap_processing_entries_leaves_payload_when_state_lookup_fails(monkeypatch):
    payload = json.dumps({
        "jobId": "job-1",
        "executionId": "exec-1",
        "kbId": "kb",
        "docId": "doc",
        "objectKey": "obj",
        "fileName": "a.txt",
        "mimeType": "text/plain",
    })
    redis = FakeRedis(processing_entries=[payload])
    monkeypatch.setattr(
        main,
        "get_job_state",
        lambda *_: (_ for _ in ()).throw(RuntimeError("api unavailable")),
    )

    assert main.reap_processing_entries(redis) == 0
    assert redis.acks == []


def test_reap_processing_entries_limits_each_scan_to_configured_batch(monkeypatch):
    def payload(job_id):
        return json.dumps({
            "jobId": job_id,
            "executionId": f"exec-{job_id}",
            "kbId": "kb",
            "docId": "doc",
            "objectKey": "obj",
            "fileName": "a.txt",
            "mimeType": "text/plain",
        })

    redis = FakeRedis(processing_entries=[payload("first"), payload("second"), payload("third")])
    inspected = []
    monkeypatch.setattr(
        main,
        "get_job_state",
        lambda job_id, _execution_id: inspected.append(job_id) or {
            "status": "PROCESSING",
            "executionCurrent": True,
            "terminal": False,
            "leaseExpired": False,
        },
    )
    monkeypatch.setattr(main.settings, "ingest_processing_queue", "processing")
    monkeypatch.setattr(main.settings, "ingest_processing_reap_batch_size", 2, raising=False)

    assert main.reap_processing_entries(redis) == 0
    assert inspected == ["second", "third"]
    assert redis.ranges == [("processing", -2, -1)]


def test_reap_processing_entries_atomically_requeues_current_queued_execution(monkeypatch):
    payload = json.dumps({
        "jobId": "queued",
        "executionId": "exec-queued",
        "kbId": "kb",
        "docId": "doc",
        "objectKey": "obj",
        "fileName": "a.txt",
        "mimeType": "text/plain",
    })
    redis = FakeRedis(processing_entries=[payload])
    monkeypatch.setattr(
        main,
        "get_job_state",
        lambda *_: {
            "status": "PENDING",
            "executionCurrent": True,
            "terminal": False,
            "leaseExpired": False,
            "requeueEligible": True,
        },
    )
    monkeypatch.setattr(main.settings, "ingest_queue", "ready")
    monkeypatch.setattr(main.settings, "ingest_processing_queue", "processing")

    assert main.reap_processing_entries(redis) == 1
    assert redis.acks == []
    assert len(redis.eval_calls) == 1
    _script, numkeys, args = redis.eval_calls[0]
    assert numkeys == 2
    assert args == ("processing", "ready", payload)


def test_redis_consumer_loop_survives_transient_redis_failure(monkeypatch):
    client = object()
    attempts = []
    outcomes = iter([RuntimeError("redis unavailable"), KeyboardInterrupt()])

    def consume_once(_client):
        attempts.append(_client)
        raise next(outcomes)

    sleeps = []
    monkeypatch.setattr(main.redis, "Redis", lambda **_: client)
    monkeypatch.setattr(main, "consume_once", consume_once)
    monkeypatch.setattr(main, "reap_processing_entries", lambda _: 0)
    monkeypatch.setattr(main.time, "sleep", lambda seconds: sleeps.append(seconds))
    monkeypatch.setattr(main.settings, "redis_retry_delay_seconds", 0.25)
    monkeypatch.setattr(main.settings, "ingest_processing_reap_interval_seconds", 60)

    with pytest.raises(KeyboardInterrupt):
        main.redis_consumer_loop()

    assert attempts == [client, client]
    assert sleeps == [0.25]
