import os
import socket
from dataclasses import dataclass


def _env(primary: str, fallback: str, default: str = "") -> str:
    return os.getenv(primary) or os.getenv(fallback) or default


@dataclass
class Settings:
    redis_host: str = os.getenv("REDIS_HOST", "localhost")
    redis_port: int = int(os.getenv("REDIS_PORT", "6379"))
    ingest_queue: str = os.getenv("INGEST_QUEUE", "dupi:ingest:jobs")
    ingest_processing_queue: str = os.getenv(
        "INGEST_PROCESSING_QUEUE",
        f"{os.getenv('INGEST_QUEUE', 'dupi:ingest:jobs')}:processing",
    )
    ingest_lease_seconds: int = int(os.getenv("INGEST_LEASE_SECONDS", "60"))
    ingest_heartbeat_interval_seconds: float = float(
        os.getenv("INGEST_HEARTBEAT_INTERVAL_SECONDS", "15")
    )
    ingest_processing_reap_interval_seconds: float = float(
        os.getenv("INGEST_PROCESSING_REAP_INTERVAL_SECONDS", "60")
    )
    ingest_processing_reap_batch_size: int = int(
        os.getenv("INGEST_PROCESSING_REAP_BATCH_SIZE", "100")
    )
    redis_retry_delay_seconds: float = float(os.getenv("REDIS_RETRY_DELAY_SECONDS", "1"))
    worker_id: str = os.getenv("WORKER_ID", f"{socket.gethostname()}-{os.getpid()}")

    minio_endpoint: str = os.getenv("MINIO_ENDPOINT", "http://localhost:9000")
    minio_access_key: str = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
    minio_secret_key: str = os.getenv("MINIO_SECRET_KEY", "minioadmin")
    minio_bucket: str = os.getenv("MINIO_BUCKET", "dupi-documents")

    milvus_host: str = os.getenv("MILVUS_HOST", "localhost")
    milvus_port: int = int(os.getenv("MILVUS_PORT", "19530"))
    milvus_collection: str = os.getenv("MILVUS_COLLECTION", "dupi_chunks")
    sparse_dual_write_profile_version: int = int(os.getenv("SPARSE_DUAL_WRITE_PROFILE_VERSION", "0"))
    allow_legacy_bm25_fallback: bool = os.getenv("ALLOW_LEGACY_BM25_FALLBACK", "false").lower() == "true"

    api_base_url: str = os.getenv("API_BASE_URL", "http://localhost:8080")
    dupi_internal_key: str = os.getenv("DUPI_INTERNAL_KEY", "")

    embedding_api_key: str = _env("EMBEDDING_API_KEY", "OPENAI_API_KEY")
    embedding_base_url: str = _env("EMBEDDING_BASE_URL", "OPENAI_BASE_URL", "https://api.openai.com/v1")
    embedding_model: str = _env("EMBEDDING_MODEL", "OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
    embedding_dimension: int = int(_env("EMBEDDING_DIMENSION", "OPENAI_EMBEDDING_DIMENSION", "1536"))
    embedding_batch_size: int = int(os.getenv("EMBEDDING_BATCH_SIZE", "32"))

    rerank_model: str = os.getenv("RERANK_MODEL", "BAAI/bge-reranker-base")
    rerank_warmup_enabled: bool = os.getenv("RERANK_WARMUP_ENABLED", "true").lower() == "true"
    rerank_cache_dir: str = os.getenv(
        "SENTENCE_TRANSFORMERS_HOME", os.getenv("HF_HOME", "/models/huggingface")
    )

    worker_host: str = os.getenv("WORKER_HOST", "0.0.0.0")
    worker_port: int = int(os.getenv("WORKER_PORT", "8000"))


settings = Settings()
