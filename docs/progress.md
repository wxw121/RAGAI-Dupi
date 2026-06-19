# 进展记录

## 当前状态

V1 MVP 全栈可运行：**Web 控制台**（`:8080`）+ API + Worker + Milvus；对话 DeepSeek、向量化智谱 `embedding-2`（1024 维）；**E2E 主流程 8/8 通过**（`scripts/e2e-main-flow.ps1`）。

## 最近进展

### 2026-06-19（Web 排障、智谱 Embedding、E2E 全绿）
- [CORS 403] — `:8080` 页面「新建知识库」「上传文档」POST 返回 403 `Invalid CORS request`；原因：`CorsConfig` 仅允许 `localhost:5173`，浏览器 `fetch` POST 带 `Origin: http://localhost:8080` 被拒
  - **修复**：[`CorsConfig.java`](../services/api/src/main/java/com/dupi/rag/config/CorsConfig.java) 改为 `allowedOriginPatterns`：`http://localhost:*`、`http://127.0.0.1:*`
- [智谱 Embedding] — `deploy/.env` 配置 `EMBEDDING_BASE_URL=https://open.bigmodel.cn/api/paas/v4`、`embedding-2`、维度 `1024`；[`KnowledgeBaseService`](../services/api/src/main/java/com/dupi/rag/service/KnowledgeBaseService.java) 新建 KB 时从 `LlmProperties` 读取默认 `embeddingModel`/`embeddingDimension`
- [摄入 400] — 旧知识库元数据仍为 `text-embedding-3-small`/1536，Worker 将其发给智谱导致 400；**规避**：新建知识库后上传，或删除旧库；切换维度时需重置 Milvus `dupi_chunks` 集合
- [Chat SSE] — DeepSeek 流式无 `event:token`；**修复**：[`LlmClient.chatStream`](../services/api/src/main/java/com/dupi/rag/client/LlmClient.java) 使用 `bodyToMono` + SSE 行解析
- [E2E 全绿] — 8 步全部 PASS（智谱摄入 + 检索 + Chat SSE）；报告 [`scripts/e2e-last-run.json`](../scripts/e2e-last-run.json)
- [文档] — 本批更新 `progress.md`、`todo.md`、`decisions.md`、`e2e-testing.md`、`architecture.md`

### 2026-06-19（E2E 主流程自动化）
- [E2E 脚本] — 新增 [`scripts/e2e-main-flow.ps1`](../scripts/e2e-main-flow.ps1)，8 步对应 Web 按钮：健康 → 列表 → 建库 → 详情 → 上传 → 轮询摄入 → 检索 → Chat SSE；报告输出 `scripts/e2e-last-run.json`
- [上传缺陷修复] — `DocumentService.upload()` 预置 UUID 导致 `@PrePersist` 未触发、`created_at` 为 null；builder 显式设置时间戳并预计算 `objectKey`；步骤 5 已通过
- [E2E 运行] — 初跑步骤 1–5 PASS；步骤 6 Embedding 401；后续配置智谱并修复 SSE 后 **8/8 PASS**
- [文档] — 新增 [`docs/e2e-testing.md`](e2e-testing.md)；更新 `README.md`、`architecture.md`

### 2026-06-19（Docker 启动排障）
- [Docker 镜像拉取失败] — 直连 `registry-1.docker.io` 超时（`redis:7-alpine` 等）
  - **解决**：在 `C:\Users\Wxw\.docker\daemon.json` 配置 `registry-mirrors`（`https://docker.1ms.run`），并设置 `ipv6: false`；`docker desktop restart` 后预拉取 5 个基础镜像
- [镜像站 429] — `docker.xuanyuan.me` 返回 `429 Too Many Requests`，`eclipse-temurin:17-jre` 标签解析失败
  - **解决**：移除不稳定镜像站，仅保留 `docker.1ms.run`；API Dockerfile 改用 `eclipse-temurin:17-jre-jammy`；预拉取 `maven` / `eclipse-temurin` / `python` 基础镜像
- [Worker pip 超时] — 构建时 `pip install` 从 `files.pythonhosted.org` 下载超时
  - **解决**：[`services/worker/Dockerfile`](services/worker/Dockerfile) 使用清华 PyPI 镜像 + `--default-timeout=300`
- [Worker 启动崩溃] — `marshmallow` 4.x 与 `pymilvus`/`environs` 不兼容（`__version_info__` 缺失）
  - **解决**：[`services/worker/requirements.txt`](services/worker/requirements.txt) 锁定 `marshmallow>=3.13.0,<4.0.0` 后重建 worker
- [全栈启动成功] — `docker compose up -d --build` 完成；`http://localhost:8080/actuator/health` → UP；`http://localhost:8000/health` → ok

### 2026-06-19
- [立项] — 确定 Java Spring Boot + Python Worker + Milvus/Redis Docker Compose 架构
- [规划] — 完成 V1 范围与 V2–V4 演进路线文档
- [工程初始化] — 创建 `docs/` 四件套、`README.md`、`docker-compose.yml`
- [M0] — Spring Boot API 脚手架、Python Worker 脚手架、PostgreSQL Flyway 迁移
- [M1] — 文档上传、PDF/DOCX/TXT/MD 解析、递归分块、Embedding、Milvus 索引闭环
- [M2] — 向量检索 API、RAG Chat SSE 流式、citations
- [M3] — 健康检查、错误处理、摄入重试
- [V2 骨架] — BM25 混合检索、Rerank、语义分块、Excel 解析、生成中断端点
