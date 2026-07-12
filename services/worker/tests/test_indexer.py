import types

import pytest

from app import indexer as indexer_module
from app.indexer import MilvusIndexer


def test_existing_collection_does_not_load_during_initialization(monkeypatch):
    load_calls = []

    class FakeCollection:
        def __init__(self, name):
            self.name = name
            self.schema = types.SimpleNamespace(fields=[
                types.SimpleNamespace(name="embedding", params={"dim": 1024}),
            ])

        def load(self, timeout=None):
            load_calls.append(timeout)

    monkeypatch.setattr(indexer_module.settings, "milvus_collection", "dupi_chunks")
    monkeypatch.setattr(indexer_module.settings, "embedding_dimension", 1024)
    monkeypatch.setattr(indexer_module.utility, "has_collection", lambda name: True)
    monkeypatch.setattr(indexer_module, "Collection", FakeCollection)

    MilvusIndexer(dimension=1024)

    assert load_calls == []


def test_search_loads_collection_with_timeout(monkeypatch):
    load_calls = []
    search_calls = []

    class FakeEntity:
        def __init__(self, values):
            self.values = values

        def get(self, key):
            return self.values[key]

    class FakeHit:
        score = 0.75
        entity = FakeEntity({
            "chunk_id": "c1",
            "doc_id": "d1",
            "content": "hello",
        })

    class FakeCollection:
        def __init__(self, name):
            self.name = name
            self.schema = types.SimpleNamespace(fields=[
                types.SimpleNamespace(name="embedding", params={"dim": 1024}),
            ])

        def load(self, timeout=None):
            load_calls.append(timeout)

        def search(self, **kwargs):
            search_calls.append(kwargs)
            return [[FakeHit()]]

    monkeypatch.setattr(indexer_module.settings, "milvus_collection", "dupi_chunks")
    monkeypatch.setattr(indexer_module.settings, "embedding_dimension", 1024)
    monkeypatch.setattr(indexer_module.utility, "has_collection", lambda name: True)
    monkeypatch.setattr(indexer_module, "Collection", FakeCollection)

    results = MilvusIndexer(dimension=1024).search("kb", [0.1], 3)

    assert load_calls == [10]
    assert search_calls[0]["timeout"] == 10
    assert results == [{
        "chunk_id": "c1",
        "doc_id": "d1",
        "content": "hello",
        "score": 0.75,
    }]


def test_delete_by_doc_tolerates_unloaded_collection(monkeypatch):
    class FakeMilvusException(Exception):
        pass

    class FakeCollection:
        def __init__(self, name):
            self.name = name
            self.schema = types.SimpleNamespace(fields=[
                types.SimpleNamespace(name="embedding", params={"dim": 1024}),
            ])

        def delete(self, **kwargs):
            raise FakeMilvusException("collection not loaded")

    monkeypatch.setattr(indexer_module.settings, "milvus_collection", "dupi_chunks")
    monkeypatch.setattr(indexer_module.settings, "embedding_dimension", 1024)
    monkeypatch.setattr(indexer_module.utility, "has_collection", lambda name: True)
    monkeypatch.setattr(indexer_module, "Collection", FakeCollection)
    monkeypatch.setattr(indexer_module, "MilvusException", FakeMilvusException)

    MilvusIndexer(dimension=1024).delete_by_doc("doc")


def test_delete_by_doc_tolerates_partially_loaded_collection(monkeypatch):
    class FakeMilvusException(Exception):
        pass

    class FakeCollection:
        def __init__(self, name):
            self.name = name
            self.schema = types.SimpleNamespace(fields=[
                types.SimpleNamespace(name="embedding", params={"dim": 1024}),
            ])

        def delete(self, **kwargs):
            raise FakeMilvusException("collection not fully loaded")

    monkeypatch.setattr(indexer_module.settings, "milvus_collection", "dupi_chunks")
    monkeypatch.setattr(indexer_module.settings, "embedding_dimension", 1024)
    monkeypatch.setattr(indexer_module.utility, "has_collection", lambda name: True)
    monkeypatch.setattr(indexer_module, "Collection", FakeCollection)
    monkeypatch.setattr(indexer_module, "MilvusException", FakeMilvusException)

    MilvusIndexer(dimension=1024).delete_by_doc("doc")


def test_delete_by_doc_tolerates_timestamp_lag(monkeypatch):
    class FakeMilvusException(Exception):
        pass

    class FakeCollection:
        def __init__(self, name):
            self.name = name
            self.schema = types.SimpleNamespace(fields=[
                types.SimpleNamespace(name="embedding", params={"dim": 1024}),
            ])

        def delete(self, **kwargs):
            raise FakeMilvusException(
                "failed to search/query delegator 7 for channel x: Timestamp lag too large"
            )

    monkeypatch.setattr(indexer_module.settings, "milvus_collection", "dupi_chunks")
    monkeypatch.setattr(indexer_module.settings, "embedding_dimension", 1024)
    monkeypatch.setattr(indexer_module.utility, "has_collection", lambda name: True)
    monkeypatch.setattr(indexer_module, "Collection", FakeCollection)
    monkeypatch.setattr(indexer_module, "MilvusException", FakeMilvusException)

    MilvusIndexer(dimension=1024).delete_by_doc("doc")


def test_delete_by_doc_reraises_other_milvus_errors(monkeypatch):
    class FakeMilvusException(Exception):
        pass

    class FakeCollection:
        def __init__(self, name):
            self.name = name
            self.schema = types.SimpleNamespace(fields=[
                types.SimpleNamespace(name="embedding", params={"dim": 1024}),
            ])

        def delete(self, **kwargs):
            raise FakeMilvusException("permission denied")

    monkeypatch.setattr(indexer_module.settings, "milvus_collection", "dupi_chunks")
    monkeypatch.setattr(indexer_module.settings, "embedding_dimension", 1024)
    monkeypatch.setattr(indexer_module.utility, "has_collection", lambda name: True)
    monkeypatch.setattr(indexer_module, "Collection", FakeCollection)
    monkeypatch.setattr(indexer_module, "MilvusException", FakeMilvusException)

    with pytest.raises(FakeMilvusException, match="permission denied"):
        MilvusIndexer(dimension=1024).delete_by_doc("doc")


def test_index_chunks_does_not_wait_for_flush(monkeypatch):
    calls = []

    class FakeCollection:
        def __init__(self, name):
            self.name = name
            self.schema = types.SimpleNamespace(fields=[
                types.SimpleNamespace(name="embedding", params={"dim": 1024}),
            ])

        def insert(self, rows):
            calls.append(("insert", rows))

        def flush(self, timeout=None):
            calls.append(("flush", timeout))

    monkeypatch.setattr(indexer_module.settings, "milvus_collection", "dupi_chunks")
    monkeypatch.setattr(indexer_module.settings, "embedding_dimension", 1024)
    monkeypatch.setattr(indexer_module.utility, "has_collection", lambda name: True)
    monkeypatch.setattr(indexer_module, "Collection", FakeCollection)

    chunk = types.SimpleNamespace(id="c1", content="hello", milvus_id=None)
    result = MilvusIndexer(dimension=1024).index_chunks("kb", "doc", [chunk], [[0.1] * 1024])

    assert result == ["c1"]
    assert chunk.milvus_id == "c1"
    assert [call[0] for call in calls] == ["insert"]
