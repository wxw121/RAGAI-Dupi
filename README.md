# dupi-RAG

> 账号 / RBAC 与 ops 管理权限更新记录见 [docs/rbac-ops-admin-2026-07-06.md](docs/rbac-ops-admin-2026-07-06.md)；摄入 outbox、删除 tombstone、实例级授权与审计运维增强见 [docs/outbox-tombstone-rbac-ops-2026-07-07.md](docs/outbox-tombstone-rbac-ops-2026-07-07.md)。

企业级 RAG 知识库引擎 — 类似 Dify/扣子底层知识库模块。

支持私有文档（PDF、DOCX、TXT、Markdown、Excel）上传、异步解析与向量化，结合大模型进行检索增强问答（SSE 流式）。

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

1. **新建知识库** → 点击卡片或「去问答」进入详情
2. **文档管理** → 上传文件，等待状态 `COMPLETED`
3. **智能问答** → 基于已摄入文档提问（需配置 `CHAT_API_KEY` 与 `EMBEDDING_API_KEY`）

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

### 4.2 端到端主流程自动化（推荐）

脚本按 Web 控制台按钮顺序调用接口（健康 → 建库 → 上传 → 摄入 → 检索 → 问答 SSE）：

```powershell
powershell -NoProfile -File scripts/e2e-main-flow.ps1
```

需有效 `EMBEDDING_*` 与 `CHAT_*` 配置；步骤说明与最近运行结果见 [docs/e2e-testing.md](docs/e2e-testing.md)。

新增维护与回归验证脚本：

```powershell
# 索引维护流程：批量上传、reindex、摄入任务重试入口、向量清理任务入口
powershell -NoProfile -File scripts/e2e-web-maintenance-flow.ps1

# RAG 检索回归评测：按 examples/rag-eval-cases.json 校验命中与引用文件
powershell -NoProfile -File scripts/rag-regression-eval.ps1
```

### 5. API 示例

```bash
# 创建知识库
curl -X POST http://localhost:8080/api/v1/knowledge-bases \
  -H "Content-Type: application/json" \
  -d '{"name":"demo","description":"测试库","chunkSize":512,"chunkOverlap":64,"topK":5}'

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

# 查看并重试残留向量补偿清理任务
curl http://localhost:8080/api/v1/ops/vector-cleanup-tasks
curl -X POST http://localhost:8080/api/v1/ops/vector-cleanup-tasks/{taskId}/retry

# 查看/导出审计日志、查看审计告警、账号/角色元数据
curl "http://localhost:8080/api/v1/ops/audit-logs?limit=50"
curl "http://localhost:8080/api/v1/ops/audit-logs/export" -o audit-logs.csv
curl http://localhost:8080/api/v1/ops/audit-alerts
curl http://localhost:8080/api/v1/ops/metadata
curl http://localhost:8080/api/v1/ops/accounts
curl http://localhost:8080/api/v1/ops/roles

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

问答 SSE 的 `retrieval` 事件返回 `{ citations, diagnostics }`；当回答提示“根据现有知识库资料无法回答”时，优先检查 `diagnostics.hitCount`、`fallbackReason`，以及知识库详情页是否提示 embedding 模型/维度与当前运行配置不一致。

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
| V2 | BM25 混合检索、Rerank、语义分块、Excel、生成中断 |
| V3 | Parent-Child 索引、多模态 OCR、Pipeline DSL |
| V4 | K8s、多租户、合规审计 |

详细规划见 [docs/todo.md](docs/todo.md) 与 [docs/decisions.md](docs/decisions.md)。
