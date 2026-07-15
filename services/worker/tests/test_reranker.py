from app.config import Settings
from app.reranker import RerankerLifecycle


class FakeModel:
    def __init__(self, scores=None):
        self.scores = scores or [1.0]
        self.calls = []

    def predict(self, pairs):
        self.calls.append(pairs)
        return self.scores


def test_warmup_loads_model_once_and_runs_inference():
    created = []
    model = FakeModel()

    def factory(name, cache_folder=None):
        created.append((name, cache_folder))
        return model

    lifecycle = RerankerLifecycle("small-model", "/cache", model_factory=factory)
    lifecycle.warmup()
    lifecycle.warmup()

    assert created == [("small-model", "/cache")]
    assert model.calls == [[["warmup", "warmup"]]]
    assert lifecycle.status().ready is True


def test_failed_warmup_is_reported_and_rerank_falls_back():
    def factory(name, cache_folder=None):
        raise RuntimeError("missing weights at /secret/cache")

    lifecycle = RerankerLifecycle("small-model", model_factory=factory)
    lifecycle.warmup()
    hits = [{"chunk_id": "a", "content": "A"}]

    assert lifecycle.status().ready is False
    assert lifecycle.status().error == "RuntimeError"
    assert lifecycle.rerank("query", hits, 1) == hits


def test_rerank_sorts_hits_and_adds_stage_evidence():
    lifecycle = RerankerLifecycle(
        "small-model", model_factory=lambda *args, **kwargs: FakeModel([0.1, 0.9])
    )
    lifecycle.warmup()
    hits = [
        {"chunk_id": "a", "content": "A"},
        {"chunk_id": "b", "content": "B"},
    ]

    result = lifecycle.rerank("query", hits, 2)

    assert [hit["chunk_id"] for hit in result] == ["b", "a"]
    assert result[0]["rerank_rank"] == 1
    assert result[0]["rerank_score"] == 0.9


def test_default_cache_uses_persistent_container_path(monkeypatch):
    monkeypatch.delenv("SENTENCE_TRANSFORMERS_HOME", raising=False)
    monkeypatch.delenv("HF_HOME", raising=False)

    assert Settings().rerank_cache_dir == "/models/huggingface"
