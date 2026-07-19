from app import callback


class FakeResponse:
    def raise_for_status(self):
        return None


class FakePostClient:
    def __init__(self):
        self.calls = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def post(self, path, json, headers=None):
        self.calls.append((path, json, headers or {}))
        return FakeResponse()


def test_post_status_omits_internal_key_when_not_configured(monkeypatch):
    client = FakePostClient()
    monkeypatch.setattr(callback.httpx, "Client", lambda **_: client)
    monkeypatch.setattr(callback.settings, "dupi_internal_key", "")

    callback.post_status({"jobId": "j1", "status": "COMPLETED"})

    assert client.calls == [
        ("/api/v1/internal/ingest/status", {"jobId": "j1", "status": "COMPLETED"}, {}),
    ]


def test_post_status_sends_internal_key_when_configured(monkeypatch):
    client = FakePostClient()
    monkeypatch.setattr(callback.httpx, "Client", lambda **_: client)
    monkeypatch.setattr(callback.settings, "dupi_internal_key", "secret")

    callback.post_status({"jobId": "j1", "status": "COMPLETED"})

    assert client.calls == [
        (
            "/api/v1/internal/ingest/status",
            {"jobId": "j1", "status": "COMPLETED"},
            {"X-Dupi-Internal-Key": "secret"},
        ),
    ]


def test_claim_job_posts_execution_and_worker_lease(monkeypatch):
    client = FakePostClient()
    monkeypatch.setattr(callback.httpx, "Client", lambda **_: client)
    monkeypatch.setattr(callback.settings, "dupi_internal_key", "secret")

    callback.claim_job("job-1", "exec-1", "worker-a", 45)

    assert client.calls == [
        (
            "/api/v1/internal/ingest/jobs/job-1/claim",
            {"executionId": "exec-1", "workerId": "worker-a", "leaseSeconds": 45},
            {"X-Dupi-Internal-Key": "secret"},
        ),
    ]


def test_refresh_lease_posts_current_execution(monkeypatch):
    client = FakePostClient()
    monkeypatch.setattr(callback.httpx, "Client", lambda **_: client)
    monkeypatch.setattr(callback.settings, "dupi_internal_key", "")

    callback.refresh_lease("job-1", "exec-1", "worker-a", 30)

    assert client.calls == [
        (
            "/api/v1/internal/ingest/jobs/job-1/lease",
            {"executionId": "exec-1", "workerId": "worker-a", "leaseSeconds": 30},
            {},
        ),
    ]


def test_check_cancelled_reads_api_response(monkeypatch):
    class FakeGetClient(FakePostClient):
        def get(self, path, params=None, headers=None):
            self.calls.append((path, params, headers or {}))

            class Response(FakeResponse):
                def json(self):
                    return {"cancelled": True}

            return Response()

    client = FakeGetClient()
    monkeypatch.setattr(callback.httpx, "Client", lambda **_: client)
    monkeypatch.setattr(callback.settings, "dupi_internal_key", "secret")

    assert callback.check_cancelled("job-1", "exec-1") is True

    assert client.calls == [
        (
            "/api/v1/internal/ingest/jobs/job-1/cancelled",
            {"executionId": "exec-1"},
            {"X-Dupi-Internal-Key": "secret"},
        ),
    ]


def test_get_job_state_reads_execution_state_contract(monkeypatch):
    class FakeGetClient(FakePostClient):
        def get(self, path, headers=None):
            self.calls.append((path, headers or {}))

            class Response(FakeResponse):
                status_code = 200

                def json(self):
                    return {
                        "status": "PROCESSING",
                        "executionCurrent": True,
                        "terminal": False,
                        "leaseExpired": False,
                    }

            return Response()

    client = FakeGetClient()
    monkeypatch.setattr(callback.httpx, "Client", lambda **_: client)
    monkeypatch.setattr(callback.settings, "dupi_internal_key", "secret")

    state = callback.get_job_state("job-1", "exec-1")

    assert state == {
        "status": "PROCESSING",
        "executionCurrent": True,
        "terminal": False,
        "leaseExpired": False,
    }
    assert client.calls == [
        (
            "/api/v1/internal/ingest/jobs/job-1/executions/exec-1/state",
            {"X-Dupi-Internal-Key": "secret"},
        ),
    ]


def test_get_job_state_marks_missing_jobs_as_reapable(monkeypatch):
    class FakeGetClient(FakePostClient):
        def get(self, path, headers=None):
            self.calls.append((path, headers or {}))

            class Response(FakeResponse):
                status_code = 404

                def json(self):
                    return {"message": "missing"}

            return Response()

    client = FakeGetClient()
    monkeypatch.setattr(callback.httpx, "Client", lambda **_: client)

    assert callback.get_job_state("missing", "exec-1") == {"missing": True}
