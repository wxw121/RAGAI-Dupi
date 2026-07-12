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
