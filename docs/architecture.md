# 架构概览

## 项目简介

dupi-RAG 是企业级 RAG（检索增强生成）知识库引擎，类似 Dify/扣子底层知识库模块：支持私有文档上传、解析、向量化，并结合大模型进行精准问答。

## 技术栈

- **Web 控制台**：React 18 + Vite + TypeScript（知识库管理、文档上传、RAG 问答）
- **主服务**：Java 17 + Spring Boot 3.x（REST API、编排、SSE 流式）
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
| `DocumentService` | 文档上传、MinIO 存储、任务入队 | `DocumentController` |
| `IngestJobService` | 摄入任务状态、重试 | `IngestJobController` |
| `RetrievalService` | 向量检索（V1 纯向量） | `RetrievalController` |
| `ChatService` | RAG 编排、LLM 调用、SSE | `ChatController` |
| `ingest-worker` | 解析→分块→Embedding→Milvus | `worker/main.py` |
| `hybrid-retriever` (V2) | BM25 + 向量 + Rerank | `worker/retrieval/` |
| `web` | 知识库 UI、文档上传、RAG 问答 | `services/web` |

## 数据流 / 核心流程

### 文档摄入（ETL）

```
上传文件 → MinIO → documents 表 → Redis 队列
  → Python Worker: 解析 → 清洗 → 分块 → Embedding API
  → Milvus 写入向量 + chunks 表元数据 → 状态 completed
```

### RAG 问答

```
用户 query → Embedding → Milvus ANN Top-K（按 kb_id 过滤）
  → 拼装 Prompt（系统指令 + 引用上下文）
  → LLM 流式生成 → SSE 推送（retrieval / token / done）
```

### V2 混合检索（已实现骨架）

```
query → 向量检索 + BM25 检索 → RRF 融合 → Rerank 模型 → Top-N → LLM
```

## 外部依赖

| 依赖 | 用途 | 当前本地配置示例 |
|------|------|------------------|
| DeepSeek API | RAG 对话（`CHAT_*`） | `https://api.deepseek.com/v1` |
| 智谱 OpenAI 兼容 API | Embedding 向量化（`EMBEDDING_*`） | `https://open.bigmodel.cn/api/paas/v4`，`embedding-2`，1024 维 |
| Milvus | 向量 ANN 检索；V2 BM25 sparse | 集合维度须与 KB `embeddingDimension` 一致 |
| PostgreSQL | 知识库、文档、分块、任务元数据 | |
| MinIO | 原始文件对象存储 | |
| Redis | 摄入任务队列、SSE 中断信号（V2） | |

摄入任务携带知识库级 `embeddingModel` / `embeddingDimension`（见 `IngestJobProducer`）；**旧库**若仍为 OpenAI 模型名，向智谱请求会 400。

## Web 与 CORS

- 浏览器入口：`web` Nginx `:8080`，`/api` 反代 `api:8080`
- API `CorsFilter` 允许 `http://localhost:*` 与 `http://127.0.0.1:*`（支持 `:8080` 与 Vite `:5173`）
- `fetch` POST 会带 `Origin`；仅允许 `5173` 时，`:8080` 上建库/上传返回 403

## 测试与验收

主流程 E2E 脚本 [`scripts/e2e-main-flow.ps1`](../scripts/e2e-main-flow.ps1) 经 Nginx `:8080` 模拟 Web 控制台操作，覆盖 KB CRUD、文档上传、Worker 摄入、检索与 SSE 问答。详见 [e2e-testing.md](e2e-testing.md)。

## Docker Compose 服务拓扑

| 服务 | 端口 | 说明 |
|------|------|------|
| `postgres` | 5432 | 元数据 |
| `redis` | 6379 | 队列与缓存 |
| `milvus` | 19530 | 向量库（standalone） |
| `minio` | 9000/9001 | 对象存储 |
| `web` | 8080 | Nginx + React SPA（浏览器入口，`/api` 反代 api） |
| `api` | 8081（调试） | Spring Boot REST API |
| `worker` | 8000 | Python 摄入与检索增强 |
