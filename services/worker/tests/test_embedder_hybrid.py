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


def test_hybrid_retrieve_excludes_parent_chunks_from_bm25_entries(monkeypatch):
    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, query):
            return [0.1]

    class FakeIndexer:
        def __init__(self, dimension, collection_name=None, profile_schema=False):
            self.dimension = dimension

        def search_profile(self, kb_id, vector, top_k, profile, entry_kind=None):
            return []

    corpus = [
        {
            "chunk_id": "parent",
            "doc_id": "d",
            "content": "target phrase",
            "metadata": {"chunk_role": "parent", "profile_scope": ["parent-child"]},
        },
        {
            "chunk_id": "child",
            "doc_id": "d",
            "content": "target phrase",
            "metadata": {
                "chunk_role": "child",
                "parent_chunk_id": "parent",
                "profile_scope": ["parent-child"],
            },
        },
    ]
    monkeypatch.setattr(hybrid, "Embedder", FakeEmbedder)
    monkeypatch.setattr(hybrid, "MilvusIndexer", FakeIndexer)

    result = hybrid.hybrid_retrieve(
        "kb",
        "target phrase",
        5,
        "m",
        3,
        retrieval_profile="parent-child",
        profile_index_ready=True,
        corpus_fetcher=lambda _: corpus,
    )

    assert [hit["chunk_id"] for hit in result] == ["child"]


def test_hybrid_retrieve_attaches_retrieval_profile_metadata(monkeypatch):
    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, query):
            return [0.1]

    class FakeIndexer:
        def __init__(self, dimension, collection_name=None, profile_schema=False):
            self.dimension = dimension

        def search_profile(self, kb_id, vector, top_k, profile, entry_kind=None):
            return [{"chunk_id": "v1", "doc_id": "d", "content": "vector hello", "score": 0.8}]

    monkeypatch.setattr(hybrid, "Embedder", FakeEmbedder)
    monkeypatch.setattr(hybrid, "MilvusIndexer", FakeIndexer)

    result = hybrid.hybrid_retrieve(
        "kb",
        "hello",
        1,
        "m",
        3,
        retrieval_profile="qa-assisted",
        profile_index_ready=True,
    )

    assert result[0]["metadata"]["retrieval_profile"] == "qa-assisted"


def test_filter_profile_corpus_isolates_classic_parent_child_and_qa_entries():
    corpus = [
        {
            "chunk_id": "classic",
            "content": "classic",
            "metadata": {"entry_kind": "original", "profile_scope": ["classic", "qa-assisted"]},
        },
        {
            "chunk_id": "child",
            "content": "child",
            "metadata": {"entry_kind": "child", "profile_scope": ["parent-child", "combined"]},
        },
        {
            "chunk_id": "qa-assisted",
            "content": "qa assisted",
            "metadata": {"entry_kind": "qa", "profile_scope": ["qa-assisted"]},
        },
        {
            "chunk_id": "qa-combined",
            "content": "qa combined",
            "metadata": {"entry_kind": "qa", "profile_scope": ["combined"]},
        },
        {
            "chunk_id": "parent",
            "content": "parent",
            "metadata": {"entry_kind": "parent", "profile_scope": ["parent-child", "combined"]},
        },
    ]

    assert [hit["chunk_id"] for hit in hybrid.filter_profile_corpus(corpus, "classic", True)] == ["classic"]
    assert [hit["chunk_id"] for hit in hybrid.filter_profile_corpus(corpus, "parent-child", True)] == ["child"]
    assert [hit["chunk_id"] for hit in hybrid.filter_profile_corpus(corpus, "qa-assisted", True)] == [
        "classic",
        "qa-assisted",
    ]
    assert [hit["chunk_id"] for hit in hybrid.filter_profile_corpus(corpus, "combined", True, "qa")] == [
        "qa-combined",
    ]
    assert [hit["chunk_id"] for hit in hybrid.filter_profile_corpus(corpus, "classic", False)] == ["classic"]


def test_weighted_rrf_combines_routes_and_validates_parameters():
    fused = hybrid.weighted_rrf([
        (1.0, [{"chunk_id": "child-a", "content": "A"}, {"chunk_id": "child-b", "content": "B"}]),
        (0.8, [{"chunk_id": "qa-a", "content": "QA"}]),
        (1.0, []),
    ], k=60)

    assert [hit["chunk_id"] for hit in fused] == ["child-a", "child-b", "qa-a"]
    assert fused[0]["score"] == pytest.approx(1.0 / 61.0)
    with pytest.raises(ValueError, match="RRF K"):
        hybrid.weighted_rrf([], k=0)
    with pytest.raises(ValueError, match="weight"):
        hybrid.weighted_rrf([(0, [])], k=60)


def test_combined_hybrid_uses_four_weighted_routes_then_reranks(monkeypatch):
    calls = []
    rerank_inputs = []

    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, query):
            return [0.1]

    class FakeIndexer:
        def __init__(self, dimension, collection_name=None, profile_schema=False):
            calls.append(("init", dimension, collection_name, profile_schema))

        def search_profile(self, kb_id, vector, top_k, profile, entry_kind=None):
            calls.append(("search", profile, entry_kind, top_k))
            return [{
                "chunk_id": f"{entry_kind}-vector",
                "doc_id": "d",
                "content": f"{entry_kind} vector",
                "score": 0.9,
            }]

    corpus = [
        {
            "chunk_id": "child-bm25",
            "doc_id": "d",
            "content": "child target",
            "metadata": {"entry_kind": "child", "profile_scope": ["combined"]},
        },
        {
            "chunk_id": "qa-bm25",
            "doc_id": "d",
            "content": "qa target",
            "metadata": {"entry_kind": "qa", "profile_scope": ["combined"]},
        },
    ]

    def fake_bm25(filtered, query, top_k):
        calls.append(("bm25", filtered[0]["metadata"]["entry_kind"], top_k))
        return [{**filtered[0], "score": 0.5}] if filtered else []

    def fake_rerank(query, hits, top_k):
        rerank_inputs.append([hit["chunk_id"] for hit in hits])
        return hits[:top_k]

    monkeypatch.setattr(hybrid, "Embedder", FakeEmbedder)
    monkeypatch.setattr(hybrid, "MilvusIndexer", FakeIndexer)
    monkeypatch.setattr(hybrid, "bm25_search", fake_bm25)
    monkeypatch.setattr(hybrid, "rerank_hits", fake_rerank)
    monkeypatch.setattr(hybrid.settings, "combined_child_weight", 1.0)
    monkeypatch.setattr(hybrid.settings, "combined_qa_weight", 0.8)
    monkeypatch.setattr(hybrid.settings, "rrf_k", 60)
    monkeypatch.setattr(hybrid.settings, "milvus_profile_collection", "profiles-v2")

    result = hybrid.hybrid_retrieve(
        "kb",
        "target",
        4,
        "m",
        3,
        use_rerank=True,
        retrieval_profile="combined",
        profile_index_ready=True,
        corpus_fetcher=lambda _: corpus,
    )

    assert calls == [
        ("init", 3, "profiles-v2", True),
        ("search", "combined", "child", 8),
        ("search", "combined", "qa", 8),
        ("bm25", "child", 8),
        ("bm25", "qa", 8),
    ]
    assert rerank_inputs == [["child-vector", "child-bm25", "qa-vector", "qa-bm25"]]
    assert [hit["chunk_id"] for hit in result] == rerank_inputs[0]


def test_combined_hybrid_preserves_child_results_when_qa_routes_are_empty(monkeypatch):
    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, query):
            return [0.1]

    class FakeIndexer:
        def __init__(self, dimension, collection_name=None, profile_schema=False):
            pass

        def search_profile(self, kb_id, vector, top_k, profile, entry_kind=None):
            if entry_kind == "qa":
                return []
            return [{"chunk_id": "child-vector", "doc_id": "d", "content": "child", "score": 0.9}]

    monkeypatch.setattr(hybrid, "Embedder", FakeEmbedder)
    monkeypatch.setattr(hybrid, "MilvusIndexer", FakeIndexer)

    result = hybrid.hybrid_retrieve(
        "kb",
        "missing",
        3,
        "m",
        3,
        retrieval_profile="combined",
        profile_index_ready=True,
        corpus_fetcher=lambda _: [],
    )

    assert [hit["chunk_id"] for hit in result] == ["child-vector"]


def test_not_ready_classic_hybrid_uses_legacy_search(monkeypatch):
    calls = []

    class FakeEmbedder:
        def __init__(self, model):
            self.model = model

        def embed(self, query):
            return [0.1]

    class FakeIndexer:
        def __init__(self, dimension):
            calls.append(("init", dimension))

        def search(self, kb_id, vector, top_k):
            calls.append(("search", kb_id, top_k))
            return [{"chunk_id": "legacy", "doc_id": "d", "content": "legacy", "score": 0.9}]

    monkeypatch.setattr(hybrid, "Embedder", FakeEmbedder)
    monkeypatch.setattr(hybrid, "MilvusIndexer", FakeIndexer)

    result = hybrid.hybrid_retrieve(
        "kb",
        "target",
        1,
        "m",
        3,
        retrieval_profile="classic",
        profile_index_ready=False,
        corpus_fetcher=lambda _: [],
    )

    assert calls == [("init", 3), ("search", "kb", 2)]
    assert [hit["chunk_id"] for hit in result] == ["legacy"]
