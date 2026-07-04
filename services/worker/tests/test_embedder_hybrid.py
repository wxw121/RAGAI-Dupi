import pytest

from app import embedder as embedder_module
from app.embedder import Embedder
from app.retrieval import hybrid


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload


class FakeClient:
    def __init__(self, payloads):
        self.payloads = list(payloads)
        self.calls = []

    def post(self, path, json):
        self.calls.append((path, json))
        return FakeResponse(self.payloads.pop(0))


class FakeGetClient:
    def __init__(self, payload):
        self.payload = payload
        self.calls = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def get(self, path):
        self.calls.append(path)
        return FakeResponse(self.payload)


def test_embed_and_embed_batch_orders_by_provider_index(monkeypatch):
    client = FakeClient([
        {"data": [{"embedding": [1.0, 2.0]}]},
        {"data": [
            {"index": 1, "embedding": [2.0]},
            {"index": 0, "embedding": [1.0]},
        ]},
    ])
    monkeypatch.setattr(embedder_module.httpx, "Client", lambda **_: client)

    emb = Embedder(model="m")

    assert emb.embed("hello") == [1.0, 2.0]
    assert emb.embed_batch(["a", "b"]) == [[1.0], [2.0]]
    assert client.calls[1][1] == {"model": "m", "input": ["a", "b"]}


def test_embed_batch_validates_count_and_embedding_shape(monkeypatch):
    client = FakeClient([
        {"data": [{"embedding": [1.0]}]},
        {"data": [{"index": 0, "embedding": None}]},
    ])
    monkeypatch.setattr(embedder_module.httpx, "Client", lambda **_: client)
    emb = Embedder(model="m")

    with pytest.raises(ValueError, match="count mismatch"):
        emb.embed_batch(["a", "b"])
    with pytest.raises(ValueError, match="Embedding missing"):
        emb.embed_batch(["a"])
    assert emb.embed_batch([]) == []


def test_embed_batch_uses_response_order_when_index_missing(monkeypatch):
    client = FakeClient([{"data": [{"embedding": [1.0]}, {"embedding": [2.0]}]}])
    monkeypatch.setattr(embedder_module.httpx, "Client", lambda **_: client)

    assert Embedder(model="m").embed_batch(["a", "b"]) == [[1.0], [2.0]]


def test_tokenize_bm25_and_rrf_fusion():
    corpus = [
        {"chunk_id": "a", "content": "hello world"},
        {"chunk_id": "b", "content": "other"},
    ]

    assert hybrid.tokenize("Hello  WORLD") == ["hello", "world"]
    assert hybrid.bm25_search(corpus, "hello", 5)[0]["chunk_id"] == "a"
    fused = hybrid.rrf_fusion(
        [{"chunk_id": "a", "content": "v", "score": 0.1}],
        [{"chunk_id": "a", "content": "b", "score": 0.9}, {"chunk_id": "b", "content": "b", "score": 0.5}],
    )
    assert [hit["chunk_id"] for hit in fused] == ["a", "b"]
    assert fused[0]["score"] > fused[1]["score"]


def test_rerank_falls_back_or_sorts_by_model_score(monkeypatch):
    monkeypatch.setattr(hybrid, "get_reranker", lambda: None)
    hits = [{"chunk_id": "a", "content": "A"}, {"chunk_id": "b", "content": "B"}]
    assert hybrid.rerank_hits("q", hits, 1) == [hits[0]]
    assert hybrid.rerank_hits("q", [], 3) == []

    class FakeReranker:
        def predict(self, pairs):
            return [0.1, 0.9]

    monkeypatch.setattr(hybrid, "get_reranker", lambda: FakeReranker())
    assert hybrid.rerank_hits("q", hits, 2)[0]["chunk_id"] == "b"


def test_bm25_search_returns_empty_for_empty_corpus():
    assert hybrid.bm25_search([], "hello", 3) == []


def test_fetch_corpus_failure_returns_empty(monkeypatch):
    class BrokenClient:
        def __init__(self, **kwargs):
            pass

        def __enter__(self):
            raise RuntimeError("down")

        def __exit__(self, *args):
            return False

    monkeypatch.setattr(hybrid.httpx, "Client", BrokenClient)
    assert hybrid.fetch_kb_corpus("kb") == []


def test_get_reranker_caches_failure_and_fetch_corpus_success(monkeypatch):
    hybrid._reranker = None
    monkeypatch.setitem(__import__("sys").modules, "sentence_transformers", None)
    assert hybrid.get_reranker() is None
    assert hybrid.get_reranker() is None

    client = FakeGetClient([{"chunk_id": "c1", "content": "hello"}])
    monkeypatch.setattr(hybrid.httpx, "Client", lambda **_: client)
    assert hybrid.fetch_kb_corpus("kb") == [{"chunk_id": "c1", "content": "hello"}]
    assert client.calls == ["/api/v1/internal/knowledge-bases/kb/chunks"]


def test_hybrid_retrieve_fuses_vector_and_bm25_then_reranks(monkeypatch):
    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, query):
            return [0.1]

    class FakeIndexer:
        def __init__(self, dimension):
            self.dimension = dimension

        def search(self, kb_id, vector, top_k):
            return [{"chunk_id": "v1", "doc_id": "d", "content": "vector hello", "score": 0.8}]

    monkeypatch.setattr(hybrid, "Embedder", FakeEmbedder)
    monkeypatch.setattr(hybrid, "MilvusIndexer", FakeIndexer)
    monkeypatch.setattr(hybrid, "rerank_hits", lambda query, hits, top_k: [{**hits[-1], "score": 9.0}])
    corpus = [{"chunk_id": "b1", "doc_id": "d", "content": "hello bm25"}]

    result = hybrid.hybrid_retrieve("kb", "hello", 1, "m", 3, use_rerank=True, corpus_fetcher=lambda _: corpus)

    assert result == [{"chunk_id": "b1", "doc_id": "d", "content": "hello bm25", "score": 9.0}]


def test_hybrid_retrieve_without_rerank_fetches_default_corpus(monkeypatch):
    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, query):
            return [0.1]

    class FakeIndexer:
        def __init__(self, dimension):
            self.dimension = dimension

        def search(self, kb_id, vector, top_k):
            return [{"chunk_id": "v1", "doc_id": "d", "content": "vector hello", "score": 0.8}]

    monkeypatch.setattr(hybrid, "Embedder", FakeEmbedder)
    monkeypatch.setattr(hybrid, "MilvusIndexer", FakeIndexer)
    monkeypatch.setattr(hybrid, "fetch_kb_corpus", lambda kb_id: [{"chunk_id": "b1", "doc_id": "d", "content": "hello bm25"}])

    result = hybrid.hybrid_retrieve("kb", "hello", 2, "m", 3, use_rerank=False)

    assert [hit["chunk_id"] for hit in result] == ["v1", "b1"]
