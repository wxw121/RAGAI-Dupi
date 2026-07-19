import httpx
from minio import Minio

from app.config import settings


def _headers() -> dict[str, str]:
    if not settings.dupi_internal_key:
        return {}
    return {"X-Dupi-Internal-Key": settings.dupi_internal_key}


def _post(path: str, payload: dict):
    with httpx.Client(base_url=settings.api_base_url, timeout=60.0) as client:
        response = client.post(path, json=payload, headers=_headers())
        response.raise_for_status()
        try:
            return response.json()
        except (AttributeError, TypeError, ValueError):
            return {}


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
    return _post("/api/v1/internal/ingest/status", payload)


def claim_job(job_id: str, execution_id: str, worker_id: str, lease_seconds: int):
    return _post(
        f"/api/v1/internal/ingest/jobs/{job_id}/claim",
        {
            "executionId": execution_id,
            "workerId": worker_id,
            "leaseSeconds": lease_seconds,
        },
    )


def refresh_lease(job_id: str, execution_id: str, worker_id: str, lease_seconds: int):
    return _post(
        f"/api/v1/internal/ingest/jobs/{job_id}/lease",
        {
            "executionId": execution_id,
            "workerId": worker_id,
            "leaseSeconds": lease_seconds,
        },
    )


def check_cancelled(job_id: str, execution_id: str) -> bool:
    with httpx.Client(base_url=settings.api_base_url, timeout=60.0) as client:
        response = client.get(
            f"/api/v1/internal/ingest/jobs/{job_id}/cancelled",
            params={"executionId": execution_id},
            headers=_headers(),
        )
        response.raise_for_status()
        body = response.json()
        return bool(body.get("cancelled")) or body.get("status", "").upper() in {
            "CANCEL_REQUESTED",
            "CANCELLED",
        }


def get_job_state(job_id: str, execution_id: str) -> dict:
    with httpx.Client(base_url=settings.api_base_url, timeout=60.0) as client:
        response = client.get(
            f"/api/v1/internal/ingest/jobs/{job_id}/executions/{execution_id}/state",
            headers=_headers(),
        )
        if getattr(response, "status_code", None) == 404:
            return {"missing": True}
        response.raise_for_status()
        return response.json()
