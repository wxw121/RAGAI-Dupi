import os
from dataclasses import dataclass


def _env(primary: str, fallback: str, default: str = "") -> str:
    return os.getenv(primary) or os.getenv(fallback) or default


@dataclass
class Settings:
    redis_host: str = os.getenv("REDIS_HOST", "localhost")
    redis_port: int = int(os.getenv("REDIS_PORT", "6379"))
    ingest_queue: str = os.getenv("INGEST_QUEUE", "dupi:ingest:jobs")

    minio_endpoint: str = os.getenv("MINIO_ENDPOINT", "http://localhost:9000")
    minio_access_key: str = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
    minio_secret_key: str = os.getenv("MINIO_SECRET_KEY", "minioadmin")
    minio_bucket: str = os.getenv("MINIO_BUCKET", "dupi-documents")

    milvus_host: str = os.getenv("MILVUS_HOST", "localhost")
    milvus_port: int = int(os.getenv("MILVUS_PORT", "19530"))
    milvus_collection: str = os.getenv("MILVUS_COLLECTION", "dupi_chunks")

    api_base_url: str = os.getenv("API_BASE_URL", "http://localhost:8080")

    embedding_api_key: str = _env("EMBEDDING_API_KEY", "OPENAI_API_KEY")
    embedding_base_url: str = _env("EMBEDDING_BASE_URL", "OPENAI_BASE_URL", "https://api.openai.com/v1")
    embedding_model: str = _env("EMBEDDING_MODEL", "OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
    embedding_dimension: int = int(_env("EMBEDDING_DIMENSION", "OPENAI_EMBEDDING_DIMENSION", "1536"))

    worker_host: str = os.getenv("WORKER_HOST", "0.0.0.0")
    worker_port: int = int(os.getenv("WORKER_PORT", "8000"))


settings = Settings()
