# Recovery Runbook

The production recovery objectives are RPO 15 minutes and RTO 60 minutes. Recovery archives are retained for 35 days, and a full recovery drill runs every quarter.

Restore order is PostgreSQL first, MinIO objects second, Milvus vectors third, and Redis cache last. PostgreSQL restores knowledge-base metadata and document records. MinIO restores original binaries. Milvus restores dense and sparse vector collections. Redis is rebuilt after durable stores are verified.

Every archive has a SHA-256 manifest. A restore is blocked when the manifest checksum, record count, object checksum, or vector count does not match. Corrupted archives must remain quarantined and must not partially overwrite the target knowledge base.

Application rollback reactivates the last retrieval profile that has matching PASS evaluation evidence. After restore or rollback, operators run document-count checks, vector-count checks, sample retrieval, the RAG benchmark, and an audit-log review before reopening traffic.

Recovery actions require OPS_ADMIN authorization and emit audit events for archive creation, restore start, verification failure, rollback, retry, and completion.
