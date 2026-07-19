import types
import uuid

import pytest

from app import indexer as indexer_module
from app.indexer import MilvusIndexer
from app.models import TextChunk


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


def test_profile_collection_creation_includes_filter_fields(monkeypatch):
    created = {}

    class FakeCollection:
        def __init__(self, name, schema=None):
            self.name = name
            self.schema = schema
            created["collection"] = self

        def create_index(self, **kwargs):
            created["index"] = kwargs

    monkeypatch.setattr(indexer_module.utility, "has_collection", lambda name: False)
    monkeypatch.setattr(indexer_module, "Collection", FakeCollection)
    monkeypatch.setattr(indexer_module.DataType, "BOOL", "BOOL", raising=False)
    monkeypatch.setattr(
        indexer_module,
        "FieldSchema",
        lambda **kwargs: types.SimpleNamespace(**kwargs),
    )
    monkeypatch.setattr(
        indexer_module,
        "CollectionSchema",
        lambda fields, description: types.SimpleNamespace(
            fields=fields,
            description=description,
        ),
    )

    MilvusIndexer(
        dimension=1024,
        collection_name="dupi_chunks_profiles_v2",
        profile_schema=True,
    )

    assert {field.name for field in created["collection"].schema.fields} == {
        "chunk_id",
        "kb_id",
        "doc_id",
        "content",
        "entry_kind",
        "profile_classic",
        "profile_parent_child",
        "profile_qa_assisted",
        "profile_combined",
        "embedding",
    }


def test_index_profile_chunks_derives_profile_flags(monkeypatch):
    inserted = []

    class FakeCollection:
        def __init__(self, name):
            self.name = name
            self.schema = profile_schema(1024)

        def insert(self, rows):
            inserted.append(rows)

    monkeypatch.setattr(indexer_module.utility, "has_collection", lambda name: True)
    monkeypatch.setattr(indexer_module, "Collection", FakeCollection)
    chunk = TextChunk(
        "c1",
        0,
        "hello",
        1,
        {
            "entry_kind": "child",
            "profile_scope": ["parent-child", "combined"],
        },
    )

    MilvusIndexer(
        dimension=1024,
        collection_name="dupi_chunks_profiles_v2",
        profile_schema=True,
    ).index_profile_chunks("kb", "doc", [chunk], [[0.1] * 1024])

    assert inserted[0][4:9] == [
        ["child"],
        [False],
        [True],
        [False],
        [True],
    ]


def test_search_profile_builds_whitelisted_expression(monkeypatch):
    search_calls = []

    class FakeCollection:
        def __init__(self, name):
            self.name = name
            self.schema = profile_schema(1024)

        def load(self, timeout=None):
            pass

        def search(self, **kwargs):
            search_calls.append(kwargs)
            return [[]]

    monkeypatch.setattr(indexer_module.utility, "has_collection", lambda name: True)
    monkeypatch.setattr(indexer_module, "Collection", FakeCollection)
    kb_id = str(uuid.uuid4())
    indexer = MilvusIndexer(
        dimension=1024,
        collection_name="dupi_chunks_profiles_v2",
        profile_schema=True,
    )

    assert indexer.search_profile(kb_id, [0.1], 3, "combined", "qa") == []
    assert search_calls[0]["expr"] == (
        f'kb_id == "{kb_id}" and profile_combined == true and entry_kind == "qa"'
    )

    with pytest.raises(ValueError, match="Unsupported retrieval profile"):
        indexer.search_profile(kb_id, [0.1], 3, "combined or true", None)
    with pytest.raises(ValueError, match="Unsupported entry kind"):
        indexer.search_profile(kb_id, [0.1], 3, "combined", 'qa" or true')


def profile_schema(dimension):
    names = [
        "chunk_id",
        "kb_id",
        "doc_id",
        "content",
        "entry_kind",
        "profile_classic",
        "profile_parent_child",
        "profile_qa_assisted",
        "profile_combined",
    ]
    fields = [types.SimpleNamespace(name=name, params={}) for name in names]
    fields.append(types.SimpleNamespace(name="embedding", params={"dim": dimension}))
    return types.SimpleNamespace(fields=fields)
