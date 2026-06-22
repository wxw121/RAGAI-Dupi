# 进展记录

## 当前状态

V1 MVP 全栈可运行：**Web 控制台**（`:8080`）+ API + Worker + Milvus；对话 DeepSeek、向量化智谱 `embedding-2`（1024 维）；**E2E 主流程 8/8 通过**（`scripts/e2e-main-flow.ps1`）。

## 最近进展

### 2026-06-21（文档管理：批次上传与删除）
- [批次上传] — 文档管理页支持多选/拖拽多个文件，顺序调用现有单文件上传 API
  - [`useDropzone.ts`](../services/web/src/hooks/useDropzone.ts) 新增 `multiple` 参数
  - [`UploadZone.tsx`](../services/web/src/components/UploadZone.tsx) 多文件上传与进度文案（`上传中 2/5：foo.pdf`）
  - [`KbDetailPage.tsx`](../services/web/src/pages/KbDetailPage.tsx) 按文件捕获错误，汇总 toast（成功 N 个 / 失败 M 个）
- [文档删除] — 对接已有 `DELETE .../documents/{docId}` API
  - [`documents.ts`](../services/web/src/api/documents.ts) 新增 `deleteDocument`
  - [`DocTable.tsx`](../services/web/src/components/DocTable.tsx) 操作列 + 删除按钮（确认对话框，删除中 loading）
  - 后端无需改动（Milvus + chunks + MinIO + DB 已在 `DocumentService.delete` 清理）
- [验证] — `tsc -b` 通过；本地 Compose 未运行时未做浏览器 E2E

### 2026-06-20（Canonical Markdown 摄入）
- [摄入统一 MD] — 各格式上传后先经 `canonicalize` 转为规范 Markdown，再按标题/代码块/表格边界分块，替代纯 token 切断
  - **新增** [`services/worker/app/canonical/`](services/worker/app/canonical/)：`md_sanitizer`、`text_to_md`、`docx_to_md`、`xlsx_to_md`、`pdf_to_md`（pymupdf4llm）、`to_markdown`
  - **新增** [`markdown_chunker.py`](services/worker/app/chunker/markdown_chunker.py)：section 拆分、`heading`/`block_type` 元数据、表格/代码块原子切分
  - **接入** [`consumer.py`](services/worker/app/consumer.py)：canonicalize → markdown chunk；`recursive` 策略亦走 markdown chunker
  - **API** [`RetrievalService.buildContext`](services/api/src/main/java/com/dupi/rag/service/RetrievalService.java) 带 `section`/`type`；[`ChatService`](services/api/src/main/java/com/dupi/rag/service/ChatService.java) prompt 要求保持上下文 MD 结构
  - **枚举** `ChunkStrategy.MARKDOWN`；`pymupdf` 升至 `>=1.24.10` 以兼容 `pymupdf4llm`
- [验证] — E2E 步骤 1–6 PASS（上传 sample-knowledge.md 摄入 COMPLETED）；检索返回 chunk 含 `heading`、`block_type`、`format=canonical_md`
- [注意] — **已摄入文档需重新上传** 才能享受新分块；E2E 步骤 7 因 PowerShell JSON 编码解析失败（非 API 错误）

### 2026-06-20（Web 问答 Markdown 渲染与自动换行）
- [Chat Markdown] — 智能问答助手回复原先以纯文本 `whitespace-pre-wrap` 展示，LLM 返回的 `##`、代码块、列表等 Markdown 语法无法解析
  - **修复（第一版）**：新增 [`MarkdownContent.tsx`](../services/web/src/components/MarkdownContent.tsx)，使用 `react-markdown` + `remark-gfm` + `@tailwindcss/typography`；[`ChatPanel.tsx`](../services/web/src/components/ChatPanel.tsx) 助手消息改用 Markdown 渲染
- [自动换行] — 首版仍有长文本不换行、单行挤在一起的问题；根因：气泡 `overflow-x-auto`、`prose max-w-none`、flex 子项缺 `min-w-0`、标准 Markdown 单换行不渲染
  - **修复（第二版）**：气泡与容器加 `min-w-0` / `break-words` / `overflow-wrap:anywhere`；移除气泡级横向滚动；`pre`/行内 `code` 支持 `whitespace-pre-wrap`；新增 `remark-breaks` 将单 `\n` 转为 `<br>`
- [Markdown 格式] — LLM 常输出 `##标题`、`1.步骤2.步骤` 等非标准 Markdown，前端无法解析为标题/列表
  - **修复（第三版）**：[`ChatService`](../services/api/src/main/java/com/dupi/rag/service/ChatService.java) system prompt 约束标准 Markdown 输出；新增 [`normalizeMarkdown.ts`](../services/web/src/lib/normalizeMarkdown.ts) 在渲染前补空格与换行
- [序号/样式] — 列表从上下文片段编号（如 5、6）起跳、`5. 1本地开发` 嵌套序号、代码块与命令粘连、`#`/`---#` 残留、标题字号过大
  - **修复（第四版）**：增强 normalize（重编号、片段序号转 `##` 标题、修复 code fence、去残留符号、命令拆词）；prompt 禁止用片段编号作列表序号；[`MarkdownContent`](../services/web/src/components/MarkdownContent.tsx) 统一标题字号、限制过长加粗
- [表格/树/链路] — 架构类回答中表格挤成一行（`||` 连接）、目录树 `├──` 不换行、`[1]#` 残留、部署箭头链路难读
  - **修复（第五版）**：normalize 新增表格行拆分、目录树/箭头流转 `text` 代码块、粘连小节标题拆分、未闭合反引号修复、`•` 转列表；prompt 要求架构分节、表格逐行、树与链路用代码块
- [部署] — `docker compose -f deploy/docker-compose.yml up -d --build web` 重建通过

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
