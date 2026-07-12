# 端到端主流程自动化测试

## 目的

从 **Web 控制台按钮操作** 出发，用脚本自动调用同一套 HTTP 接口，验证「健康检查 → 知识库 → 上传 → 摄入 → 检索 → 问答」主链路。

脚本路径：[`scripts/e2e-main-flow.ps1`](../scripts/e2e-main-flow.ps1)  
最近一次运行报告：[`scripts/e2e-last-run.json`](../scripts/e2e-last-run.json)（每次运行覆盖）

补充脚本：

- [`scripts/e2e-web-maintenance-flow.ps1`](../scripts/e2e-web-maintenance-flow.ps1)：覆盖批量上传、reindex、摄入任务重试入口、向量清理任务列表/重试入口。
- [`scripts/rag-regression-eval.ps1`](../scripts/rag-regression-eval.ps1)：读取 [`examples/rag-eval-cases.json`](../examples/rag-eval-cases.json)，校验检索命中、引用文件和关键片段。

## 与前端按钮的对应关系

| 步骤 | 脚本动作 | Web 控制台等价操作 | API |
|------|----------|-------------------|-----|
| 1 | `GET /actuator/health` | 顶栏服务指示灯 | 经 Nginx `:8080` |
| 2 | `GET /api/v1/knowledge-bases` | 首页加载知识库列表 | |
| 3 | `POST /api/v1/knowledge-bases` | **新建知识库** | |
| 4 | `GET /api/v1/knowledge-bases/{id}` | 点击卡片 / **管理文档** 进入详情 | |
| 5 | `POST .../documents` multipart | **文档管理 → 上传** | 样例：`examples/sample-knowledge.md` |
| 6 | 轮询文档 `status` 至 `COMPLETED` | 等待列表状态变为已完成 | Worker 异步摄入 |
| 7 | `POST .../retrieve` | 检索调试（API） | |
| 8 | `POST .../chat` SSE | **去问答** / 智能问答流式 | 需 `CHAT_*` + `EMBEDDING_*` |
| 9 | `GET .../chat-sessions` + `GET .../chat-sessions/{id}` | **历史会话**列表与详情 | 列表只应返回已有消息的会话，详情 `messages` 不应为空 |

默认 `BaseUrl=http://localhost:8080`（与浏览器入口一致，经 Nginx 反代 API）。

## 运行方式

**前置**：`deploy` 目录下 `docker compose up -d` 全栈已启动，且 `deploy/.env` 已配置有效的 `EMBEDDING_*`（摄入与检索）和 `CHAT_*`（问答）。

```powershell
# 项目根目录（Windows 需 Bypass 执行策略）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/e2e-main-flow.ps1

# 可选参数
powershell -NoProfile -File scripts/e2e-main-flow.ps1 `
  -BaseUrl "http://localhost:8080" `
  -SampleFile "examples\sample-knowledge.md" `
  -PollSeconds 120 `
  -PollInterval 3
```

维护流程与 RAG 回归：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/e2e-web-maintenance-flow.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/rag-regression-eval.ps1
```

`rag-regression-eval.ps1` 可通过 `-KbId` 复用已有知识库；未传时会新建临时评测知识库并上传 `examples/sample-knowledge.md`。

退出码：`0` 全部通过；`1` 任一步失败（报告仍写入 `e2e-last-run.json`）。

## 最近一次运行结果（2026-06-19）

| 步骤 | 状态 | 说明 |
|------|------|------|
| 1–4 | PASS | 健康、列表、建库、详情 |
| 5 上传 | PASS | `DocumentService` 时间戳修复后通过 |
| 6 摄入 | PASS | 智谱 `embedding-2`，需新 KB + Milvus 集合维度 1024 |
| 7 检索 | PASS | hits≥1 |
| 8 Chat SSE | PASS | DeepSeek 流式 + `LlmClient` SSE 解析修复 |

**运行命令**：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/e2e-main-flow.ps1`

## 常见问题（页面 / 摄入）

### Web 建库或上传 403

- 现象：`Invalid CORS request`
- 原因：API `CorsConfig` 未允许 `Origin: http://localhost:8080`
- 处理：确保 API 已部署含 `allowedOriginPatterns`（`localhost:*`）的版本；硬刷新页面

### 摄入失败 400（智谱 embeddings）

- 现象：`Client error '400 Bad Request' for url '.../embeddings'`
- 常见原因：**旧知识库** `embeddingModel` 仍为 `text-embedding-3-small`（Worker 按 KB 元数据调 API，非仅读 `.env`）
- 处理：删除旧库并**新建知识库**后重传；`GET /api/v1/knowledge-bases/{id}` 确认 `embeddingModel=embedding-2`、`embeddingDimension=1024`

### Milvus 维度不匹配

- 现象：`the length(1024) of float data should divide the dim(1536)`
- 处理：删除 Milvus 集合 `dupi_chunks`（或 `docker volume rm deploy_milvus_data`）后重新摄入

### Chat 只显示“错误：”或历史会话为空

- 现象：问答区只显示空错误前缀，或点击历史会话后没有具体消息。
- 常见原因：Milvus QueryNode 短暂不可查（例如 `Timestamp lag too large`）导致 Chat SSE 500，前端收到空 error；失败请求可能留下没有消息的会话壳。
- 处理：确认 API 已包含本地 chunk 文本兜底与空会话过滤修复；回归时运行 `.\mvnw.cmd "-Dtest=RetrievalServiceTest,ChatSessionServiceTest,ChatServiceTest" test` 和 `npm run test -- src/api/chat.test.ts`，并用真实 `/chat`、`/chat-sessions` 接口复核。

### 索引维护任务全部失败

- 现象：批量上传多个文档后，Embedding 请求成功，但索引维护任务显示 `FAILED`，错误包含 Milvus `Timestamp lag too large`。
- 常见原因：Worker 在写入新 chunks 前会按 `doc_id` 删除旧向量；Milvus delete 在 QueryNode 时间戳滞后时抛出临时异常。
- 处理：确认 Worker 已包含 `delete_by_doc` 对 `Timestamp lag too large` / `failed to search/query delegator` 的容错；回归时运行 `python -m pytest services/worker/tests`，然后对失败的 ingest job 使用页面“重试”或 `POST /api/v1/knowledge-bases/{kbId}/ingest-jobs/{jobId}/retry`。

**配置与重启**：

- `deploy/.env` 配置 `EMBEDDING_*`（智谱）与 `CHAT_*`（DeepSeek）
- `EMBEDDING_BASE_URL` 为 API 根路径，**不要**带 `/embeddings` 后缀
- 修改 `.env` 后：`cd deploy && docker compose up -d --force-recreate worker api`

## 相关缺陷修复

**上传 500（`documents.created_at` NOT NULL）**

- **原因**：`Document.builder().id(UUID)` 预置主键时，JPA `save()` 走 merge 路径，`@PrePersist` 不执行；第二次 `save()` 将 `created_at` 更新为 null
- **修复**：[`DocumentService.upload()`](../services/api/src/main/java/com/dupi/rag/service/DocumentService.java) 在 builder 中显式设置 `createdAt`/`updatedAt`，并预先计算 `objectKey`
- **部署**：`docker compose up -d --build api`
