# 缺陷扫描与修复记录（2026-07-02）

## 背景

本次按 `docs/` 项目记忆文档梳理上下文后，对 API、Worker、Web、部署脚本做了一轮缺陷扫描。重点覆盖上传/摄入一致性、检索与问答边界、SSE 流式体验、Embedding 批量、防御式校验和前端状态处理。

## 已修复缺陷

### 1. Web 构建失败：`streamChat` 参数签名不匹配

- **位置**：`services/web/src/components/ChatPanel.tsx`、`services/web/src/api/chat.ts`
- **问题**：前端调用 `streamChat(..., signal, sessionId)`，但 API 函数只接收 3-4 个参数，TypeScript 构建直接失败。
- **修复**：扩展 `streamChat` 签名，接收 `sessionId` 并写入请求体 `{ query, stream: true, sessionId }`。
- **影响**：恢复 TypeScript 编译，并让后端使用前端生成的会话 ID。

### 2. Chat 停止按钮无法命中后端会话

- **位置**：`services/web/src/components/ChatPanel.tsx`、`services/web/src/api/chat.ts`、`services/api/src/main/java/com/dupi/rag/service/ChatService.java`
- **问题**：前端本地生成 `sessionId` 但未传给后端，点击停止时 `cancelChat` 发送的 ID 与后端实际会话 ID 不一致。
- **修复**：发送请求时携带 `sessionId`；前端中止时将消息流状态置为结束；后端继续按 `sessionId` 命中 `cancelledSessions`。
- **影响**：停止按钮不再只 abort 浏览器连接，取消请求能对应当前服务端会话。

### 3. SSE token 解析吞掉空格

- **位置**：`services/web/src/api/chat.ts`
- **问题**：前端解析 SSE `data:` 行时使用 `trim()`，会去掉 token 前后的必要空格，导致模型输出单词/标点粘连。
- **修复**：按 SSE 语义仅移除 `data:` 后的一个可选空格，多行 data 用 `\n` 合并，不再 trim token 内容。
- **影响**：流式输出保留 LLM token 原始空白。

### 4. 后端 Chat 不是真正逐块转发

- **位置**：`services/api/src/main/java/com/dupi/rag/client/LlmClient.java`
- **问题**：`chatStream()` 使用 `bodyToMono(String.class)` 收完整个上游 SSE 后再拆 token，用户端可能只在模型完成后才看到批量 token。
- **修复**：改为 `bodyToFlux(String.class)` 并逐块解析 `data:` token。
- **影响**：降低首 token 延迟，改善流式问答体验。

### 5. 上传入队失败后文档状态不一致

- **位置**：`services/api/src/main/java/com/dupi/rag/service/DocumentService.java`、`services/web/src/pages/KbDetailPage.tsx`
- **问题**：上传文件已保存到 MinIO 后，如果 Redis 入队失败，接口会抛异常或留下不可摄入的半成品状态；前端也默认把 HTTP 200 的上传都计为成功。
- **修复**：入队失败时将 `IngestJob` 与 `Document` 标为 `FAILED` 并记录错误；前端收到 `FAILED` 文档时计入失败并提示错误。
- **影响**：用户能看到明确失败状态，不再误判为“上传成功，正在处理”。

### 6. 文档删除清理失败不可观测

- **位置**：`services/api/src/main/java/com/dupi/rag/service/DocumentService.java`
- **问题**：删除文档时 Milvus 或 MinIO 清理异常会直接中断或被无日志吞掉，残留对象/向量难追踪。
- **修复**：对 Milvus / MinIO 清理分别做容错并记录 warn 日志，继续清理数据库源数据。
- **影响**：删除接口更幂等，清理失败可通过日志追踪。

### 7. 摄入回调缺少 job/doc/kb 绑定校验

- **位置**：`services/api/src/main/java/com/dupi/rag/service/IngestJobService.java`、`services/api/src/main/java/com/dupi/rag/controller/DocumentController.java`
- **问题**：内部回调只按 `jobId` 和 `docId` 分别查实体，未验证二者绑定关系；文档 ingest-job 查询也未校验 `kbId` 与 `docId`。
- **修复**：回调校验 `job.docId == doc.id` 且 `job.kbId == doc.kbId`；文档详情下的 ingest-job 查询先校验文档属于当前知识库。
- **影响**：避免错误回调污染其他文档状态。

### 8. Retrieve/Chat 请求级 `topK` 缺少边界

- **位置**：`services/api/src/main/java/com/dupi/rag/dto/RetrieveRequest.java`、`services/api/src/main/java/com/dupi/rag/dto/ChatRequest.java`、`RetrievalService.java`、`ChatService.java`
- **问题**：创建知识库时 `topK` 有范围约束，但检索/问答请求可传 0、负数或超大值。
- **修复**：DTO 增加 `@Min(1)` / `@Max(50)`；service 层做兜底 clamp。
- **影响**：避免 Milvus/Worker 参数错误和超大请求负载。

### 9. Worker Embedding 批量调用缺少分批和响应防御

- **位置**：`services/worker/app/embedder.py`、`services/worker/app/consumer.py`
- **问题**：大文档一次提交全部 chunk 给 embedding provider，可能超出服务限制；响应缺少数量、索引和 embedding 字段校验。
- **修复**：按 32 条分批；校验每批返回数量；优先按 `index` 排序，缺失时记录 warn 并使用响应顺序；检查每项 `embedding` 存在；consumer 校验向量数量与维度。
- **影响**：大文档摄入更稳定，provider 异常时错误更可定位。

### 10. 同一文件重复选择不会触发上传

- **位置**：`services/web/src/hooks/useDropzone.ts`
- **问题**：`<input type="file">` 未清空 value，用户再次选择同一个文件可能不会触发 `change`。
- **修复**：处理文件后重置 `inputRef.current.value = ''`。
- **影响**：同一文件可连续重传，便于失败后重试。

### 11. Worker 递归分块在无分隔符长文本上崩溃

- **位置**：`services/worker/app/chunker/recursive_chunker.py`
- **问题**：递归分块器降级到空分隔符时仍调用 `text.split("")`，Python 会抛出 `ValueError: empty separator`；当输入是没有换行、空格或 Markdown 分隔符的超长文本时，摄入任务会直接失败。
- **修复**：为 `sep == ""` 增加固定窗口切片分支，按 `chunk_size`/`chunk_overlap` 滑动切分，避免再调用空字符串 split。
- **影响**：纯长串、压缩文本、OCR 异常文本等极端输入也能稳定分块，Worker 不会因空分隔符崩溃。

## 已验证

- `services/api`: `mvn -q -DskipTests package` 通过。
- `services/worker`: `python -m compileall app` 通过。
- `services/web`: `npx tsc -b` 通过。
- `services/api`: `mvn verify` 通过，54 tests，JaCoCo 覆盖率检查通过。
- `services/worker`: `python -m coverage run -m pytest` 与 `python -m coverage report` 通过，27 passed，总覆盖率 96%。
- `services/web`: `vitest run --coverage` 通过，29 tests，总行覆盖率 98.61%，分支覆盖率 91.7%。
- 中文注释统计：注释行 45 行，中文注释 37 行，覆盖率 82.2%。

## 当前未完全解决/后续建议

- **完整 Web build 受本机 Node 版本阻塞**：当前 `node -v` 为 `v16.20.1`，Vite 5 构建阶段需要 `globalThis.crypto.getRandomValues`，`npm run build` 在 Vite 阶段失败；需切换 Node 18+ 后再跑完整 `npm run build`。
- **上传事务与 Redis 入队仍非原子**：当前已让入队失败可见，但更完整方案是 transactional outbox 或事务提交后入队。
- **删除进行中的文档仍有 Worker 竞态风险**：更完整方案是 tombstone/cancel set，并让 Worker indexing 前回查文档仍有效。
- **Milvus 单集合维度混用仍需 fail-fast**：建议启动或创建 KB 时校验 collection schema，或按模型/维度拆 collection。
- **内部接口需鉴权**：`/api/v1/internal/**` 建议增加 shared secret、mTLS 或网络层隔离。
