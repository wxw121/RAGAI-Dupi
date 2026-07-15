from types import SimpleNamespace

from app.retrieval.sparse import SparseMilvusAdapter


class FakeCollection:
    def __init__(self):
        self.search_args = None

    def load(self, timeout):
        self.timeout = timeout

    def search(self, **kwargs):
        self.search_args = kwargs
        entity = SimpleNamespace(get=lambda key: {
            "chunk_id": "chunk-1", "doc_id": "doc-1", "content": "hello"
        }[key])
        return [[SimpleNamespace(entity=entity, score=0.75)]]


def test_sparse_adapter_returns_stage_rank_without_vector_payload():
    adapter = SparseMilvusAdapter.__new__(SparseMilvusAdapter)
    adapter.collection = FakeCollection()

    result = adapter.search("kb-1", "hello", 5, {"drop_ratio_search": 0.1})

    assert result[0]["sparse_rank"] == 1
    assert "embedding" not in result[0]
    assert adapter.collection.search_args["anns_field"] == "sparse_embedding"
    assert adapter.collection.search_args["data"] == ["hello"]
    assert adapter.collection.search_args["param"]["metric_type"] == "BM25"
    assert adapter.collection.search_args["expr"] == 'kb_id == "kb-1"'
