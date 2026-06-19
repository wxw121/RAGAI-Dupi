# dupi-RAG

企业级 RAG 知识库引擎 — 类似 Dify/扣子底层知识库模块。

支持私有文档（PDF、DOCX、TXT、Markdown、Excel）上传、异步解析与向量化，结合大模型进行检索增强问答（SSE 流式）。

## 技术栈

- **Web 控制台**：React 18 + Vite + TypeScript + Tailwind
- **API**：Java 17 + Spring Boot 3
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

# API 直连调试（可选，映射到宿主机 8081）
curl http://localhost:8081/actuator/health
```

### 4.1 端到端主流程自动化（推荐）

脚本按 Web 控制台按钮顺序调用接口（健康 → 建库 → 上传 → 摄入 → 检索 → 问答 SSE）：

```powershell
powershell -NoProfile -File scripts/e2e-main-flow.ps1
```

需有效 `EMBEDDING_*` 与 `CHAT_*` 配置；步骤说明与最近运行结果见 [docs/e2e-testing.md](docs/e2e-testing.md)。

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

# RAG 流式问答
curl -N -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/chat \
  -H "Content-Type: application/json" \
  -d '{"query":"你的问题","stream":true}'
```

### 6. 本地前端开发（可选）

```bash
cd services/web
npm install
npm run dev
```

Vite 开发服务器运行在 http://localhost:5173，API 代理到 http://localhost:8081。

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
