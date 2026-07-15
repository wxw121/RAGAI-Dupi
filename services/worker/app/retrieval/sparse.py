from pymilvus import (
    Collection, CollectionSchema, DataType, FieldSchema, Function, FunctionType,
    connections, utility,
)

from app.config import settings

MILVUS_OPERATION_TIMEOUT_SECONDS = 10


class SparseMilvusAdapter:
    def __init__(self, kb_id: str, profile_version: int, index_params: dict | None = None):
        if profile_version <= 0:
            raise ValueError("Sparse collection requires a positive profile version")
        connections.connect(alias="default", host=settings.milvus_host, port=settings.milvus_port)
        kb_key = kb_id.replace("-", "").lower()
        self.collection_name = f"{settings.milvus_collection}_sparse_{kb_key}_v{profile_version}"
        self.index_params = index_params or {}
        self._ensure_collection()

    def _ensure_collection(self):
        if utility.has_collection(self.collection_name):
            self.collection = Collection(self.collection_name)
            return
        fields = [
            FieldSchema(name="chunk_id", dtype=DataType.VARCHAR, is_primary=True, max_length=64),
            FieldSchema(name="kb_id", dtype=DataType.VARCHAR, max_length=64),
            FieldSchema(name="doc_id", dtype=DataType.VARCHAR, max_length=64),
            FieldSchema(
                name="content", dtype=DataType.VARCHAR, max_length=65535,
                enable_analyzer=True, analyzer_params=self.index_params.get("analyzer_params", {"type": "standard"}),
            ),
            FieldSchema(name="sparse_embedding", dtype=DataType.SPARSE_FLOAT_VECTOR),
        ]
        bm25 = Function(
            name="content_bm25",
            input_field_names=["content"],
            output_field_names=["sparse_embedding"],
            function_type=FunctionType.BM25,
        )
        self.collection = Collection(
            self.collection_name,
            CollectionSchema(fields, functions=[bm25], description="dupi-RAG native BM25 sparse chunks"),
        )
        self.collection.create_index(
            field_name="sparse_embedding",
            index_params={
                "index_type": "SPARSE_INVERTED_INDEX",
                "metric_type": "BM25",
                "params": {
                    "bm25_k1": float(self.index_params.get("bm25_k1", 1.5)),
                    "bm25_b": float(self.index_params.get("bm25_b", 0.75)),
                },
            },
        )

    def upsert(self, kb_id: str, doc_id: str, chunks: list) -> list[str]:
        if not chunks:
            return []
        rows = [{
            "chunk_id": chunk.id,
            "kb_id": kb_id,
            "doc_id": doc_id,
            "content": chunk.content[:65000],
        } for chunk in chunks]
        self.collection.upsert(rows)
        return [chunk.id for chunk in chunks]

    def delete_by_doc(self, doc_id: str):
        self.collection.delete(expr=f'doc_id == "{doc_id}"', timeout=MILVUS_OPERATION_TIMEOUT_SECONDS)

    def count(self, kb_id: str) -> int:
        self.collection.load(timeout=MILVUS_OPERATION_TIMEOUT_SECONDS)
        rows = self.collection.query(
            expr=f'kb_id == "{kb_id}"', output_fields=["count(*)"],
            timeout=MILVUS_OPERATION_TIMEOUT_SECONDS,
        )
        return int(rows[0].get("count(*)", 0)) if rows else 0

    def search(self, kb_id: str, query: str, top_k: int, search_params: dict | None = None) -> list[dict]:
        self.collection.load(timeout=MILVUS_OPERATION_TIMEOUT_SECONDS)
        results = self.collection.search(
            data=[query],
            anns_field="sparse_embedding",
            param={"metric_type": "BM25", "params": dict(search_params or {})},
            limit=top_k,
            expr=f'kb_id == "{kb_id}"',
            output_fields=["chunk_id", "doc_id", "content"],
            timeout=MILVUS_OPERATION_TIMEOUT_SECONDS,
        )
        return [{
            "chunk_id": hit.entity.get("chunk_id"),
            "doc_id": hit.entity.get("doc_id"),
            "content": hit.entity.get("content"),
            "score": float(hit.score),
            "sparse_rank": rank,
        } for rank, hit in enumerate(results[0], start=1)]
