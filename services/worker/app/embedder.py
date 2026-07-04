import httpx
import logging

from app.config import settings

MAX_BATCH_SIZE = 32
logger = logging.getLogger(__name__)


class Embedder:
    def __init__(self, model: str | None = None):
        self.model = model or settings.embedding_model
        self.client = httpx.Client(
            base_url=settings.embedding_base_url,
            headers={"Authorization": f"Bearer {settings.embedding_api_key}"},
            timeout=120.0,
        )

    def embed(self, text: str) -> list[float]:
        response = self.client.post("/embeddings", json={"model": self.model, "input": text})
        response.raise_for_status()
        return response.json()["data"][0]["embedding"]

    def embed_batch(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []

        vectors: list[list[float]] = []
        for start in range(0, len(texts), MAX_BATCH_SIZE):
            batch = texts[start : start + MAX_BATCH_SIZE]
            response = self.client.post(
                "/embeddings",
                json={"model": self.model, "input": batch},
            )
            response.raise_for_status()
            data = response.json().get("data", [])
            if len(data) != len(batch):
                raise ValueError(
                    f"Embedding response count mismatch for batch {start}-{start + len(batch) - 1}: "
                    f"expected={len(batch)} actual={len(data)} model={self.model}"
                )
            if all("index" in item for item in data):
                ordered = sorted(data, key=lambda x: x["index"])
            else:
                logger.warning("Embedding response missing index; using provider response order")
                ordered = data

            for offset, item in enumerate(ordered):
                embedding = item.get("embedding")
                if not isinstance(embedding, list):
                    raise ValueError(
                        f"Embedding missing for batch item {start + offset} model={self.model}"
                    )
                vectors.append(embedding)

        return vectors
