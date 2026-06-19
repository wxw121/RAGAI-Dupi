from pymilvus import (
    Collection,
    CollectionSchema,
    DataType,
    FieldSchema,
    connections,
    utility,
)

from app.config import settings
from app.models import TextChunk


class MilvusIndexer:
    def __init__(self, dimension: int | None = None):
        self.dimension = dimension or settings.embedding_dimension
        connections.connect(alias="default", host=settings.milvus_host, port=settings.milvus_port)
        self.collection_name = settings.milvus_collection
        self._ensure_collection()

    def _ensure_collection(self):
        if utility.has_collection(self.collection_name):
            self.collection = Collection(self.collection_name)
            self.collection.load()
            return

        fields = [
            FieldSchema(name="chunk_id", dtype=DataType.VARCHAR, is_primary=True, max_length=64),
            FieldSchema(name="kb_id", dtype=DataType.VARCHAR, max_length=64),
            FieldSchema(name="doc_id", dtype=DataType.VARCHAR, max_length=64),
            FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=65535),
            FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=self.dimension),
        ]
        schema = CollectionSchema(fields, description="dupi-RAG chunks")
        self.collection = Collection(self.collection_name, schema)
        self.collection.create_index(
            field_name="embedding",
            index_params={"index_type": "HNSW", "metric_type": "COSINE", "params": {"M": 16, "efConstruction": 200}},
        )
        self.collection.load()

    def index_chunks(
        self,
        kb_id: str,
        doc_id: str,
        chunks: list[TextChunk],
        vectors: list[list[float]],
    ) -> list[str]:
        if not chunks:
            return []

        chunk_ids = [c.id for c in chunks]
        kb_ids = [kb_id] * len(chunks)
        doc_ids = [doc_id] * len(chunks)
        contents = [c.content[:65000] for c in chunks]

        self.collection.insert([chunk_ids, kb_ids, doc_ids, contents, vectors])
        self.collection.flush()

        for chunk, cid in zip(chunks, chunk_ids):
            chunk.milvus_id = cid
        return chunk_ids

    def delete_by_doc(self, doc_id: str):
        self.collection.delete(expr=f'doc_id == "{doc_id}"')

    def search(self, kb_id: str, vector: list[float], top_k: int) -> list[dict]:
        results = self.collection.search(
            data=[vector],
            anns_field="embedding",
            param={"metric_type": "COSINE", "params": {"ef": 64}},
            limit=top_k,
            expr=f'kb_id == "{kb_id}"',
            output_fields=["chunk_id", "doc_id", "content"],
        )
        hits = []
        for hit in results[0]:
            hits.append({
                "chunk_id": hit.entity.get("chunk_id"),
                "doc_id": hit.entity.get("doc_id"),
                "content": hit.entity.get("content"),
                "score": float(hit.score),
            })
        return hits
