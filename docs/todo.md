# 待办清单

## 进行中

（无）

## 待办

- [ ] 知识库 embedding 配置：支持更新 API 或迁移旧库（当前旧库 `text-embedding-3-small` 会导致智谱 400）
- [ ] 在 README 补充 Docker 启动排障章节（镜像加速 / pip 镜像 / 依赖锁定 / CORS / Milvus 维度重置）
- [ ] Worker `embed_batch` 分批调用智谱（大文档单次 `input` 数组可能超限）
- [ ] 生产级鉴权：JWT + API Key（V2 完善）
- [ ] Milvus BM25 sparse 字段生产调优与索引参数压测
- [ ] Parent-Child / QA 索引模式（V3）
- [ ] 可视化 Knowledge Pipeline DSL（V3）
- [ ] K8s Helm Chart（V4）

## 已完成

- [x] E2E 主流程 8/8 全绿（智谱 Embedding + DeepSeek Chat） — 2026-06-19
- [x] Web 控制台 CORS 403 修复（`:8080` Origin） — 2026-06-19
- [x] 智谱 Embedding 配置与新建 KB 默认维度/模型 — 2026-06-19
- [x] `LlmClient` DeepSeek SSE 流式 token 解析 — 2026-06-19
- [x] E2E 主流程脚本（对应 Web 按钮） — 2026-06-19
- [x] 修复文档上传 `created_at` null 导致 500 — 2026-06-19
- [x] `/project-init` 文档四件套 — 2026-06-19
- [x] M0：Spring Boot + Python Worker + docker-compose — 2026-06-19
- [x] M1：上传→解析→分块→Embedding→Milvus 摄入闭环 — 2026-06-19
- [x] M2：向量检索 + RAG SSE 流式问答 — 2026-06-19
- [x] M3：健康检查、重试、结构化日志 — 2026-06-19
- [x] V2 骨架：混合检索、Rerank、语义分块、Excel、生成中断 — 2026-06-19
- [x] Docker Compose 本地全栈启动（含镜像加速、pip 镜像、依赖修复） — 2026-06-19
