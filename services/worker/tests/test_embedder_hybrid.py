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

    def get(self, path, headers=None):
        self.calls.append((path, headers or {}))
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


def test_embed_batch_uses_configured_chunk_size(monkeypatch):
    client = FakeClient([
        {"data": [
            {"index": 0, "embedding": [1.0]},
            {"index": 1, "embedding": [2.0]},
        ]},
        {"data": [
            {"index": 0, "embedding": [3.0]},
            {"index": 1, "embedding": [4.0]},
        ]},
        {"data": [{"index": 0, "embedding": [5.0]}]},
    ])
    monkeypatch.setattr(embedder_module.httpx, "Client", lambda **_: client)
    monkeypatch.setattr(embedder_module.settings, "embedding_batch_size", 2)

    assert Embedder(model="m").embed_batch(["a", "b", "c", "d", "e"]) == [
        [1.0],
        [2.0],
        [3.0],
        [4.0],
        [5.0],
    ]
    assert [call[1]["input"] for call in client.calls] == [["a", "b"], ["c", "d"], ["e"]]


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
    assert client.calls == [("/api/v1/internal/knowledge-bases/kb/chunks", {})]


def test_fetch_corpus_sends_internal_key_when_configured(monkeypatch):
    client = FakeGetClient([{"chunk_id": "c1", "content": "hello"}])
    monkeypatch.setattr(hybrid.httpx, "Client", lambda **_: client)
    monkeypatch.setattr(hybrid.settings, "dupi_internal_key", "secret")

    assert hybrid.fetch_kb_corpus("kb") == [{"chunk_id": "c1", "content": "hello"}]
    assert client.calls == [
        ("/api/v1/internal/knowledge-bases/kb/chunks", {"X-Dupi-Internal-Key": "secret"}),
    ]


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

    assert result[0] == {
        "chunk_id": "b1", "doc_id": "d", "content": "hello bm25", "score": 9.0,
        "sparse_rank": 1, "fusion_score": 1 / 61, "fusion_rank": 2,
    }


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


def test_hybrid_retrieve_applies_profile_limits_and_stage_ranks(monkeypatch):
    calls = {}

    class FakeEmbedder:
        def __init__(self, model):
            pass

        def embed(self, query):
            return [0.1]

    class FakeIndexer:
        def __init__(self, dimension):
            pass

        def search(self, kb_id, vector, top_k):
            calls["vector_limit"] = top_k
            return [{"chunk_id": "same", "doc_id": "d", "content": "hello", "score": 0.8}]

    monkeypatch.setattr(hybrid, "Embedder", FakeEmbedder)
    monkeypatch.setattr(hybrid, "MilvusIndexer", FakeIndexer)
    original_bm25 = hybrid.bm25_search

    def tracked_bm25(corpus, query, top_k, index_params=None, search_params=None):
        calls["sparse_limit"] = top_k
        calls["index_params"] = index_params
        calls["search_params"] = search_params
        return original_bm25(corpus, query, top_k, index_params, search_params)

    monkeypatch.setattr(hybrid, "bm25_search", tracked_bm25)

    result = hybrid.hybrid_retrieve(
        "kb", "hello", 5, "m", 3, use_rerank=False,
        corpus_fetcher=lambda _: [{"chunk_id": "same", "doc_id": "d", "content": "hello"}],
        vector_candidate_count=30, sparse_candidate_count=20, rrf_constant=42,
        rerank_candidate_limit=10, final_top_k=3,
        sparse_index_params={"bm25_k1": 1.8}, sparse_search_params={"drop_ratio_search": 0.1},
    )

    assert calls == {
        "vector_limit": 30, "sparse_limit": 20,
        "index_params": {"bm25_k1": 1.8}, "search_params": {"drop_ratio_search": 0.1},
    }
    assert result[0]["vector_rank"] == 1
    assert result[0]["sparse_rank"] == 1
    assert result[0]["fusion_rank"] == 1
    assert result[0]["fusion_score"] == 2 / 43
