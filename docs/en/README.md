# dupi-RAG

<!-- language-switch -->
[中文](../../README.zh-CN.md) | **English**


# User-visible quota; requires DOCUMENT_UPLOAD
curl http://localhost:8080/api/v1/upload-quota

# Idempotent single-file upload
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/documents \
  -H "Idempotency-Key: upload-20260718-001" \
  -F "file=@sample.pdf"

# Cancel queued/running ingest
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/ingest-jobs/{jobId}/cancel

```

See [the V1.4.1 release runbook](../v1.4.1-release-runbook.md) and the design（local note）. The latest local V1.4.1 release scan records image digest `sha256:eec613fab9cdd1d873b95172f98d42ade5989238e2b0f76761b6b4f63b86515a`, image size 640,389,450 bytes, no Python findings, and 22 accepted upstream-unfixed OS findings expiring 2026-08-15.

> V1.4.0 adds tenant-scoped, checksum-verified knowledge-base archives and idempotent restore into a new hidden knowledge base. It is an application recovery layer, not a replacement for PostgreSQL, MinIO, etcd, or Milvus infrastructure backups.

## V1.4 Verifiable Recovery

Operators with `KB_RECOVERY` use the **Recovery** tab to create, inspect, download, retry, and delete archives, and to create, retry, or abandon restores. Archive objects are sealed under `archives/{tenantId}/{archiveId}/` in a private recovery bucket; `manifest.json` is written last. A target stays hidden as `RESTORING` until objects, records, dense/sparse vectors, counts, schemas, and checksums verify.

Routes are below `/api/v1/knowledge-bases/{kbId}/recovery`. Commands return `202 Accepted`; the Web panel polls non-terminal jobs every three seconds. See [the recovery runbook](../v1.4-recovery-runbook.md).

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

V1.3 adds blockable RAG quality policies/baselines, versioned Retrieval profiles, as well as Milvus native Sparse BM25 backfilling, double write, Shadow, Cutover, and Rollback. Production deployment requirements: Milvus 2.5.4; Can be upgraded before the backup Milvus/etcd/MinIO/PostgreSQL, and complete backfill and rollback drills in isolation.

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

Account/RBAC and ops administrative permission update records can be found at [docs/rbac-ops-admin-2026-07-06.md](../rbac-ops-admin-2026-07-06.md); See [docs/ outbox-tombstone-rbac-OPs-2026-07-07.md](../outbox-tombstone-rbac-ops-2026-07-07.md) for ingroving outbox, removing tombstone, instance authorization and audit operations enhancement.
> V1.1 (API `0.1.1-SNAPSHOT` / Web `0.1.1`) adds the real-browser E2E gate, ingest diagnostics, RAG evaluation, upload-governance guidance, and aggregated operations alerts.
> V1.2 expands browser coverage with document-index details, structured chat errors, persistent RAG evaluation cases and history, hybrid retrieval and Rerank controls, audit webhooks, and metadata or chunk-snapshot recovery.
> V1.2.1 isolates real-browser gate data in the `e2e` tenant and removes temporary knowledge bases and accounts after successful runs.
> V1.5 (RAG Quality Upgrade) adds Parent-Child/QA-assisted indexing, a filterable profile v2 Milvus superset Combined weighted RRF, revision-bound eval quality gates, and Web readiness/gate comparisons.

Enterprise-level RAG knowledge base engine - similar to the Dify/ Douzi underlying knowledge base module.

Supports the upload of private documents (PDF, DOCX, TXT, Markdown, Excel), asynchronous parsing and vectorization, and combines large models for retrieval to enhance question answering (SSE streaming).

Upgrade and activation of V1.5

-V1.5 uses an independent `MILVUS_PROFILE_COLLECTION` to save the filterable superset shared by classic, parent-child, qa-assisted, and combined. After the existing knowledge base is upgraded, a "rebuild index" operation needs to be performed once. The reconstruction replaces vectors and chunks by document scrolling, without clearing the entire online index first.
The knowledge base is marked as profile v2 ready only when all documents are `COMPLETED` and `index_schema_version=2`. The first ready will persist the cutover state and clean up Legacy. During subsequent uploads or rebuilds, the completed documents in v2 will still be used and will not be rolled back to the cleaned Legacy. Switching the default profile only changes the search entry and does not rebuild the unified index again.
For non-classic profiles, the RAG evaluation of the current `index_revision` must be compared with `CLASSIC`, and it must include at least 3 cases, references can be evaluated, and neither hit rate nor reference pass rate can be rolled back. When not passed, the update interface returns HTTP `409`, and the error code is `retrieval_profile_gate_blocked`.

Version changes can be found in [V1.5.0 Release Notes](../v1.5-release-notes.md). Upgrade, gray-scale, verification and rollback steps can be found in [V1.5.0 Release and Operation Manual](../v1.5-release-runbook.md).

## Technology Stack

- **Web Console ** : React 18 + Vite + TypeScript + Tailwind
- **API** : Java 17 + Spring Boot 3
- ** Build Tool ** : Maven Wrapper fixes Apache Maven 3.9.9 (`services/api/mvnw.cmd` / `services/api/mvnw`)
- **Worker** : Python 3.11
- * * * * : vector library Milvus|* * * * : metadata PostgreSQL|queue * * * * : Redis|object storage * * * * : MinIO

"Quick start.

1. Configure environment variables

```bash

cp deploy/.env.example deploy/.env

```

To edit `deploy/.env`, two sets of LLM credentials must be configured (DeepSeek official has no Embedding interface) :

__DU_PI_PIPE__ sample|__DU_PI_PIPE__ variable|purposes
__DU_PI_PIPE__ ------|------|------ __DU_PI_PIPE__
__DU_PI_PIPE__ `CHAT_API_KEY`|RAG dialogue (DeepSeek)|in [platform.deepseek.com](https://platform.deepseek.com) apply for __DU_PI_PIPE__
__DU_PI_PIPE__ `CHAT_BASE_URL`|dialogue API address|`https://api.deepseek.com` __DU_PI_PIPE__
__DU_PI_PIPE__ `CHAT_MODEL`|dialogue model|`deepseek-chat` __DU_PI_PIPE__
__DU_PI_PIPE__ `EMBEDDING_API_KEY`|document vectorization + search|apply on [Zhipu Open Platform](https://open.bigmodel.cn) __DU_PI_PIPE__
__DU_PI_PIPE__ `EMBEDDING_BASE_URL`|Embedding API address|`https://open.bigmodel.cn/api/paas/v4` __DU_PI_PIPE__
__DU_PI_PIPE__ `EMBEDDING_MODEL`|vector model|`embedding-2`|(spectrum)
__DU_PI_PIPE__|`EMBEDDING_DIMENSION`|vector dimensions must be consistent with the model, Wisdom spectrum `embedding-2` for `1024` __DU_PI_PIPE__
__DU_PI_PIPE__ `EMBEDDING_BATCH_SIZE`|Worker single Embedding request text number|default `32` can be adjusted downward according to the supplier's restrictions
__DU_PI_PIPE__ `DUPI_API_KEY`|optional public API shared key|local trusted development can be left blank; It is recommended to set|for the shared/deployed environment
__DU_PI_PIPE__ `DUPI_INTERNAL_KEY`|optional internal API shared key|API and Worker must be consistent __DU_PI_PIPE__
__DU_PI_PIPE__ `UPLOAD_RATE_LIMIT_REQUESTS`|upload the number of requests within the rate limiting window|default `20` __DU_PI_PIPE__
__DU_PI_PIPE__ `UPLOAD_RATE_LIMIT_WINDOW_SECONDS`|upload current-limiting window seconds|default `60` __DU_PI_PIPE__
__DU_PI_PIPE__ `INGEST_QUEUE_MAX_PENDING_JOBS`|intake Redis queue high water level|default `200` When the threshold is reached, the upload entry quickly rejects __DU_PI_PIPE__
__DU_PI_PIPE__ `INGEST_RECOVERY_CRON`|take in task compensation scan cron|by default|every 2 minutes
__DU_PI_PIPE__ `INGEST_RECOVERY_MAX_ATTEMPTS`|maximum automatic retry times for intake compensation|default `3` Once reached, it enters the dead letter status __DU_PI_PIPE__
__DU_PI_PIPE__ `INGEST_OUTBOX_DISPATCH_CRON`|transactional outbox delivers cron|by default every 10 seconds __DU_PI_PIPE__
__DU_PI_PIPE__ `ORPHAN_VECTOR_CLEANUP_CRON`|residual vector compensation cleaning scheduled task|default daily `03:30` __DU_PI_PIPE__
__DU_PI_PIPE__ `AUDIT_RETENTION_DAYS`|audit log retention days|default `180`, Less than or equal to 0 indicates that|will not be cleaned up
__DU_PI_PIPE__ `AUDIT_RETENTION_CRON`|audit log retention cleanup cron|default daily `02:15` __DU_PI_PIPE__
__DU_PI_PIPE__ `AUDIT_ALERT_WINDOW_MINUTES`|audit failure alert statistics window|default `30` minutes __DU_PI_PIPE__
__DU_PI_PIPE__ `AUDIT_ALERT_FAILED_THRESHOLD`|audit failure alert threshold|default `10` times __DU_PI_PIPE__
__DU_PI_PIPE__ `AUDIT_ALERT_WEBHOOK_URL`|optional audit alert Webhook address|when left blank, the notification interface returns `configured=false`  __DU_PI_PIPE__
__DU_PI_PIPE__ `AUDIT_ALERT_WEBHOOK_TIMEOUT_SECONDS`|audit alert Webhook timeout|default `10` seconds __DU_PI_PIPE__

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

1. ** Create a New Knowledge Base ** → Select vector search or hybrid search, and click the card to enter the details
2. ** Document Management ** → Upload File, Waiting status `COMPLETED`; Click the View button to check objects, intake tasks, total number of blocks, up to 20 block samples, and index readiness status
3. ** Intelligent Q&A ** → Ask questions based on ingited documents (`CHAT_API_KEY` and `EMBEDDING_API_KEY` need to be configured)
4. **RAG Evaluation ** → Manage persistent use cases (automatically create built-in use cases for empty libraries, with a maximum of 100 cases per library), select whether to enable Rerank, run and view the results of the last 10 times and use case-by-case diagnosis

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

4.1 Docker Startup Troubleshooting

- ** Slow or failed image pull ** : Prioritize the configuration of Docker Desktop registry mirrors, or advance the base image in `docker pull` Compose; Do not write the temporary proxy address into the repository configuration.
- ** Slow or failed installation of Worker pip ** : The pip image source can be configured on the local machine /CI side; The dependent version is based on `services/worker/requirements*.txt` to avoid temporarily relaxing constraints such as `pymilvus` and `marshmallow`.
- ** Front-end build Node version issue ** : The Web script is compatible with native Node 16 via `services/web/scripts/node16-webcrypto.cjs`; For production builds, it is still recommended to use the fixed build environment in the project Dockerfile or Node 18+.
- **CORS or port access exception ** : By default, only `http://localhost:8080` is accessed, and `/api` is inverted by Web Nginx; If you need to directly connect to API, PostgreSQL, Redis, Milvus or MinIO, please use temporary Compose override to explicitly expose the port.
- **Milvus dimension inconsistency ** : `EMBEDDING_DIMENSION` must be consistent with the `embedding` vector dimension of the current `MILVUS_COLLECTION`. After switching the embedding model/dimension, the old knowledge base can be reconstructed using `POST /api/v1/knowledge-bases/{kbId}/reindex`. If the dimensions of the collection itself do not match, the API will fail-fast at startup. You need to delete/rebuild the collection or point `MILVUS_COLLECTION` to a new dimensional-specific collection.
- ** Slow Milvus collection loading ** : The API startup only asynchronously initiates the collection load and no longer synchronously waits for the QueryNode, resulting in a long Web 502 error. During the period when the collection is not ready, the search will follow the existing local text fallback and report the cause in the diagnosis.

4.2 End-to-end Main Process Automation (Recommended)

The script calls the interface in the order of the Web console buttons (Health → Library Building → Upload → Intake → Search → Question Answering SSE) :

```powershell

powershell -NoProfile -File scripts/e2e-main-flow.ps1

```

Valid `EMBEDDING_*` and `CHAT_*` configurations are required. Step-by-step instructions and recent running results can be found at [docs/e2e-testing.md](../e2e-testing.md).

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

5. API Examples

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

6. Local front-end Development (Optional)

```bash

cd services/web
npm install
npm run dev

```

The Vite development server runs on the http://localhost:5173，API default proxy to http://localhost:8081。

The default deployment no longer maps the host machine `8081`. If you need to use a Vite proxy for local front-end development, please expose the debug port through Docker Compose override or by launching a separate API.

7. Local API Build (Optional)

The API is recommended to use the Maven Wrapper that comes with the project to ensure that the local, CI, and container build baselines are consistent:

```powershell

cd services/api
.\mvnw.cmd verify

```

Directory structure

See [docs/architecture.md](../architecture.md).

Version planning

__DU_PI_PIPE__|version|ability
__DU_PI_PIPE__ ------|------ __DU_PI_PIPE__
__DU_PI_PIPE__ V1|knowledge base CRUD, asynchronous ingestion, pure vector retrieval, SSE RAG, Web console __DU_PI_PIPE__
__DU_PI_PIPE__ V1.1|Real browser E2E access control, intake diagnosis, RAG evaluation closed loop, upload governance prompt, aggregated operation and maintenance alert __DU_PI_PIPE__
__DU_PI_PIPE__ V1.2|Index details, structured Chat errors, persistence RAG assessment, mixed search /Rerank control, Webhook, export recovery __DU_PI_PIPE__
__DU_PI_PIPE__ V1.5|Parent-Child/QA-assisted indexing, profile v2 filterable superset Combined weighted fusion, revision-bound quality gates, Web readiness/gate comparison  __DU_PI_PIPE__
__DU_PI_PIPE__ V2|BM25 sparse production tuning, semantic blocking, generating interrupts, full object/vector disaster recovery __DU_PI_PIPE__
__DU_PI_PIPE__ V3|Multimodal OCR, Pipeline DSL __DU_PI_PIPE__
__DU_PI_PIPE__ V4|K8s, multi-tenant, compliance audit __DU_PI_PIPE__

For detailed planning, please refer to [docs/todo.md](../todo.md) and [docs/decisions.md](../decisions.md).
# V1.3 Release hardening

V1.3 uses a 30-item, six-category search list and current /legacy conflict corpus as the release benchmark. Worker supports Rerank startup preheating and persistent Hugging Face caching. The RAG evaluation page of the knowledge base provides Sparse Migration status tracks and protected Cutover operations. The backup/recovery drills and dependencies, licenses, Cves, and image volume scans of Milvus 2.4.1 to 2.5.4 all provide repeatable scripts.

The complete release steps, environment variables, failure strategies, and evidence locations can be found in [V1.3 Release Run Manual](../v1.3-release-runbook.md). Actual production of the same specification drills, 30-case environmental benchmarks and mirror scans remain mandatory before the official release.
