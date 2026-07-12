import httpx
from minio import Minio

from app.config import settings


def get_minio_client() -> Minio:
    endpoint = settings.minio_endpoint.replace("http://", "").replace("https://", "")
    secure = settings.minio_endpoint.startswith("https")
    return Minio(
        endpoint,
        access_key=settings.minio_access_key,
        secret_key=settings.minio_secret_key,
        secure=secure,
    )


def download_object(object_key: str, dest_path: str):
    client = get_minio_client()
    client.fget_object(settings.minio_bucket, object_key, dest_path)


def post_status(payload: dict):
    headers = {}
    if settings.dupi_internal_key:
        headers["X-Dupi-Internal-Key"] = settings.dupi_internal_key
    with httpx.Client(base_url=settings.api_base_url, timeout=60.0) as client:
        response = client.post("/api/v1/internal/ingest/status", json=payload, headers=headers)
        response.raise_for_status()
