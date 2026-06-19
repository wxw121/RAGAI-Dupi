import httpx

from app.config import settings


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
        response = self.client.post("/embeddings", json={"model": self.model, "input": texts})
        response.raise_for_status()
        data = response.json()["data"]
        return [item["embedding"] for item in sorted(data, key=lambda x: x["index"])]
