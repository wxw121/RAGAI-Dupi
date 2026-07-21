# Ingestion Operations

A batch upload accepts at most 20 files. Each file may be no larger than 50 MiB. Supported source formats are PDF, DOCX, XLSX, Markdown, and plain text.

Document ingestion moves through PENDING, PROCESSING, COMPLETED, and FAILED. Cancellation prevents later callbacks from changing the terminal state. A failed job can be retried, while exhausted jobs move to the dead-letter workflow for operator review.

The default recursive chunk size is 512 tokens with 64 tokens of overlap. Worker embedding batches contain up to 32 chunks. Parser execution has a 120-second timeout.

Upload idempotency keys are retained for 24 hours. Reusing a key with different file content is rejected. Tenant and user quotas track retained bytes, retained document count, rolling-window bytes, and concurrent processing load.

Deleting a document schedules cleanup for legacy vectors, profile vectors, sparse vectors, object storage, and metadata. Cleanup failures are retried without restoring the deleted document to user-visible results.
