import json
import uuid

from pymilvus import (
    Collection,
    CollectionSchema,
    DataType,
    FieldSchema,
    connections,
    utility,
)
from pymilvus.exceptions import MilvusException

from app.config import settings
from app.models import TextChunk

MILVUS_OPERATION_TIMEOUT_SECONDS = 10
DELETE_BATCH_SIZE = 256
VECTOR_ID_NAMESPACE = uuid.UUID("bce98f18-8f7d-5a6c-a52d-2f544582584c")
PROFILE_FIELDS = {
    "classic": "profile_classic",
    "parent-child": "profile_parent_child",
    "qa-assisted": "profile_qa_assisted",
    "combined": "profile_combined",
}
ENTRY_KINDS = {"original", "child", "qa"}
PROFILE_SCHEMA_FIELDS = {
    "chunk_id",
    "kb_id",
    "doc_id",
    "content",
    "entry_kind",
    *PROFILE_FIELDS.values(),
    "embedding",
}


def scope_chunk_ids(chunks: list[TextChunk], execution_id: str | None, doc_id: str) -> list[str]:
    if not execution_id:
        return [chunk.id for chunk in chunks]

    execution_namespace = uuid.uuid5(VECTOR_ID_NAMESPACE, f"{execution_id}\0{doc_id}")
    for chunk in chunks:
        chunk.id = str(uuid.uuid5(execution_namespace, str(chunk.chunk_index)))
    return [chunk.id for chunk in chunks]


def _chunk_ids_expr(chunk_ids: list[str]) -> str:
    return f"chunk_id in {json.dumps(chunk_ids, ensure_ascii=True)}"


class MilvusIndexer:
    def __init__(
        self,
        dimension: int | None = None,
        collection_name: str | None = None,
        profile_schema: bool = False,
    ):
        self.dimension = dimension or settings.embedding_dimension
        connections.connect(alias="default", host=settings.milvus_host, port=settings.milvus_port)
        self.collection_name = collection_name or settings.milvus_collection
        self.profile_schema = profile_schema
        self._ensure_collection()

    def _ensure_collection(self):
        if utility.has_collection(self.collection_name):
            self.collection = Collection(self.collection_name)
            self._validate_schema()
            return

        fields = [
            FieldSchema(name="chunk_id", dtype=DataType.VARCHAR, is_primary=True, max_length=64),
            FieldSchema(name="kb_id", dtype=DataType.VARCHAR, max_length=64),
            FieldSchema(name="doc_id", dtype=DataType.VARCHAR, max_length=64),
            FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=65535),
        ]
        if self.profile_schema:
            fields.extend([
                FieldSchema(name="entry_kind", dtype=DataType.VARCHAR, max_length=32),
                FieldSchema(name="profile_classic", dtype=DataType.BOOL),
                FieldSchema(name="profile_parent_child", dtype=DataType.BOOL),
                FieldSchema(name="profile_qa_assisted", dtype=DataType.BOOL),
                FieldSchema(name="profile_combined", dtype=DataType.BOOL),
            ])
        fields.append(FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=self.dimension))
        schema = CollectionSchema(fields, description="dupi-RAG chunks")
        self.collection = Collection(self.collection_name, schema)
        self.collection.create_index(
            field_name="embedding",
            index_params={"index_type": "HNSW", "metric_type": "COSINE", "params": {"M": 16, "efConstruction": 200}},
        )

    def _validate_schema(self):
        field_names = {field.name for field in self.collection.schema.fields}
        if self.profile_schema:
            missing = PROFILE_SCHEMA_FIELDS - field_names
            if missing:
                raise ValueError(
                    f"Milvus collection {self.collection_name} missing profile fields: "
                    + ", ".join(sorted(missing))
                )
        for field in self.collection.schema.fields:
            if field.name == "embedding":
                actual = int(field.params.get("dim", 0))
                if actual != self.dimension:
                    raise ValueError(
                        f"Milvus collection {self.collection_name} dimension mismatch: "
                        f"expected={self.dimension} actual={actual}"
                    )
                return
        raise ValueError(f"Milvus collection {self.collection_name} missing embedding field")

    def _load_collection(self):
        self.collection.load(timeout=MILVUS_OPERATION_TIMEOUT_SECONDS)

    def index_chunks(
        self,
        kb_id: str,
        doc_id: str,
        chunks: list[TextChunk],
        vectors: list[list[float]],
        sparse_profile_version: int = 0,
    ) -> list[str]:
        if not chunks:
            return []

        chunk_ids = [c.id for c in chunks]
        kb_ids = [kb_id] * len(chunks)
        doc_ids = [doc_id] * len(chunks)
        contents = [c.content[:65000] for c in chunks]

        self.collection.insert([chunk_ids, kb_ids, doc_ids, contents, vectors])

        for chunk, cid in zip(chunks, chunk_ids):
            chunk.milvus_id = cid
        if sparse_profile_version > 0:
            from app.retrieval.sparse import SparseMilvusAdapter
            SparseMilvusAdapter(kb_id, sparse_profile_version).upsert(kb_id, doc_id, chunks)
        return chunk_ids

    def index_profile_chunks(
        self,
        kb_id: str,
        doc_id: str,
        chunks: list[TextChunk],
        vectors: list[list[float]],
    ) -> list[str]:
        if not self.profile_schema:
            raise ValueError("Profile chunks require a profile-schema collection")
        if not chunks:
            return []

        chunk_ids = [chunk.id for chunk in chunks]
        entry_kinds = []
        profile_flags = {profile: [] for profile in PROFILE_FIELDS}
        for chunk in chunks:
            entry_kind = str(chunk.metadata.get("entry_kind") or "").strip().lower()
            if entry_kind not in ENTRY_KINDS:
                raise ValueError(f"Unsupported entry kind: {entry_kind}")
            scope = {
                str(profile).strip().lower()
                for profile in chunk.metadata.get("profile_scope", [])
            }
            unsupported = scope - PROFILE_FIELDS.keys()
            if unsupported:
                raise ValueError(f"Unsupported retrieval profile: {sorted(unsupported)[0]}")
            entry_kinds.append(entry_kind)
            for profile in PROFILE_FIELDS:
                profile_flags[profile].append(profile in scope)

        self.collection.insert([
            chunk_ids,
            [kb_id] * len(chunks),
            [doc_id] * len(chunks),
            [chunk.content[:65000] for chunk in chunks],
            entry_kinds,
            profile_flags["classic"],
            profile_flags["parent-child"],
            profile_flags["qa-assisted"],
            profile_flags["combined"],
            vectors,
        ])
        for chunk, chunk_id in zip(chunks, chunk_ids):
            chunk.milvus_id = chunk_id
        return chunk_ids

    def _delete_dense(self, expression: str, strict: bool):
        try:
            self.collection.delete(expr=expression, timeout=MILVUS_OPERATION_TIMEOUT_SECONDS)
        except MilvusException as exc:
            error = str(exc).lower()
            if (
                not strict
                and (
                    "collection not loaded" in error
                    or "collection not fully loaded" in error
                    or "timestamp lag too large" in error
                    or "failed to search/query delegator" in error
                )
            ):
                return
            raise

    def delete_by_doc(
        self,
        doc_id: str,
        kb_id: str = "",
        sparse_profile_version: int = 0,
        strict: bool = False,
    ):
        self._delete_dense(f'doc_id == "{doc_id}"', strict)
        if sparse_profile_version > 0:
            from app.retrieval.sparse import SparseMilvusAdapter
            SparseMilvusAdapter(kb_id, sparse_profile_version).delete_by_doc(doc_id)

    def delete_by_ids(
        self,
        chunk_ids: list[str],
        kb_id: str = "",
        sparse_profile_version: int = 0,
        strict: bool = False,
    ):
        owned_ids = list(dict.fromkeys(chunk_ids))
        for offset in range(0, len(owned_ids), DELETE_BATCH_SIZE):
            batch = owned_ids[offset:offset + DELETE_BATCH_SIZE]
            self._delete_dense(_chunk_ids_expr(batch), strict)
        if sparse_profile_version > 0 and owned_ids:
            from app.retrieval.sparse import SparseMilvusAdapter
            SparseMilvusAdapter(kb_id, sparse_profile_version).delete_by_ids(owned_ids)

    def search(self, kb_id: str, vector: list[float], top_k: int) -> list[dict]:
        return self._search(
            vector,
            top_k,
            f'kb_id == "{kb_id}"',
            ["chunk_id", "doc_id", "content"],
        )

    def search_profile(
        self,
        kb_id: str,
        vector: list[float],
        top_k: int,
        profile: str,
        entry_kind: str | None = None,
    ) -> list[dict]:
        if not self.profile_schema:
            raise ValueError("Profile search requires a profile-schema collection")
        profile_field = PROFILE_FIELDS.get(profile)
        if profile_field is None:
            raise ValueError(f"Unsupported retrieval profile: {profile}")
        if entry_kind is not None and entry_kind not in ENTRY_KINDS:
            raise ValueError(f"Unsupported entry kind: {entry_kind}")
        normalized_kb_id = str(uuid.UUID(kb_id))
        expression = f'kb_id == "{normalized_kb_id}" and {profile_field} == true'
        if entry_kind is not None:
            expression += f' and entry_kind == "{entry_kind}"'
        return self._search(
            vector,
            top_k,
            expression,
            ["chunk_id", "doc_id", "content", "entry_kind"],
        )

    def _search(
        self,
        vector: list[float],
        top_k: int,
        expression: str,
        output_fields: list[str],
    ) -> list[dict]:
        self._load_collection()
        results = self.collection.search(
            data=[vector],
            anns_field="embedding",
            param={"metric_type": "COSINE", "params": {"ef": 64}},
            limit=top_k,
            expr=expression,
            output_fields=output_fields,
            timeout=MILVUS_OPERATION_TIMEOUT_SECONDS,
        )
        hits = []
        for hit in results[0]:
            item = {
                "chunk_id": hit.entity.get("chunk_id"),
                "doc_id": hit.entity.get("doc_id"),
                "content": hit.entity.get("content"),
                "score": float(hit.score),
            }
            if "entry_kind" in output_fields:
                item["entry_kind"] = hit.entity.get("entry_kind")
            hits.append(item)
        return hits
