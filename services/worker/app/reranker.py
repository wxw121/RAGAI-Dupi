import threading
from dataclasses import dataclass
from typing import Any, Callable


@dataclass(frozen=True)
class RerankerStatus:
    model: str
    ready: bool
    error: str | None = None


class RerankerLifecycle:
    def __init__(
        self,
        model: str,
        cache_dir: str | None = None,
        model_factory: Callable[..., Any] | None = None,
    ):
        self.model = model
        self.cache_dir = cache_dir
        self._model_factory = model_factory
        self._model = None
        self._load_attempted = False
        self._error = None
        self._lock = threading.Lock()

    def warmup(self) -> RerankerStatus:
        with self._lock:
            if self._load_attempted:
                return self.status()
            self._load_attempted = True
            try:
                factory = self._model_factory or self._default_factory
                self._model = factory(self.model, cache_folder=self.cache_dir)
                self._model.predict([["warmup", "warmup"]])
            except Exception as exc:
                self._model = None
                self._error = type(exc).__name__
            return self.status()

    def get_model(self):
        if not self._load_attempted:
            self.warmup()
        return self._model

    def status(self) -> RerankerStatus:
        return RerankerStatus(self.model, self._model is not None, self._error)

    def rerank(self, query: str, hits: list[dict], top_k: int) -> list[dict]:
        model = self.get_model()
        if model is None or not hits:
            return hits[:top_k]
        scores = model.predict([[query, hit["content"]] for hit in hits])
        ranked = sorted(zip(hits, scores), key=lambda item: item[1], reverse=True)
        return [
            {**hit, "score": float(score), "rerank_score": float(score), "rerank_rank": rank}
            for rank, (hit, score) in enumerate(ranked[:top_k], start=1)
        ]

    @staticmethod
    def _default_factory(model: str, cache_folder: str | None = None):
        from sentence_transformers import CrossEncoder

        return CrossEncoder(model, cache_folder=cache_folder)
