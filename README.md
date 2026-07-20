# dupi-RAG

> V1.5.0 is the current release. It adds Parent-Child and QA-assisted indexing, a filterable Profile V2 Milvus superset, Combined weighted RRF, revision-bound quality gates, and Web readiness/gate comparisons. API and Web version: `1.5.0`.

See the [V1.5.0 release notes](docs/v1.5-release-notes.md) and [release runbook](docs/v1.5-release-runbook.md) before upgrading an existing deployment. Keep `CLASSIC` as the default until the current index revision passes the candidate-vs-classic quality gate.

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

See [the V1.4.1 release runbook](docs/v1.4.1-release-runbook.md) and the local design note. The latest local V1.4.1 release scan records image digest `sha256:eec613fab9cdd1d873b95172f98d42ade5989238e2b0f76761b6b4f63b86515a`, image size 640,389,450 bytes, no Python findings, and 22 accepted upstream-unfixed OS findings expiring 2026-08-15.

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

> V1.3 增加可阻断的 RAG 质量策略/基线、版本化 Retrieval Profile，以及 Milvus 原生 Sparse BM25 的回填、双写、Shadow、Cutover 和 Rollback。生产部署要求 Milvus 2.5.4；升级前必须备份 Milvus/etcd/MinIO/PostgreSQL，并在隔离环境完成回填与回滚演练。

## V1.3 Sparse 迁移运维

每个 Profile 使用独立集合 `{MILVUS_COLLECTION}_sparse_{kbId}_v{version}`。迁移状态依次为 `PREPARING -> BACKFILLING -> DUAL_WRITING -> SHADOW_VALIDATING -> CUTOVER -> COMPLETED`，失败进入 `FAILED`，`BACKFILLING` 可幂等重试。legacy BM25 fallback 由迁移记录持久化控制，仅允许在双写和 Shadow 阶段启用；完成后由激活 Profile 永久驱动 Sparse 写入。

Cutover 要求覆盖率 100%、embedding 维度一致、候选 Profile 有完全匹配的 PASS 评测、候选 P95 不超过基线 1.25 倍、fallback rate 不增加。Rollback 只能重新激活更旧且已有 PASS 证据的 Profile。删除文档会同步清理 dense 集合和该知识库所有版本化 Sparse 集合。

真实语料基准命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/rag-retrieval-benchmark.ps1 `
  -KbId <kbId> -HybridProfileId <profileId> -RerankProfileId <rerankProfileId> `
  -ApiKey $env:DUPI_API_KEY -OutputPath artifacts/v13-real-benchmark.json
```

脚本会核对实际模式、Profile 开关、逐用例阶段排名和相对 VECTOR 的 rank delta；实际未执行 reranker 时直接失败。

Worker 使用 CPU-only PyTorch 和 `BAAI/bge-reranker-base`，默认在启动生命周期加载模型并执行预热推理；Compose 通过 `hf_model_cache` 持久化模型缓存。预热失败会在 `/health` 中标记 Rerank 不可用，但不阻断 VECTOR/HYBRID；冷启动延迟不得与热态 P95 混用。

> 账号 / RBAC 与 ops 管理权限更新记录见 [docs/rbac-ops-admin-2026-07-06.md](docs/rbac-ops-admin-2026-07-06.md)；摄入 outbox、删除 tombstone、实例级授权与审计运维增强见 [docs/outbox-tombstone-rbac-ops-2026-07-07.md](docs/outbox-tombstone-rbac-ops-2026-07-07.md)。
> V1.1（API `0.1.1-SNAPSHOT` / Web `0.1.1`）新增真实浏览器 E2E 门禁、摄入诊断、知识库详情 `RAG 评估`、上传治理提示与聚合运维告警；设计与实施记录见 local design note 与 local design note。
> V1.2（API `0.1.2-SNAPSHOT` / Web `0.1.2`）扩展真实浏览器门禁，新增文档索引详情、结构化 Chat 错误、持久化 RAG 评估用例/历史、混合检索与 Rerank 控制、审计告警 Webhook，以及知识库元数据/分块快照导出恢复；实施计划见 local design note。
> V1.2.1 收尾将真实浏览器门禁业务数据隔离到 `e2e` 租户；成功运行会删除临时知识库和账号，失败运行仅保留 `e2e` 证据。设计见 local design note。
> V1.5 (RAG Quality Upgrade) adds Parent-Child / QA-assisted indexing, a filterable profile v2 Milvus superset, Combined weighted RRF, revision-bound eval quality gates, and Web readiness/gate comparisons.

企业级 RAG 知识库引擎 — 类似 Dify/扣子底层知识库模块。

支持私有文档（PDF、DOCX、TXT、Markdown、Excel）上传、异步解析与向量化，结合大模型进行检索增强问答（SSE 流式）。

### V1.5 升级与启用

- V1.5 使用独立的 `MILVUS_PROFILE_COLLECTION` 保存 classic、parent-child、qa-assisted 和 combined 共用的可过滤 superset。已有知识库升级后需要执行一次“重建索引”；重建按文档滚动替换向量和 chunk，不会先清空整个在线索引。
- 知识库仅在所有文档均为 `COMPLETED` 且 `index_schema_version=2` 时标记为 profile v2 ready。首次 ready 会持久化 cutover 状态并清理 Legacy；后续上传或重建期间仍使用 v2 中已完成的文档，不会回退到已清理的 Legacy。切换默认 profile 只改变检索入口，不会再次重建统一索引。
- 非 classic profile 必须使用当前 `index_revision` 的 RAG 评估与 `CLASSIC` 对比，且至少包含 3 个 case、引用可评估、命中率和引用通过率均不回退。未通过时更新接口返回 HTTP `409`，错误码为 `retrieval_profile_gate_blocked`。

版本变更见 [V1.5.0 Release Notes](docs/v1.5-release-notes.md)，升级、灰度、验证与回滚步骤见 [V1.5.0 发布运行手册](docs/v1.5-release-runbook.md)。

## 技术栈

- **Web 控制台**：React 18 + Vite + TypeScript + Tailwind
- **API**：Java 17 + Spring Boot 3
- **构建工具**：Maven Wrapper 固定 Apache Maven 3.9.9（`services/api/mvnw.cmd` / `services/api/mvnw`）
- **Worker**：Python 3.11
- **向量库**：Milvus | **元数据**：PostgreSQL | **队列**：Redis | **对象存储**：MinIO

## 快速启动

### 1. 配置环境变量

```bash
cp deploy/.env.example deploy/.env
```

编辑 `deploy/.env`，**必须**配置两套 LLM 凭证（DeepSeek 官方无 Embedding 接口）：

| 变量 | 用途 | 示例 |
|------|------|------|
| `CHAT_API_KEY` | RAG 对话（DeepSeek） | 在 [platform.deepseek.com](https://platform.deepseek.com) 申请 |
| `CHAT_BASE_URL` | 对话 API 地址 | `https://api.deepseek.com` |
| `CHAT_MODEL` | 对话模型 | `deepseek-chat` |
| `EMBEDDING_API_KEY` | 文档向量化 + 检索 | 在 [智谱开放平台](https://open.bigmodel.cn) 申请 |
| `EMBEDDING_BASE_URL` | Embedding API 地址 | `https://open.bigmodel.cn/api/paas/v4` |
| `EMBEDDING_MODEL` | 向量模型 | `embedding-2`（智谱） |
| `EMBEDDING_DIMENSION` | 向量维度 | 须与模型一致，智谱 `embedding-2` 为 `1024` |
| `EMBEDDING_BATCH_SIZE` | Worker 单次 Embedding 请求文本数 | 默认 `32`，可按供应商限制下调 |
| `DUPI_API_KEY` | 可选公开 API 共享密钥 | 本地可信开发可留空；共享/部署环境建议设置 |
| `DUPI_INTERNAL_KEY` | 可选内部 API 共享密钥 | API 与 Worker 必须保持一致 |
| `UPLOAD_RATE_LIMIT_REQUESTS` | 上传限流窗口内请求数 | 默认 `20` |
| `UPLOAD_RATE_LIMIT_WINDOW_SECONDS` | 上传限流窗口秒数 | 默认 `60` |
| `INGEST_QUEUE_MAX_PENDING_JOBS` | 摄入 Redis 队列高水位 | 默认 `200`，达到阈值时上传入口快速拒绝 |
| `INGEST_RECOVERY_CRON` | 摄入任务补偿扫描 cron | 默认每 2 分钟 |
| `INGEST_RECOVERY_MAX_ATTEMPTS` | 摄入补偿最大自动重试次数 | 默认 `3`，达到后进入死信状态 |
| `INGEST_OUTBOX_DISPATCH_CRON` | transactional outbox 投递 cron | 默认每 10 秒 |
| `ORPHAN_VECTOR_CLEANUP_CRON` | 残留向量补偿清理定时任务 | 默认每天 `03:30` |
| `AUDIT_RETENTION_DAYS` | 审计日志保留天数 | 默认 `180`，小于等于 0 表示不清理 |
| `AUDIT_RETENTION_CRON` | 审计日志保留清理 cron | 默认每天 `02:15` |
| `AUDIT_ALERT_WINDOW_MINUTES` | 审计失败告警统计窗口 | 默认 `30` 分钟 |
| `AUDIT_ALERT_FAILED_THRESHOLD` | 审计失败告警阈值 | 默认 `10` 次 |
| `AUDIT_ALERT_WEBHOOK_URL` | 可选审计告警 Webhook 地址 | 留空时通知接口返回 `configured=false` |
| `AUDIT_ALERT_WEBHOOK_TIMEOUT_SECONDS` | 审计告警 Webhook 超时 | 默认 `10` 秒 |

配置后重启应用容器：

```bash
cd deploy
docker compose up -d --force-recreate api worker
```

### 2. 启动基础设施与应用

```bash
cd deploy
docker compose up -d --build
```

### 3. 访问 Web 控制台

浏览器打开 **http://localhost:8080**

1. **新建知识库** → 选择向量检索或混合检索，点击卡片进入详情
2. **文档管理** → 上传文件，等待状态 `COMPLETED`；点击查看按钮检查对象、摄入任务、分块总数、最多 20 个分块样例与索引就绪状态
3. **智能问答** → 基于已摄入文档提问（需配置 `CHAT_API_KEY` 与 `EMBEDDING_API_KEY`）
4. **RAG 评估** → 管理持久化用例（空库自动创建内置用例，每库最多 100 条），选择是否启用 Rerank，运行并查看最近 10 次结果与逐用例诊断

### 4. 验证

```bash
# 健康检查（经 Nginx 代理）
curl http://localhost:8080/actuator/health
```

默认 Compose 只暴露 Web 入口 `http://localhost:8080`，API、Worker、PostgreSQL、Redis、Milvus、MinIO 仅在 Docker 内部网络可见。需要直连调试时，可临时使用本地 override 文件映射端口，避免把调试端口长期暴露在默认部署中。

需要检查 Compose 展开配置时，请使用脱敏脚本，避免把 `.env` 中的第三方 Key 打印到终端或聊天记录：

```powershell
powershell -ExecutionPolicy Bypass -NoProfile -File scripts/compose-config-redacted.ps1
```

如果曾经把 `docker compose config` 的原始输出贴到终端共享上下文或截图中，请立即轮换对应的 `CHAT_API_KEY`、`EMBEDDING_API_KEY` 以及共享密钥。

### 4.1 Docker 启动排障

- **镜像拉取慢或失败**：优先配置 Docker Desktop registry mirrors，或提前 `docker pull` Compose 中的基础镜像；不要把临时代理地址写入仓库内配置。
- **Worker pip 安装慢或失败**：可在本机/CI 侧配置 pip 镜像源；依赖版本以 `services/worker/requirements*.txt` 为准，避免临时放宽 `pymilvus`、`marshmallow` 等约束。
- **前端构建 Node 版本问题**：Web 脚本已通过 `services/web/scripts/node16-webcrypto.cjs` 兼容本机 Node 16；生产构建仍建议使用项目 Dockerfile 中固定的构建环境或 Node 18+。
- **CORS 或端口访问异常**：默认只访问 `http://localhost:8080`，由 Web Nginx 反代 `/api`；如需直连 API、PostgreSQL、Redis、Milvus 或 MinIO，请使用临时 Compose override 显式暴露端口。
- **Milvus 维度不一致**：`EMBEDDING_DIMENSION` 必须与当前 `MILVUS_COLLECTION` 的 `embedding` 向量维度一致。切换 embedding 模型/维度后，旧知识库可用 `POST /api/v1/knowledge-bases/{kbId}/reindex` 重建；如果 collection 本身维度不匹配，API 会在启动时 fail-fast，需要删除/重建 collection，或把 `MILVUS_COLLECTION` 指向新的维度专用集合。
- **Milvus 集合加载较慢**：API 启动只异步发起 collection load，不再同步等待 QueryNode 导致 Web 长时间 502；集合未就绪期间检索会沿用已有本地文本 fallback，并在诊断中报告原因。

### 4.2 端到端主流程自动化（推荐）

脚本按 Web 控制台按钮顺序调用接口（健康 → 建库 → 上传 → 摄入 → 检索 → 问答 SSE）：

```powershell
powershell -NoProfile -File scripts/e2e-main-flow.ps1
```

需有效 `EMBEDDING_*` 与 `CHAT_*` 配置；步骤说明与最近运行结果见 [docs/e2e-testing.md](docs/e2e-testing.md)。

新增维护与回归验证脚本：

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

`e2e-browser-gate.ps1` 需要 `E2E_ADMIN_USERNAME` 与 `E2E_ADMIN_PASSWORD`，缺少凭据时会明确失败。门禁仅以配置管理员创建 `e2e` 租户中的临时管理员，后续知识库、RAG 用例和验证账号均在该租户完成；成功后自动删除临时知识库及 `e2e_*` 账号，失败时在 Playwright 结果中保留资源标识和页面 URL 作为证据。`rag-regression-eval.ps1` 会写入 `scripts/rag-regression-eval-last-run.json`，其中 `caseResults` 包含每条用例的 query、pass/fail、命中数、期望/命中文件、命中 token、检索模式、fallback 原因和 embedding 信息。

### 5. API 示例

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

问答 SSE 的 `retrieval` 事件返回 `{ citations, diagnostics }`；HTTP 与 SSE 错误都返回结构化 JSON（`error`、`message`、`stage`、`suggestion`、`requestId`），前端会按检索、LLM、鉴权等阶段给出可执行提示。知识库导入仅接受 `schemaVersion=1`，当前不重新上传 MinIO 原始二进制、不恢复文档主记录，也不直接重建向量；导出中的文档/分块属于审计与迁移快照，完整灾备仍需对象存储备份配合。

### 6. 本地前端开发（可选）

```bash
cd services/web
npm install
npm run dev
```

Vite 开发服务器运行在 http://localhost:5173，API 默认代理到 http://localhost:8081。

默认部署不再映射宿主机 `8081`。本地前端开发如需使用 Vite 代理，请通过 Docker Compose override 或单独启动 API 暴露调试端口。

### 7. 本地 API 构建（可选）

API 推荐使用项目自带 Maven Wrapper，确保本地、CI 与容器构建基线一致：

```powershell
cd services/api
.\mvnw.cmd verify
```

## 目录结构

见 [docs/architecture.md](docs/architecture.md)。

## 版本规划

| 版本 | 能力 |
|------|------|
| V1 | 知识库 CRUD、异步摄入、纯向量检索、SSE RAG、Web 控制台 |
| V1.1 | 真实浏览器 E2E 门禁、摄入诊断、RAG 评估闭环、上传治理提示、聚合运维告警 |
| V1.2 | 索引详情、结构化 Chat 错误、持久化 RAG 评估、混合检索/Rerank 控制、Webhook、导出恢复 |
| V1.5 | Parent-Child / QA-assisted indexing, profile v2 filterable superset, Combined weighted fusion, revision-bound quality gates, Web readiness/gate comparison |
| V2 | BM25 sparse 生产调优、语义分块、生成中断、完整对象/向量灾备恢复 |
| V3 | 多模态 OCR、Pipeline DSL |
| V4 | K8s、多租户、合规审计 |

详细规划见 [docs/todo.md](docs/todo.md) 与 [docs/decisions.md](docs/decisions.md)。
# V1.3 发布硬化

V1.3 使用 30 条、六分类检索清单及当前/legacy 冲突语料作为发布基准，Worker 支持 Rerank 启动预热和持久化 Hugging Face 缓存，知识库 RAG 评估页提供 Sparse Migration 状态轨道和受保护的 Cutover 操作。Milvus 2.4.1 到 2.5.4 的备份/恢复演练及依赖、许可证、CVE、镜像体积扫描均提供可重复脚本。

完整发布步骤、环境变量、失败策略和证据位置见 [V1.3 发布运行手册](docs/v1.3-release-runbook.md)。实际生产同规格演练、30 Case 环境基准和镜像扫描仍是正式发布前的必做项。
