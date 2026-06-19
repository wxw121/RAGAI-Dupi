# 技术决策

## 2026-06-19 — Java 主服务 + Python Worker 拆分

**背景**：技术栈要求 Go/Java + Python；需在企业 API 能力与 ML/解析生态间取舍。  
**决策**：主服务采用 Java Spring Boot；解析、Embedding、Rerank 由 Python Worker 承担。  
**理由**：Java 适合事务型 API 与企业集成；Python 拥有成熟的 PDF/DOCX 解析库与 LlamaIndex 生态，避免 JVM 侧 ML 依赖维护成本。  
**影响范围**：`services/api`、`services/worker`、Redis 任务队列契约。

## 2026-06-19 — V1 纯向量检索

**背景**：混合检索需同时维护向量库与倒排索引，复杂度高。  
**决策**：V1 仅实现 Milvus 稠密向量 ANN 检索；V2 引入 BM25 + RRF。  
**理由**：先跑通「上传→索引→问答」闭环，降低首版交付风险。  
**影响范围**：`RetrievalService`、Milvus Collection 设计。

## 2026-06-19 — V1 递归分块而非语义分块

**背景**：语义分块需额外 Embedding 计算与调参。  
**决策**：V1 使用标题/段落优先 + Recursive Character Splitter；语义分块作为 V2 可选策略。  
**理由**：实现快、行为可预测，满足多数企业文档场景。  
**影响范围**：`worker/app/chunker/`。

## 2026-06-19 — Redis 作摄入任务队列

**背景**：V1 单机 Docker Compose，吞吐量有限。  
**决策**：使用 Redis List（`BRPOP`）作摄入任务队列。  
**理由**：部署简单，与缓存共用实例；流量增长后可换 RabbitMQ/Kafka。  
**影响范围**：`IngestJobProducer`、`worker/consumer.py`。

## 2026-06-19 — Chat 与 Embedding 配置拆分

**背景**：DeepSeek 官方 API 仅提供 `/chat/completions`，无 `/embeddings` 端点；原单一 `OPENAI_*` 配置无法同时满足问答与向量化需求。  
**决策**：将 `dupi.llm` 拆为 `chat` 与 `embedding` 两段独立配置（`CHAT_*` / `EMBEDDING_*` 环境变量）；Java API 使用 `LlmClient` 分别构建 WebClient；Worker 仅读取 `EMBEDDING_*`。  
**理由**：允许 Chat 使用 DeepSeek、Embedding 使用任意 OpenAI 兼容供应商；保留 `OPENAI_*` 作为回退以兼容旧部署。  
**影响范围**：`LlmProperties`、`LlmClient`（Java）、`config.py` / `embedder.py`（Worker）、`deploy/.env*`、Web `ChatPanel` 401 提示。

## 2026-06-19 — OpenAI 兼容协议统一 LLM/Embedding

**背景**：需支持多种国产与开源模型。  
**决策**：Chat 与 Embedding 均通过 `base_url` + `api_key` 调用 OpenAI 兼容接口。  
**理由**：一套客户端适配多供应商，配置驱动切换模型。  
**影响范围**：`OpenAiClient`（Java）、`embedder.py` / `llm.py`（Python）。

## 2026-06-19 — Docker Compose 单机部署

**背景**：V1 以本地/单机验证为主。  
**决策**：Milvus standalone + PG + Redis + MinIO + api + worker 一键 Compose。  
**理由**：降低环境搭建成本；K8s 划入 V4。  
**影响范围**：`deploy/docker-compose.yml`。

## 2026-06-19 — V2 混合检索由 Python Worker 提供

**背景**：BM25 与 Rerank 生态在 Python 侧更成熟。  
**决策**：混合检索与 Rerank 通过 Worker HTTP 端点 `/api/v1/retrieve/hybrid` 实现；Java API 按 `retrieval_mode=HYBRID` 路由。  
**理由**：避免 Java 侧重复实现 rank-bm25 与 CrossEncoder；语料从 API 内部端点拉取。  
**影响范围**：`RetrievalService`、`worker/app/retrieval/hybrid.py`。

## 2026-06-19 — Docker 镜像加速（国内网络）

**背景**：`docker compose up` 拉取 `redis:7-alpine` 时连接 `registry-1.docker.io:443` 超时。  
**决策**：在 Docker Desktop `daemon.json` 配置 `registry-mirrors`（`https://docker.1ms.run`），并禁用 IPv6（`ipv6: false`）。  
**理由**：不修改项目 compose 文件即可解决 Hub 访问问题；预拉取镜像可进一步降低失败率。  
**影响范围**：本机 `C:\Users\Wxw\.docker\daemon.json`（不入库）。

## 2026-06-19 — Worker 构建使用清华 PyPI 镜像

**背景**：Worker 镜像构建时 `pip install` 从 `files.pythonhosted.org` 超时（尤其 `torch` 等大包）。  
**决策**：Dockerfile 中 `pip install` 指定 `-i https://pypi.tuna.tsinghua.edu.cn/simple` 与 `--default-timeout=300`。  
**理由**：国内构建稳定；不影响运行时逻辑。  
**影响范围**：[`services/worker/Dockerfile`](services/worker/Dockerfile)。

## 2026-06-19 — API 基础镜像使用明确标签

**背景**：镜像加速站对 `eclipse-temurin:17-jre` 返回 not found，且部分镜像站 429。  
**决策**：API Dockerfile 运行阶段改用 `eclipse-temurin:17-jre-jammy`；构建前预拉取 `maven:3.9-eclipse-temurin-17`。  
**理由**：明确标签兼容性更好，减少镜像站元数据解析失败。  
**影响范围**：[`services/api/Dockerfile`](services/api/Dockerfile)。

## 2026-06-19 — 锁定 marshmallow 3.x 兼容 pymilvus

**背景**：Worker 启动报 `marshmallow` 无 `__version_info__`，因 pip 解析安装了 marshmallow 4.x。  
**决策**：`requirements.txt` 增加 `marshmallow>=3.13.0,<4.0.0`。  
**理由**：`pymilvus 2.4.1` / `environs 9.5.0` 依赖 marshmallow 3.x API。  
**影响范围**：[`services/worker/requirements.txt`](services/worker/requirements.txt)。

## 2026-06-19 — Web 控制台 CORS 覆盖 :8080

**背景**：用户经 Nginx `http://localhost:8080` 访问 SPA；`fetch` POST 会发送 `Origin` 头。原 `CorsConfig` 仅 `allowedOrigins: localhost:5173`，导致建库/上传返回 403 `Invalid CORS request`。  
**决策**：使用 `allowedOriginPatterns`：`http://localhost:*`、`http://127.0.0.1:*`，覆盖 8080（生产式 Compose）与 5173（Vite 开发）。  
**理由**：GET 列表正常、POST 失败是典型的 CORS 预检/Origin 拒绝；模式匹配避免每端口硬编码。  
**影响范围**：[`CorsConfig.java`](../services/api/src/main/java/com/dupi/rag/config/CorsConfig.java)。

## 2026-06-19 — Embedding 选用智谱 embedding-2

**背景**：DeepSeek 无 `/embeddings`；国内部署需可用的向量服务。  
**决策**：`deploy/.env` 默认智谱：`EMBEDDING_BASE_URL=https://open.bigmodel.cn/api/paas/v4`、`EMBEDDING_MODEL=embedding-2`、`EMBEDDING_DIMENSION=1024`；对话仍用 DeepSeek `CHAT_*`。  
**理由**：智谱 OpenAI 兼容 `/embeddings`；`embedding-2` 固定 1024 维，与 Milvus collection 维度一致即可。  
**影响范围**：`deploy/.env*`、`README.md`、Worker `embedder.py`、摄入任务 `embeddingModel`/`embeddingDimension` 字段。

## 2026-06-19 — 新建知识库默认 Embedding 取自环境变量

**背景**：`CreateKnowledgeBaseRequest` 硬编码 `text-embedding-3-small`/1536，与 `.env` 智谱配置不一致；旧库摄入会向智谱发送错误模型名（400）。  
**决策**：`KnowledgeBaseService.create()` 在请求未指定时，从 `LlmProperties.embedding` 填充 `embeddingModel` 与 `embeddingDimension`。  
**理由**：单一配置源（`.env` → Spring → 新 KB → Redis 摄入任务）；避免新建库仍用 OpenAI 默认。  
**影响范围**：[`KnowledgeBaseService.java`](../services/api/src/main/java/com/dupi/rag/service/KnowledgeBaseService.java)、[`CreateKnowledgeBaseRequest.java`](../services/api/src/main/java/com/dupi/rag/dto/CreateKnowledgeBaseRequest.java)。  
**遗留**：已存在的旧知识库元数据不会自动更新，需新建库或后续提供迁移 API。

## 2026-06-19 — DeepSeek 流式 SSE 解析方式

**背景**：`LlmClient.chatStream` 用 `bodyToFlux(String)` 按行过滤 `data:`，DeepSeek 流式响应无 `event:token` 输出。  
**决策**：改为 `bodyToMono(String)` 收齐 SSE 体后按行解析 `data:` JSON delta。  
**理由**：WebClient 分片与行边界不对齐导致 token 全被过滤；非流式 `chat()` 正常说明 API Key 与 URL 无误。  
**影响范围**：[`LlmClient.java`](../services/api/src/main/java/com/dupi/rag/client/LlmClient.java)。  
**权衡**：首 token 延迟略高于真流式；后续可换 `DataBuffer` 行缓冲实现增量推送。

## 2026-06-19 — E2E 脚本经 Nginx 模拟 Web 主流程

**背景**：需在不打开浏览器的情况下验收与 Web 控制台一致的主链路。  
**决策**：提供 PowerShell 脚本 `scripts/e2e-main-flow.ps1`，默认请求 `http://localhost:8080`（Nginx + SPA 同入口），8 步映射首页/建库/上传/问答按钮；结果写入 `scripts/e2e-last-run.json`。  
**理由**：与真实用户路径一致；`curl` 可 CI 化；步骤失败时报告保留 kbId/docId 便于排障。  
**影响范围**：`scripts/`、`docs/e2e-testing.md`、`README.md`。

## 2026-06-19 — 预置 JPA 主键时显式设置时间戳

**背景**：`Document.builder().id(UUID)` 使 `save()` 走 merge，`@PrePersist` 不执行；二次 `save()` 将 `created_at` 置 null，违反 NOT NULL。  
**决策**：在 `DocumentService` / `IngestJob` builder 中显式设置 `createdAt`/`updatedAt`；`objectKey` 在首次 persist 前用预生成 `docId` 计算。  
**理由**：保留客户端可见的稳定 `objectKey` 路径，同时避免 merge 语义陷阱。  
**影响范围**：[`DocumentService.java`](../services/api/src/main/java/com/dupi/rag/service/DocumentService.java)。

## 2026-06-19 — Chat 与 Embedding 拆分配置（DeepSeek + 独立向量服务）

**背景**：OpenAI 国内不可达；DeepSeek 官方 API 仅提供对话，无 `/embeddings`。  
**决策**：`dupi.llm.chat.*` 默认 DeepSeek；`dupi.llm.embedding.*` 由用户配置独立 OpenAI 兼容 Embedding 服务。Worker 摄入与 API 检索均使用 `EMBEDDING_*` 环境变量。  
**理由**：一套 Key 无法覆盖 RAG 全链路；拆分后用户可 DeepSeek 对话 + 国产 Embedding。  
**影响范围**：[`application.yml`](services/api/src/main/resources/application.yml)、[`LlmProperties.java`](services/api/src/main/java/com/dupi/rag/config/LlmProperties.java)、[`deploy/.env.example`](deploy/.env.example)、[`worker/app/config.py`](services/worker/app/config.py)。
