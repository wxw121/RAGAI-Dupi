# 架构概览

## 项目简介

dupi-RAG 是企业级 RAG（检索增强生成）知识库引擎，类似 Dify/扣子底层知识库模块：支持私有文档上传、解析、向量化，并结合大模型进行精准问答。

## 技术栈

- **Web 控制台**：React 18 + Vite + TypeScript（知识库管理、文档上传、RAG 问答；助手回复经 `react-markdown` 渲染）
- **主服务**：Java 17 + Spring Boot 3.x（REST API、编排、SSE 流式）
- **API 构建**：Maven Wrapper 固定 Apache Maven 3.9.9；容器构建使用 `maven:3.9.9-eclipse-temurin-17`
- **Worker**：Python 3.11 + FastAPI / Redis 消费者（解析、分块、Embedding、索引）
- **向量库**：Milvus 2.x
- **元数据**：PostgreSQL 16
- **对象存储**：MinIO
- **缓存/队列**：Redis 7
- **编排参考**：LlamaIndex（Python 侧）
- **部署**：Docker Compose 单机栈

## 目录结构

```
dupi-RAG/
├── docs/                    # 项目记忆文档
├── deploy/
│   └── docker-compose.yml   # 基础设施 + 应用服务
├── services/
│   ├── api/                 # Spring Boot 主服务
│   ├── web/                 # React Web 控制台
│   └── worker/              # Python 摄入 Worker
├── schemas/
│   └── ingest-job.json      # Java ↔ Python 任务消息契约
└── README.md
```

## 模块与边界

| 模块 | 职责 | 入口 |
|------|------|------|
| `KnowledgeBaseService` | 知识库 CRUD、分块/检索配置 | `KbController` |
| `DocumentService` | 文档上传、MinIO 存储、创建摄入任务与 outbox 事件 | `DocumentController` |
| `DocumentIndexInspectionService` | 聚合文档对象、最近摄入任务诊断、分块计数/样例和索引就绪状态 | `GET .../documents/{docId}/index-detail` |
| `UploadRateLimitFilter` | 上传入口限流；已认证用户按租户 + principal 分桶，匿名/API Key 请求按租户 + IP + API Key 分桶 | `/documents`、`/documents/batch` |
| `IngestJobService` | 摄入任务状态、重试、队列补偿扫描、摄入诊断和打开失败告警摘要 | `IngestJobController` / 定时任务 |
| `IngestOutboxService` | transactional outbox 投递，将已提交摄入任务可靠投递到 Redis | 定时任务、上传/重试/reindex 后续投递 |
| `DocumentTombstoneService` | 删除 tombstone，防止 outbox 或 Worker 迟到回调恢复已删文档 | 文档删除、outbox、Worker 回调 |
| `RetrievalService` | 向量检索、混合检索与可选 Rerank，统一返回检索诊断 | `RetrievalController`、RAG 评估 |
| `ChatService` | RAG 编排、LLM 调用、结构化阶段错误与 SSE | `ChatController` |
| `RagEvalService` | 租户/知识库范围内的评估用例 CRUD、运行与不可变结果历史 | `/rag-eval/cases`、`/rag-eval/runs` |
| `KnowledgeBaseExportService` | 导出知识库元数据、文档/分块快照和评估用例；导入为新知识库 | `/export`、`/knowledge-bases/import` |
| `AuditLogService` | 高影响操作审计日志，记录租户、动作、目标和结果 | 删除、reindex、retry、账号/角色运维操作 |
| `OpsController` | 运维元数据、上传/摄入/审计 guardrails、审计/摄入/向量清理聚合告警 | `/api/v1/ops/**` |
| `OpsNotificationService` | 将当前聚合告警投递到可选 Webhook，并返回配置/投递状态 | `POST /api/v1/ops/audit-alerts/notify` |
| `AccountService` | 数据库账号管理，负责配置账号启动同步、创建/更新、密码重置、禁用/启用与 tokenVersion 轮换 | `/api/v1/ops/accounts/**` |
| `RoleService` | 角色管理、权限点归一化与权限说明元数据，账号通过 `roleCode` 继承角色权限 | `/api/v1/ops/roles/**`、`/api/v1/ops/metadata` |
| `VectorCleanupTaskService` | 文档/知识库删除后的残留 Milvus 向量补偿清理 | 定时任务、`/api/v1/ops/vector-cleanup-tasks` |
| `ingest-worker` | 解析→分块→Embedding→Milvus | `worker/main.py` |
| `hybrid-retriever` (V2) | BM25 + 向量 + Rerank | `worker/retrieval/` |
| `web` | 知识库 UI、文档上传、RAG 问答（助手 Markdown 渲染） | `services/web` |

## 数据流 / 核心流程

### 文档摄入（ETL）

```
上传文件 → 队列高水位检查 → MinIO → documents + ingest_jobs + ingest_outbox_events
  → outbox dispatcher → Redis 队列
  → Python Worker: 解析 → 清洗 → 分块 → Embedding API
  → Milvus 写入向量 + chunks 表元数据 → 状态 completed
```

批量上传使用同一个后端 batch API，一次请求内仍按“一文件一 Document + 一 IngestJob”处理；单个文件失败会返回逐文件失败结果，不阻断同批次其他文件入队。上传入口默认启用轻量限流，配置项为 `UPLOAD_RATE_LIMIT_*`。限流 key 会优先使用当前安全上下文中的租户 + 用户主体；未登录或兼容 API Key 请求回退为租户 + 客户端 IP + API Key，避免同 NAT 下不同登录用户互相挤占上传额度。

上传落 MinIO 前会先检查 Redis 摄入队列长度，`INGEST_QUEUE_MAX_PENDING_JOBS` 达到阈值时快速拒绝。上传事务内只写入 `documents`、`ingest_jobs` 和 `ingest_outbox_events`，由 `IngestOutboxService` 按 `INGEST_OUTBOX_DISPATCH_CRON` 在事务提交后投递 Redis。若 Redis 短暂不可用，outbox 事件会进入 `FAILED` 并按退避时间重试，文档和任务保持 `PENDING + QUEUED`；补偿成功后文档进入 `PROCESSING`。旧补偿扫描仍按 `INGEST_RECOVERY_CRON` 兼容历史 queued job，连续失败达到 `INGEST_RECOVERY_MAX_ATTEMPTS` 后任务进入 `DEAD_LETTER`，文档标记为 `FAILED`。Web 文档页提供“索引维护”面板，可查看最近摄入任务并对 `FAILED` / `DEAD_LETTER` 任务执行租户边界内的手动重试；同时展示向量清理补偿任务，并可触发手动重试。V1.1 起，摄入任务响应会带 `documentFileName`、`documentStatus` 与 `diagnosis`。V1.2 的文档查看动作调用 `GET .../documents/{docId}/index-detail`，在同一处展示对象 key/可用性、最近任务诊断、分块总数、最多 20 个分块样例和 `indexReady`，把上传、摄入和索引排障串成闭环。

切换 embedding 模型或维度后，可在 Web 文档页点击“重建索引”，或调用 `POST /api/v1/knowledge-bases/{kbId}/reindex` 重建旧知识库索引。该流程会更新知识库当前 embedding 配置、登记 Milvus 残留向量补偿清理、清空本地 chunks，并为库内所有文档重新创建摄入任务。API 启动时若发现已存在的 Milvus collection 中 `embedding` 字段不是 `FloatVector`，或 schema 维度与当前 `EMBEDDING_DIMENSION` 不一致，会 fail-fast 并提示重置 collection 或使用维度专用 collection，避免带错维度继续运行。

Worker 的 `embed_batch` 会按 `EMBEDDING_BATCH_SIZE`（默认 `32`）拆分 Embedding 请求，避免大文档一次性向供应商发送过长 `input` 数组；返回向量仍按原 chunk 顺序合并并校验数量与维度。

### 删除与向量补偿清理

```
删除文档 → 登记 document_tombstones → 登记 vector_cleanup_tasks → best-effort 删除 Milvus 向量
  → 删除数据库主记录 → 定时任务重试残留 Milvus 向量
```

删除链路以数据库为最终事实源，避免 Milvus 半加载或短暂不可用阻塞用户删除；后台补偿任务使用 `ORPHAN_VECTOR_CLEANUP_CRON` 调度，重试失败会记录错误并退避到下一次执行。文档删除会先写入 `document_tombstones`，outbox dispatcher 遇到 tombstone 会取消待投递事件，Worker 迟到状态回调也会被忽略，避免已删除文档被恢复为 chunks 或 `COMPLETED` 状态。运维入口 `GET /api/v1/ops/vector-cleanup-tasks` 返回当前主体可访问的待处理/失败任务，`POST /api/v1/ops/vector-cleanup-tasks/{taskId}/retry` 会做任务实例级授权后立即执行一次清理并写入审计日志。

### 审计日志

`audit_logs` 记录高影响操作的租户、动作、目标类型、目标 ID、结果、说明和错误信息。当前覆盖知识库删除、文档删除、聊天会话批量删除、知识库 reindex、摄入任务手动 retry、向量清理任务手动 retry，以及账号创建/更新/密码重置/禁用/启用/tokenVersion 轮换、角色创建/更新/禁用等管理动作。审计写入使用独立事务，降低主业务回滚导致审计丢失的风险。运维接口支持查询、CSV 导出、聚合告警和筛选/权限元数据；`POST /api/v1/ops/audit-alerts/notify` 要求 `OPS_ADMIN + OPS_AUDIT_READ + OPS_ALERT_NOTIFY`，会把当前告警投递到 `AUDIT_ALERT_WEBHOOK_URL` 并记录 `AUDIT_ALERT_NOTIFY` 审计，超时由 `AUDIT_ALERT_WEBHOOK_TIMEOUT_SECONDS` 控制（默认 10 秒）。未配置时明确返回 `configured=false`，投递失败不会伪装成功。保留清理由 `AUDIT_RETENTION_DAYS` 与 `AUDIT_RETENTION_CRON` 控制。

### RAG 问答

```
用户 query → Embedding → Milvus ANN Top-K（按 kb_id 过滤）
  → 拼装 Prompt（系统指令 + 引用上下文）
  → LLM 流式生成 → SSE 推送（retrieval / token / done）
```

`/retrieve` 响应包含 `diagnostics`（检索模式、TopK、命中数、embedding 模型/维度、fallback 原因）。Chat HTTP 与 SSE 失败统一返回 `ApiErrorResponse`（`error`、`message`、`stage`、`suggestion`、`requestId`）；同步错误和流式入口都区分 retrieval/llm 阶段，前端按 retrieval/llm/auth/unknown 展示可执行建议并兼容旧纯文本错误事件。V1.2 的 `RAG 评估` 使用 PostgreSQL `rag_eval_cases`、`rag_eval_runs`、`rag_eval_run_results` 持久化用户用例和最近 10 次运行历史，空库自动写入内置用例，每库最多 100 条；知识库级悲观锁避免初始化与创建并发冲突。评估运行需要 `MAINTENANCE + KB_READ`，状态为 `RUNNING/COMPLETED/FAILED`，可选择 Rerank，并保存命中、文件、token、检索模式、embedding、失败原因及运行失败信息；脚本侧评估仍保留为独立 CI/运维入口。

### 混合检索与 Rerank

```
query → 向量检索 + BM25 检索 → RRF 融合 → Rerank 模型 → Top-N → LLM
```

新建知识库可选择 `VECTOR` 或 `HYBRID`；RAG 评估运行可独立传入 `useRerank`。当前仍保留向量/本地文本 fallback，Milvus BM25 sparse 索引参数与大规模压测属于后续生产调优项。

API 启动会校验已有 collection schema，但以 `syncLoad=false` 异步请求加载，避免 Milvus QueryNode 加载缓慢时阻塞整个 Spring 上下文。集合尚未就绪时，检索路径的 load-state 检查会快速失败并进入本地文本 fallback，而不是让健康检查持续 502。

### 导出与恢复边界

`GET /api/v1/knowledge-bases/{kbId}/export` 使用 `schemaVersion=1` 导出知识库配置、文档元数据、分块内容/元数据和评估用例，单次限制为 1,000 个文档快照和 10,000 个分块快照。`POST /api/v1/knowledge-bases/import` 只接受版本化且经过校验的导入请求，通过现有业务服务创建新知识库并恢复配置与评估用例；V1.2 不重新上传 MinIO 原始二进制、不恢复原文档主记录，也不直接写回 Milvus 向量，文档/分块快照仅用于审计和迁移检查。完整灾备需要同时备份 PostgreSQL、MinIO 和 Milvus。

## 外部依赖

| 依赖 | 用途 | 当前本地配置示例 |
|------|------|------------------|
| DeepSeek API | RAG 对话（`CHAT_*`） | `https://api.deepseek.com/v1` |
| 智谱 OpenAI 兼容 API | Embedding 向量化（`EMBEDDING_*`） | `https://open.bigmodel.cn/api/paas/v4`，`embedding-2`，1024 维 |
| Milvus | 向量 ANN 检索；V2 BM25 sparse | 集合维度须与 KB `embeddingDimension` 一致 |
| PostgreSQL | 知识库、文档、分块、任务元数据 | |
| MinIO | 原始文件对象存储 | |
| Redis | 摄入任务队列、SSE 中断信号（V2） | |

摄入任务携带知识库级 `embeddingModel` / `embeddingDimension`（见 `IngestJobProducer`）；**旧库**若仍为 OpenAI 模型名，向智谱请求会 400。知识库详情响应会标记 `embeddingConfigCurrent`，当库内配置与当前运行配置不一致时返回 `embeddingConfigWarning`，用户可通过 `POST /api/v1/knowledge-bases/{kbId}/reindex` 将旧库按当前运行配置重建索引。Milvus collection 级别的维度不一致属于启动前置错误：需要删除/重建 collection，或把 `MILVUS_COLLECTION` 指向与当前 embedding 维度匹配的新集合。

公共知识库、文档、检索与会话入口使用 `X-Dupi-Tenant-Id` 进行租户隔离，未传时默认为 `default`；租户 ID 仅允许安全字符，防止路径/注入类输入。登录用户的 token 租户优先于请求头租户。账号可通过 `knowledgeBaseIds` 限定可访问知识库范围，文档、会话和清理任务等实例级操作会先解析实例归属的 kbId，再执行资源级授权。Worker 回调、摄入补偿、internal chunks 拉取等内部路径使用系统级查询，不受请求租户头误伤。

## Web 与 CORS

- 浏览器入口：`web` Nginx `:8080`，`/api` 反代 `api:8080`
- API 支持可选共享密钥鉴权：`/api/**` 校验 `X-Dupi-API-Key`，`/api/v1/internal/**` 校验 `X-Dupi-Internal-Key`；对应环境变量为空时保持本地开发兼容。
- Web Nginx 会把 `DUPI_API_KEY` 注入到代理给 API 的请求头中；Worker 调用 internal API 时会携带 `DUPI_INTERNAL_KEY`。
- 需要查看 Compose 展开配置时使用 `scripts/compose-config-redacted.ps1`，不要直接共享包含 `.env` 展开值的原始 `docker compose config` 输出。
- API `CorsFilter` 允许 `http://localhost:*` 与 `http://127.0.0.1:*`（支持 `:8080` 与 Vite `:5173`）
- `fetch` POST 会带 `Origin`；仅允许 `5173` 时，`:8080` 上建库/上传返回 403

## 测试与验收

主流程 E2E 脚本 [`scripts/e2e-main-flow.ps1`](../scripts/e2e-main-flow.ps1) 经 Nginx `:8080` 覆盖 KB CRUD、文档上传、Worker 摄入、检索与 SSE 问答。V1.2 扩展真实浏览器门禁 [`scripts/e2e-browser-gate.ps1`](../scripts/e2e-browser-gate.ps1)，使用 Playwright 从登录页进入，验证 Cookie/CSRF、混合检索建库、文档/问答/RAG 页签、持久化评估用例、审计、账号真实创建/禁用和角色页面，并拦截控制台、页面、请求和错误 Toast。维护与脚本侧 RAG 回归入口继续保留。详见 [e2e-testing.md](e2e-testing.md)。

## Docker Compose 服务拓扑

| 服务 | 端口 | 说明 |
|------|------|------|
| `postgres` | 内部网络 | 元数据 |
| `redis` | 内部网络 | 队列与缓存 |
| `milvus` | 内部网络 | 向量库（standalone） |
| `minio` | 内部网络 | 对象存储 |
| `web` | 8080 | Nginx + React SPA（浏览器入口，`/api` 反代 api） |
| `api` | 内部网络 | Spring Boot REST API |
| `worker` | 内部网络 | Python 摄入与检索增强 |

默认 Compose 仅将 `web:80` 映射到宿主机 `8080`，其余服务使用 Docker 内部网络通信；如需数据库、MinIO、Milvus、API 或 Worker 直连调试，应通过临时 override 文件显式暴露端口。
