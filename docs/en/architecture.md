# Architecture Overview

<!-- language-switch -->
[中文](../zh-CN/architecture.md) | **English**

## Durable resource workflows

PostgreSQL is the source of truth for long-running resource mutations. Knowledge-base deletion, Recovery ZIP import, and Markdown package import return `202 Accepted` with an `operation_jobs` record instead of claiming synchronous completion. The runner claims due work with a fenced token/epoch and lease, records idempotent `operation_steps`, and moves through `PREPARED`, `RUNNING`, `RETRY_WAIT`, `COMPENSATING`, then `COMPLETED` or `FAILED`. `GET /api/v1/operations/{jobId}` exposes sanitized progress to `KB_READ`; only a failed job can start a new operator retry epoch, and that action requires `MAINTENANCE` plus `KB_READ`.

Domain visibility is part of the operation transaction. A knowledge base becomes `DELETING` before cleanup and is excluded from normal reads. Markdown imports prepare hidden document rows and deterministic object targets; after every object is verified, one fenced publish transaction makes all documents visible and completes the operation. Recovery imports use the same principle for the new archive identity. Audit events for submit, retry, completion, compensation, and failure are written only with the real fenced state transition in the same transaction.

External object I/O never holds a database lock. Before staging I/O, the intake acquires a durable owner token, epoch, and renewable lease. Flyway V28 adds `operation_staging_attempts`, whose unique object key and `ACTIVE`, `CLEANUP_PENDING`, or `CLEANED` state preserve cleanup truth across crashes. A lease takeover atomically fences the previous owner and marks its active attempts cleanup-pending. Retention converts abandoned non-runnable intake to compensation; a bounded replayer performs checked deletion and retains absent-object work so late writers cannot create untracked orphans.

Flyway V27 changes RAG evaluation identity from mutable file names to document UUIDs. It backfills only names that resolve to one document in the same knowledge base; ambiguous legacy cases deliberately remain unresolved for review or regeneration. V25 introduces durable operation jobs and steps, V26 adds document import visibility state, and V28 adds staging attempts. Production upgrades must apply and validate these migrations in order.

See the [Recovery runbook](v1.4-recovery-runbook.md) for the executable PostgreSQL V24-to-V28 migration drill and MinIO outage/restart matrix.
