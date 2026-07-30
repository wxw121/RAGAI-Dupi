# dupi-RAG

<!-- language-switch -->
[中文](README.zh-CN.md) | **English**


> V2.0 is the current packaged release in API/Web metadata (`2.0.0`). V1.6b-V2.4 remain completed local RAG quality milestones; the former V2.5-V3.0 follow-up scope has been pulled into the V2.0 RAG Quality Closure package.

See the [V1.5.0 release notes](docs/v1.5-release-notes.md) and [release runbook](docs/v1.5-release-runbook.md) as historical upgrade references. V2.0 keeps `CLASSIC` as the safe baseline until the current index revision passes the candidate-vs-classic quality gate. See the [V2.0 RAG quality roadmap](docs/en/rag-quality-roadmap.md), [design](docs/en/v2.0-rag-quality-closure-design.md), and [implementation plan](docs/en/v2.0-rag-quality-closure-implementation.md).

> V1.4.2 added a read-only GET /api/v1/ops/governance-summary endpoint for OPS_ADMIN operators plus a smoke script and Pester check for the V1.4.1 upload, ingest, outbox, notification, and vector cleanup state.

## V1.4.2 Governance Ops

GET /api/v1/ops/governance-summary returns a compact read-only snapshot with generatedAt, uploadQuota, ingestJobs, ingestOutbox, failureNotifications, vectorCleanup, and alerts.

Smoke check: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-governance-summary.ps1 -BaseUrl http://localhost:8080 -ApiKey $env:DUPI_API_KEY -OutFile evidence/governance-summary-smoke.json

Focused Pester coverage currently passes 4 of 4: powershell -NoProfile -ExecutionPolicy Bypass -Command Import-Module Pester; Invoke-Pester -Path scripts/tests/smoke-governance-summary.Tests.ps1 -CI

Local Web validation on this workstation must use project npm scripts so services/web/scripts/node16-webcrypto.cjs loads the Node 16 WebCrypto shim. Do not invoke raw vite or vitest directly on Node 16.

> V1.4.1 adds persisted tenant/user upload quotas, idempotent per-file uploads, cancellable and leased ingest executions, stale callback protection, and deduplicated terminal-failure events with optional webhook delivery. API version: `1.4.1-SNAPSHOT`; Web version: `1.4.1`.

## V1.4.1 Upload Governance

The Web uploads each file independently with bounded concurrency and an `Idempotency-Key`. It shows retained and rolling-window quota, keeps failures isolated per file, supports transport abort/retry, and calls the ingest cancellation API after a job exists. Polling is serialized and aborted on unmount so an older response cannot overwrite newer state.

PostgreSQL is authoritative for upload reservations and ingest execution. Upload reservations move through `PENDING -> COMMITTED -> RELEASED`; retained quota counts active `PENDING` + `COMMITTED` reservations, while `RELEASED` reservations no longer consume retained bytes/documents. `attemptId` / `attemptExpiresAt` lease in-flight uploads so the stale-upload reconciler can either commit durable doc/job/outbox attempts or clean partial objects/jobs/docs before release. A retry of the same released idempotency key rechecks retained quota but does not double-charge rolling-window bytes. Ingest retries rotate `executionId`; Worker callbacks carry a monotonic `sequence`; stale, duplicate, or terminal-state callbacks are acknowledged as ignored. Redis uses ready and processing lists, a bounded reaper moves `requeueEligible` processing payloads back to ready, and a processing item is acknowledged only after terminal handling.

Terminal `FAILED`/`DEAD_LETTER` notifications are persisted once per job execution/status. With a webhook configured, due `PENDING`/`FAILED` rows are delivered with bounded backoff; 2xx responses become `DELIVERED`, and rows that reach the attempt limit become `EXHAUSTED`. Webhook delivery requires HTTPS by default, blocks local/metadata hosts unless explicitly allowed, can include `X-Dupi-Webhook-Secret`, and truncates sanitized error text.

| Variable | Default | Purpose |
|---|---:|---|
| `UPLOAD_QUOTA_ENABLED` | `true` | Enable persistent upload quota accounting |
| `UPLOAD_QUOTA_RETAINED_BYTES_LIMIT` | `1073741824` | Retained bytes per tenant/user |
| `UPLOAD_QUOTA_RETAINED_DOCUMENTS_LIMIT` | `1000` | Retained documents per tenant/user |
| `UPLOAD_QUOTA_WINDOW_BYTES_LIMIT` | `268435456` | Accepted bytes per rolling window |
| `UPLOAD_QUOTA_WINDOW_SECONDS` | `3600` | Rolling upload window |
| `UPLOAD_QUOTA_ATTEMPT_LEASE_SECONDS` | `300` | In-flight upload attempt lease before stale reconciliation |
| `UPLOAD_QUOTA_RECONCILIATION_BATCH_SIZE` | `50` | Max stale upload reservations claimed per reconciler pass |
| `UPLOAD_QUOTA_RECONCILIATION_CRON` | `0 */5 * * * *` | Stale upload reservation reconciler cadence |
| `INGEST_PROCESSING_QUEUE` | `dupi:ingest:jobs:processing` | Worker in-flight Redis list |
| `INGEST_LEASE_SECONDS` | `60` | PostgreSQL ingest claim lease |
| `INGEST_HEARTBEAT_INTERVAL_SECONDS` | `15` | Worker lease heartbeat during long operations |
| `INGEST_PROCESSING_REAP_INTERVAL_SECONDS` | `60` | Worker processing-list reaper cadence |
| `INGEST_PROCESSING_REAP_BATCH_SIZE` | `100` | Oldest-tail processing payloads inspected per reaper pass |
| `REDIS_RETRY_DELAY_SECONDS` | `1` | Worker Redis transient failure backoff |
| `WORKER_ID` | host/process derived | Stable claim owner identifier |
| `INGEST_FAILURE_NOTIFICATION_WEBHOOK_URL` | empty | Optional POST target for FAILED/DEAD_LETTER ingest events |
| `INGEST_FAILURE_NOTIFICATION_TIMEOUT_SECONDS` | `10` | Webhook delivery timeout |
| `INGEST_FAILURE_NOTIFICATION_MAX_ATTEMPTS` | `5` | Bounded webhook retry attempts before `EXHAUSTED` |
| `INGEST_FAILURE_NOTIFICATION_WEBHOOK_SECRET` | empty | Optional `X-Dupi-Webhook-Secret` header value |
| `INGEST_FAILURE_NOTIFICATION_MAX_ERROR_MESSAGE_LENGTH` | `512` | Sanitized webhook error-text cap |
| `INGEST_FAILURE_NOTIFICATION_ALLOW_INSECURE_WEBHOOK` | `false` | Permit non-HTTPS/local webhook targets for trusted local testing only |
| `INGEST_FAILURE_NOTIFICATION_DISPATCH_CRON` | `*/30 * * * * *` | Failure-notification dispatch cadence |

Key routes:

```bash
# User-visible quota; requires DOCUMENT_UPLOAD
curl http://localhost:8080/api/v1/upload-quota

# Idempotent single-file upload
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/documents \
  -H "Idempotency-Key: upload-20260718-001" \
  -F "file=@sample.pdf"

# Cancel queued/running ingest
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/ingest-jobs/{jobId}/cancel
```

See [the V1.4.1 release runbook](docs/v1.4.1-release-runbook.md) and the design（local note）. The latest local V1.4.1 release scan records image digest `sha256:eec613fab9cdd1d873b95172f98d42ade5989238e2b0f76761b6b4f63b86515a`, image size 640,389,450 bytes, no Python findings, and 22 accepted upstream-unfixed OS findings expiring 2026-08-15.

> V1.4.0 adds tenant-scoped, checksum-verified knowledge-base archives and idempotent restore into a new hidden knowledge base. It is an application recovery layer, not a replacement for PostgreSQL, MinIO, etcd, or Milvus infrastructure backups.

## V1.4 Verifiable Recovery

Operators with `KB_RECOVERY` use the **Recovery** tab to create, inspect, download, retry, and delete archives, and to create, retry, or abandon restores. Archive objects are sealed under `archives/{tenantId}/{archiveId}/` in a private recovery bucket; `manifest.json` is written last. A target stays hidden as `RESTORING` until objects, records, dense/sparse vectors, counts, schemas, and checksums verify.

Routes are below `/api/v1/knowledge-bases/{kbId}/recovery`. Commands return `202 Accepted`; the Web panel polls non-terminal jobs every three seconds. See [the recovery runbook](docs/v1.4-recovery-runbook.md).

| Variable | Default | Purpose |
|---|---:|---|
| `DUPI_RECOVERY_BUCKET` | `dupi-recovery` | Dedicated private MinIO bucket |
| `DUPI_RECOVERY_QUIESCENCE_TIMEOUT_SECONDS` | `300` | Wait for active KB mutations |
| `DUPI_RECOVERY_PAGE_SIZE` | `500` | Bounded vector snapshot page size |
| `DUPI_RECOVERY_MAX_CONCURRENT_JOBS` | `2` | Bounded archive/restore concurrency |

### V1.4.0 Release Gate

The Worker image installs CPU-only PyTorch from the official CPU wheel index, uses PyMilvus 2.5.18 with the current patched packaging toolchain, and runs as UID/GID `65534`. The gate runs `pip check` and imports the production Worker modules before scanning. Run it from the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/scan-release.ps1 `
  -Image dupi-rag-worker:v1.4 `
  -OutputPath artifacts/v1.4-release-scan `
  -HighExceptionPath deploy/release-exceptions/v1.4.0.json
```

The scan exports the image's exact `pip freeze --all` result to `worker-requirements.lock.txt`, then audits that lock without resolving a second dependency graph. It accepts either a `pip-audit` executable on `PATH` or an installed `pip_audit` module through `python -m pip_audit`, retries transient audit failures up to three times, and falls back to the pinned `dupi-rag-pip-audit:2.10.1` container when host networking cannot reach OSV. Every path deletes stale output first and records the execution mode in `summary.md`. Use `-TrivySkipDbUpdate` only when the local Trivy database was refreshed separately. The structured exception release must match the normalized image tag, cover every active upstream-unfixed finding exactly, and expires on `2026-08-15`; fixable, expired, unused, or unmatched entries fail the gate. The release scan generates the dependency lock, pip-audit JSON, CycloneDX/Syft SBOM, Trivy version/result JSON, and `summary.md` under `artifacts/v1.4-release-scan`. The summary records the immutable image digest and Trivy vulnerability-database timestamp.


V1.3 adds blockable RAG quality policies/baselines, versioned Retrieval profiles, as well as Milvus native Sparse BM25 backfilling, dual write, shadow validation, cutover, and rollback. Production deployment requires Milvus 2.5.4; before upgrading, back up Milvus/etcd/MinIO/PostgreSQL and complete backfill plus rollback drills in isolation.

## V1.3 Sparse Migration Operation and Maintenance

Each Profile uses an independent collection `{MILVUS_COLLECTION}_sparse_{kbId}_v{version}`. The migration status is in sequence as `PREPARING -> BACKFILLING -> DUAL_WRITING -> SHADOW_VALIDATING -> CUTOVER -> COMPLETED`. If it fails, it enters `FAILED`. `BACKFILLING` can be idempotent and retried. legacy BM25 fallback is controlled by migration record persistence and is only allowed to be enabled during the dual write and Shadow phases; After completion, the Sparse write is permanently driven by activating the Profile.

Cutover requires a coverage rate of 100%, consistent embedding dimensions, candidate profiles with exactly matching PASS evaluations, candidate P95 not exceeding 1.25 times the baseline, and no increase in fallback rate. Rollback can only reactivate older profiles that already have PASS evidence. Deleting a document will simultaneously clean up the dense collection and all versioned Sparse collections of this knowledge base.

Real corpus benchmark command

```powershell

powershell -NoProfile -ExecutionPolicy Bypass -File scripts/rag-retrieval-benchmark.ps1 `
  -KbId <kbId> -HybridProfileId <profileId> -RerankProfileId <rerankProfileId> `
  -ApiKey $env:DUPI_API_KEY -OutputPath artifacts/v13-real-benchmark.json

```

The script will check the actual mode, Profile switch, use-by-case phase ranking, and the rank delta of the relative VECTOR; It failed directly when reranker was not actually executed.

Worker uses CPU-only PyTorch and `BAAI/bge-reranker-base`, and by default loads the model and performs preheating inference during the startup lifecycle. Compose persists the model cache through `hf_model_cache`. A preheating failure will mark Rerank unavailable in `/health`, but will not block VECTOR/HYBRID. Cold start delay must not be mixed with hot P95.

Account/RBAC and ops administrative permission update records can be found at [docs/rbac-ops-admin-2026-07-06.md](docs/rbac-ops-admin-2026-07-06.md); See [docs/ outbox-tombstone-rbac-OPs-2026-07-07.md](docs/outbox-tombstone-rbac-ops-2026-07-07.md) for ingroving outbox, removing tombstone, instance authorization and audit operations enhancement.
> V1.1 (API `0.1.1-SNAPSHOT` / Web `0.1.1`) adds the real-browser E2E gate, ingest diagnostics, RAG evaluation, upload-governance guidance, and aggregated operations alerts.
> V1.2 expands browser coverage with document-index details, structured chat errors, persistent RAG evaluation cases and history, hybrid retrieval and Rerank controls, audit webhooks, and metadata or chunk-snapshot recovery.
> V1.2.1 isolates real-browser gate data in the `e2e` tenant and removes temporary knowledge bases and accounts after successful runs.
> V1.5 (RAG Quality Upgrade) adds Parent-Child/QA-assisted indexing, a filterable profile v2 Milvus superset Combined weighted RRF, revision-bound eval quality gates, and Web readiness/gate comparisons.
> V1.6b-V2.4 are local RAG quality milestones covering benchmark, dashboard, and quality-loop summaries; they are not packaged releases.

Enterprise-level RAG knowledge base engine - similar to the Dify/ Douzi underlying knowledge base module.

Supports the upload of private documents (PDF, DOCX, TXT, Markdown, Excel), asynchronous parsing and vectorization, and combines large models for retrieval to enhance question answering (SSE streaming).

### V1.5 upgrade and activation

- V1.5 uses an independent `MILVUS_PROFILE_COLLECTION` to save the filterable superset shared by classic, parent-child, qa-assisted, and combined. After the existing knowledge base is upgraded, a "rebuild index" operation needs to be performed once. The reconstruction replaces vectors and chunks by document scrolling, without clearing the entire online index first.
- The knowledge base is marked as profile v2 ready only when all documents are `COMPLETED` and `index_schema_version=2`. The first ready will persist the cutover state and clean up Legacy. During subsequent uploads or rebuilds, the completed documents in v2 will still be used and will not be rolled back to the cleaned Legacy. Switching the default profile only changes the search entry and does not rebuild the unified index again.
- For non-classic profiles, the RAG evaluation of the current `index_revision` must be compared with `CLASSIC`, and it must include at least 3 cases, references can be evaluated, and neither hit rate nor reference pass rate can be rolled back. When not passed, the update interface returns HTTP `409`, and the error code is `retrieval_profile_gate_blocked`.

Version changes can be found in [V1.5.0 Release Notes](docs/v1.5-release-notes.md). Upgrade, gray-scale, verification and rollback steps can be found in [V1.5.0 Release and Operation Manual](docs/v1.5-release-runbook.md).

## Technology Stack

- **Web Console**: React 18 + Vite + TypeScript + Tailwind
- **API**: Java 17 + Spring Boot 3
- **Build Tool**: Maven Wrapper pins Apache Maven 3.9.9 (`services/api/mvnw.cmd` / `services/api/mvnw`)
- **Worker** : Python 3.11
- **Vector database**: Milvus | **Metadata**: PostgreSQL | **Queue**: Redis | **Object storage**: MinIO

## Quick start

1. Configure environment variables

```bash

cp deploy/.env.example deploy/.env

```

To edit `deploy/.env`, two sets of LLM credentials must be configured (DeepSeek official has no Embedding interface) :

| Variable | Purpose | Notes |
|---|---|---|
| `CHAT_API_KEY` | RAG chat model API key | Apply at [platform.deepseek.com](https://platform.deepseek.com) |
| `CHAT_BASE_URL` | Chat API base URL | `https://api.deepseek.com` |
| `CHAT_MODEL` | Chat model | `deepseek-chat` |
| `EMBEDDING_API_KEY` | Document vectorization and retrieval key | Apply at [Zhipu Open Platform](https://open.bigmodel.cn) |
| `EMBEDDING_BASE_URL` | Embedding API base URL | `https://open.bigmodel.cn/api/paas/v4` |
| `EMBEDDING_MODEL` | Embedding model | `embedding-2` |
| `EMBEDDING_DIMENSION` | Embedding vector dimension | Must match the model; Zhipu `embedding-2` uses `1024` |
| `EMBEDDING_BATCH_SIZE` | Worker embedding batch size | Default `32`; lower it if provider limits require |
| `DUPI_API_KEY` | Optional public API shared key | May be blank for trusted local development; set it in shared/deployed environments |
| `DUPI_INTERNAL_KEY` | Optional internal API shared key | Must match between API and Worker |
| `UPLOAD_RATE_LIMIT_REQUESTS` | Upload requests per rate-limit window | Default `20` |
| `UPLOAD_RATE_LIMIT_WINDOW_SECONDS` | Upload rate-limit window seconds | Default `60` |
| `INGEST_QUEUE_MAX_PENDING_JOBS` | Redis ingest queue high-water mark | Default `200`; uploads are rejected quickly when reached |
| `INGEST_RECOVERY_CRON` | Ingest compensation scan cron | Default every 2 minutes |
| `INGEST_RECOVERY_MAX_ATTEMPTS` | Max automatic ingest compensation retries | Default `3`; then moves to dead-letter status |
| `INGEST_OUTBOX_DISPATCH_CRON` | Transactional outbox dispatch cron | Default every 10 seconds |
| `ORPHAN_VECTOR_CLEANUP_CRON` | Orphan vector cleanup cron | Default daily at `03:30` |
| `AUDIT_RETENTION_DAYS` | Audit log retention days | Default `180`; `<=0` disables cleanup |
| `AUDIT_RETENTION_CRON` | Audit retention cleanup cron | Default daily at `02:15` |
| `AUDIT_ALERT_WINDOW_MINUTES` | Audit failure alert window | Default `30` minutes |
| `AUDIT_ALERT_FAILED_THRESHOLD` | Audit failure alert threshold | Default `10` failures |
| `AUDIT_ALERT_WEBHOOK_URL` | Optional audit alert webhook URL | Blank returns `configured=false` |
| `AUDIT_ALERT_WEBHOOK_TIMEOUT_SECONDS` | Audit alert webhook timeout | Default `10` seconds |

Restart the application container after configuration

```bash

cd deploy
docker compose up -d --force-recreate api worker

```

2. Start infrastructure and applications

```bash

cd deploy
docker compose up -d --build

```

3. Access the Web console

Open **http://localhost:8080** in the browser

1. **Create a New Knowledge Base** → Select vector search or hybrid search, and click the card to enter the details
2. **Document Management** → Upload a file, wait for status `COMPLETED`, then click the View button to inspect objects, ingest tasks, total chunk count, up to 20 chunk samples, and index readiness status
3. **Intelligent Q&A** → Ask questions based on ingested documents (`CHAT_API_KEY` and `EMBEDDING_API_KEY` need to be configured)
4. **RAG Evaluation** → Manage persistent use cases (automatically create built-in use cases for empty libraries, with a maximum of 100 cases per library), select whether to enable Rerank, run evaluations, and inspect the latest 10 runs plus per-case diagnostics

4. Verification

```bash

# 健康检查（经 Nginx 代理）
curl http://localhost:8080/actuator/health

```

By default, Compose only exposes the Web entry `http://localhost:8080`, and the API, Worker, PostgreSQL, Redis, Milvus, and MinIO are only visible within the Docker internal network. When direct connection debugging is required, the local override file can be temporarily used to map the port to avoid exposing the debugging port to the default deployment for a long time.

When checking the Compose expansion configuration, please use the desensitization script to avoid printing third-party keys in `.env` to the terminal or chat records:

```powershell

powershell -ExecutionPolicy Bypass -NoProfile -File scripts/compose-config-redacted.ps1

```

If you have ever pasted the original output of `docker compose config` into the terminal shared context or screenshot, please immediately rotate the corresponding `CHAT_API_KEY`, `EMBEDDING_API_KEY` and the shared key.

### Docker startup troubleshooting

- **Slow or failed image pull**: Prioritize Docker Desktop registry mirrors or pre-pull base images used by Compose; do not write temporary proxy addresses into repository configuration.
- **Slow or failed Worker pip install**: Configure the pip mirror locally or in CI; keep dependency versions pinned to `services/worker/requirements*.txt` instead of temporarily relaxing constraints such as `pymilvus` or `marshmallow`.
- **Front-end build Node version issue**: The Web script is compatible with native Node 16 via `services/web/scripts/node16-webcrypto.cjs`; for production builds, it is still recommended to use the fixed build environment in the project Dockerfile or Node 18+.
- **CORS or port access exception**: By default, only `http://localhost:8080` is accessed, and `/api` is reverse-proxied by Web Nginx; if you need to directly connect to API, PostgreSQL, Redis, Milvus, or MinIO, use a temporary Compose override to explicitly expose the port.
- **Milvus dimension inconsistency**: `EMBEDDING_DIMENSION` must be consistent with the `embedding` vector dimension of the current `MILVUS_COLLECTION`. After switching the embedding model/dimension, the old knowledge base can be reconstructed using `POST /api/v1/knowledge-bases/{kbId}/reindex`. If the dimensions of the collection itself do not match, the API will fail-fast at startup. You need to delete/rebuild the collection or point `MILVUS_COLLECTION` to a new dimensional-specific collection.
- **Slow Milvus collection loading**: API startup only initiates collection loading asynchronously and no longer waits synchronously for the QueryNode, avoiding long Web 502 windows. While the collection is not ready, retrieval follows the existing local text fallback and reports the cause in diagnostics.

### End-to-end main process automation

The script calls the interface in the order of the Web console buttons (Health → Library Building → Upload → Intake → Search → Question Answering SSE) :

```powershell

powershell -NoProfile -File scripts/e2e-main-flow.ps1

```

Valid `EMBEDDING_*` and `CHAT_*` configurations are required. Step-by-step instructions and recent running results can be found at [docs/e2e-testing.md](docs/e2e-testing.md).

New maintenance and regression verification script added:

```powershell

# 真实浏览器 E2E 门禁：使用真实登录、Cookie 与 CSRF，不依赖本地开放模式
$env:E2E_BASE_URL="http://localhost:8080"
$env:E2E_ADMIN_USERNAME="<admin>"
$env:E2E_ADMIN_PASSWORD="<password>"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/e2e-browser-gate.ps1

# 索引维护流程：批量上传、reindex、摄入任务重试入口、向量清理任务入口
powershell -NoProfile -File scripts/e2e-web-maintenance-flow.ps1

# RAG 检索回归评测：按 examples/rag-eval-cases.json 校验命中与引用文件
powershell -NoProfile -File scripts/rag-regression-eval.ps1

```

`e2e-browser-gate.ps1` requires `E2E_ADMIN_USERNAME` and `E2E_ADMIN_PASSWORD`. If the credentials are missing, it will explicitly fail. Access control is only created as a temporary administrator in the `e2e` tenant by the configuration administrator. Subsequent knowledge bases, RAG use cases, and verification accounts are all completed in this tenant. Upon success, the temporary knowledge base and the `e2e_*` account will be automatically deleted. If it fails, the resource identifier and page URL will be retained in the Playwright result as evidence. `rag-regression-eval.ps1` will be written to `scripts/rag-regression-eval-last-run.json`. Among them, `caseResults` contains the query, pass/fail, hit count, expected/hit file, hit token, retrieval mode, fallback reason and embedding information for each use case.

### API examples

```bash

# 创建知识库
curl -X POST http://localhost:8080/api/v1/knowledge-bases \
  -H "Content-Type: application/json" \
  -d '{"name":"demo","description":"测试库","chunkSize":512,"chunkOverlap":64,"topK":5,"retrievalMode":"HYBRID"}'

# 上传文档
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/documents \
  -F "file=@sample.pdf"

# 检索调试
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/retrieve \
  -H "Content-Type: application/json" \
  -d '{"query":"你的问题","topK":5}'

# /retrieve 返回 citations 和 diagnostics，可用于排查命中数、fallback 原因与 embedding 配置

# 重建旧知识库索引（切换 embedding 模型/维度后使用）
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/reindex

# 重试失败或死信摄入任务
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/ingest-jobs/{jobId}/retry

# 查看摄入任务诊断；响应包含 documentFileName、documentStatus、diagnosis
curl http://localhost:8080/api/v1/knowledge-bases/{kbId}/ingest-jobs

# 查看单文档上传/摄入/索引详情；包含对象状态、最近任务、分块数与分块样例
curl http://localhost:8080/api/v1/knowledge-bases/{kbId}/documents/{docId}/index-detail

# 管理持久化 RAG 评估用例、运行评估并查看最近历史
curl http://localhost:8080/api/v1/knowledge-bases/{kbId}/rag-eval/cases
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/rag-eval/cases \
  -H "Content-Type: application/json" \
  -d '{"caseKey":"format-check","query":"支持哪些格式？","minHits":1,"topK":5,"expectedFileName":"guide.md","mustContainAny":["PDF"]}'
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/rag-eval/runs \
  -H "Content-Type: application/json" \
  -d '{"useRerank":true}'
curl http://localhost:8080/api/v1/knowledge-bases/{kbId}/rag-eval/runs

# 查看并重试残留向量补偿清理任务
curl http://localhost:8080/api/v1/ops/vector-cleanup-tasks
curl -X POST http://localhost:8080/api/v1/ops/vector-cleanup-tasks/{taskId}/retry

# 查看/导出审计日志、查看审计告警、账号/角色元数据
curl "http://localhost:8080/api/v1/ops/audit-logs?limit=50"
curl "http://localhost:8080/api/v1/ops/audit-logs/export" -o audit-logs.csv
curl http://localhost:8080/api/v1/ops/audit-alerts
curl -X POST http://localhost:8080/api/v1/ops/audit-alerts/notify
curl http://localhost:8080/api/v1/ops/metadata
curl http://localhost:8080/api/v1/ops/accounts
curl http://localhost:8080/api/v1/ops/roles

# 仅测试清理：需 OPS_ADMIN，且仅允许删除 e2e 租户中的 e2e_* 账号。
# 账号管理页面不提供通用删除入口。
curl -X DELETE http://localhost:8080/api/v1/ops/accounts/e2e_account_42

# /ops/metadata 返回 guardrails：上传限流、摄入队列、审计阈值和 multipart 最大文件大小
# /ops/audit-alerts 聚合审计失败峰值、摄入失败/死信任务和向量清理失败任务
# /ops/audit-alerts/notify 仅在 AUDIT_ALERT_WEBHOOK_URL 非空时投递，响应返回 configured/delivered/statusCode
# 调用主体须同时拥有 OPS_ADMIN、OPS_AUDIT_READ、OPS_ALERT_NOTIFY；超时由 AUDIT_ALERT_WEBHOOK_TIMEOUT_SECONDS 控制

# schemaVersion=1；单次最多导出 1,000 个文档快照和 10,000 个分块快照
# 导入时创建新知识库，仅通过业务服务恢复知识库配置和评估用例
curl http://localhost:8080/api/v1/knowledge-bases/{kbId}/export -o kb-export.json
curl -X POST http://localhost:8080/api/v1/knowledge-bases/import \
  -H "Content-Type: application/json" \
  --data-binary @kb-export.json

# 新建/更新账号、重置密码、禁用/启用账号、轮换 tokenVersion
curl -X POST http://localhost:8080/api/v1/ops/accounts \
  -H "Content-Type: application/json" \
  -d '{"username":"analyst","password":"change-me","tenantId":"default","roleCode":"ANALYST","knowledgeBaseIds":[]}'
curl -X PATCH http://localhost:8080/api/v1/ops/accounts/analyst \
  -H "Content-Type: application/json" \
  -d '{"roleCode":"VIEWER","knowledgeBaseIds":["<kbId>"]}'
curl -X POST http://localhost:8080/api/v1/ops/accounts/analyst/reset-password \
  -H "Content-Type: application/json" \
  -d '{"password":"new-change-me"}'
curl -X POST http://localhost:8080/api/v1/ops/accounts/analyst/disable
curl -X POST http://localhost:8080/api/v1/ops/accounts/analyst/enable
curl -X POST http://localhost:8080/api/v1/ops/accounts/analyst/rotate-token

# 新建/更新/禁用角色；账号通过 roleCode 获得角色绑定的权限点
curl -X POST http://localhost:8080/api/v1/ops/roles \
  -H "Content-Type: application/json" \
  -d '{"code":"SUPPORT","name":"支持人员","permissions":["KB_READ","CHAT_WRITE"]}'
curl -X PATCH http://localhost:8080/api/v1/ops/roles/SUPPORT \
  -H "Content-Type: application/json" \
  -d '{"name":"支持人员","permissions":["KB_READ","CHAT_WRITE","DOCUMENT_UPLOAD"]}'
curl -X POST http://localhost:8080/api/v1/ops/roles/SUPPORT/disable

# RAG 流式问答
curl -N -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/chat \
  -H "Content-Type: application/json" \
  -d '{"query":"你的问题","stream":true}'

```

The `retrieval` event of SSE returns `{ citations, diagnostics }`; Both HTTP and SSE errors return structured JSON (`error` `message` `stage`, `suggestion`, `requestId`), The front end will provide executable prompts in stages such as search, LLM, and authentication. The knowledge base import only accepts `schemaVersion=1`. Currently, there is no re-upload of the MinIO original binary, no restoration of the document master record, nor direct reconstruction of the vector. The documents/blocks in the export belong to the audit and migration snapshots. Complete disaster recovery still requires the support of object storage backups.

### Local front-end development

```bash

cd services/web
npm install
npm run dev

```

The Vite development server runs on the http://localhost:5173，API default proxy to http://localhost:8081。

The default deployment no longer maps the host machine `8081`. If you need to use a Vite proxy for local front-end development, please expose the debug port through Docker Compose override or by launching a separate API.

### Local API build

The API is recommended to use the Maven Wrapper that comes with the project to ensure that the local, CI, and container build baselines are consistent:

```powershell

cd services/api
.\mvnw.cmd verify

```

## Directory structure

See [docs/architecture.md](docs/architecture.md).

## Version planning

| Version | Capability |
| --- | --- |
| V1 | Knowledge base CRUD, asynchronous ingestion, pure vector retrieval, SSE RAG, Web console |
| V1.1 | Real browser E2E gate, ingest diagnostics, RAG evaluation closed loop, upload governance prompt, aggregated operations alert |
| V1.2 | Index details, structured Chat errors, persistent RAG evaluation, hybrid search / Rerank control, Webhook, export recovery |
| V1.5 | Parent-Child / QA-assisted indexing, profile v2 filterable superset, Combined weighted fusion, revision-bound quality gates, Web readiness/gate comparison |
| V1.6b-V2.4 local milestones | RAG quality foundation: realistic benchmark, quality dashboard, and quality-loop summaries; BM25 sparse production tuning, semantic chunking, generation interrupt, and full object/vector disaster recovery remain hardening tracks |
| V2.0 | Packaged RAG Quality Closure release: persistent feedback candidates in evaluation metrics, deterministic answer judge, experiment matrix, data/index governance, online quality SLO, canary promote/rollback gate, and release report |
| Post-V2.0 | Production feedback tables/events, multimodal OCR, Pipeline DSL, K8s/Helm, multi-tenant compliance audit, high-concurrency load tests, and long-running cost optimization |

For detailed planning, see [docs/todo.md](docs/todo.md) and [docs/decisions.md](docs/decisions.md).
## V1.3 Release hardening

V1.3 uses a 30-item, six-category retrieval checklist plus current/legacy conflict corpus as the release benchmark. Worker supports Rerank startup preheating and persistent Hugging Face caching. The RAG evaluation page of the knowledge base provides Sparse Migration status tracks and protected Cutover operations. The backup/recovery drills and dependencies, licenses, Cves, and image volume scans of Milvus 2.4.1 to 2.5.4 all provide repeatable scripts.

See [V1.3 release runbook](docs/v1.3-release-runbook.md) for release steps, environment variables, failure strategy, and evidence locations. Production-equivalent drills, environment benchmarks, and image scans remain required before an official production release.

## V1.6b RAG evaluation benchmark

The current benchmark is stored under `benchmarks/v1.6b/` and contains 100 cases: 40 real queries, 20 hard negatives, 20 multi-document queries, and 20 ambiguous questions. Multi-document cases require distinct source files, hard negatives require zero hits, and ambiguous cases identify an authoritative source plus disambiguating tokens.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/rag-eval-cases.ps1 -ValidateOnly
```

`scripts/rag-retrieval-benchmark.ps1` uses the same V1.6b manifest and corpus by default. V1.7/V1.8 can pass `-ExperimentLabel` and `-TopKOverride` to label retrieval experiments; each run artifact records `experimentLabel`, `topKOverride`, and `releaseGate`, and `-SkipCaseReconcile` supports low-resource reruns when cases are already synced.

## V1.7/V1.8 RAG quality dashboard

V1.7/V1.8 adds category trend/dashboard views, diagnostic drill-down filters, release gate rollups, `topKOverride`/`experimentLabel` retrieval experiment controls, and Profile A/B comparisons to RAG evaluation. The completed milestone is recorded in the tracked [English progress log](docs/en/progress.md).

## V1.9-V2.4 RAG quality loop

V1.9-V2.4 continues the RAG quality system in the same local version slice: release readiness, real-query feedback candidates, experiment matrix, answer-quality proxy metrics, online observability summaries, and data/index governance summaries are written into each evaluation run `metrics` object and rendered as six Quality dashboard cards in the web panel. The completed milestone is recorded in the tracked [English progress log](docs/en/progress.md).

## V2.0 RAG quality closure

The former V2.5-V3.0 RAG quality items are now selected into the V2.0 Closure MVP: persistent feedback candidates, deterministic answer judging, retrieval experiment matrix, data/index governance, online quality SLOs, canary promote/rollback gates, and release reports. The implementation stays in RagEvalRun.metrics and the existing Web dashboard, without adding heavyweight database migrations. See [`docs/en/rag-quality-roadmap.md`](docs/en/rag-quality-roadmap.md), [`docs/en/v2.0-rag-quality-closure-design.md`](docs/en/v2.0-rag-quality-closure-design.md), and [`docs/en/v2.0-rag-quality-closure-implementation.md`](docs/en/v2.0-rag-quality-closure-implementation.md).
