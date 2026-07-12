# 待办清单

## 进行中

（无）

## 待办

- [ ] 生产级鉴权增强：SSO/OIDC、外部身份源同步、密钥轮换审批流
- [ ] 上传保护升级：按租户/用户配额、上传取消与告警
- [ ] 运维面板增强：任务高级筛选、告警外部通知、管理员权限配置与审计归档对接
- [ ] Milvus BM25 sparse 字段生产调优与索引参数压测
- [ ] Parent-Child / QA 索引模式（V3）
- [ ] 可视化 Knowledge Pipeline DSL（V3）
- [ ] K8s Helm Chart（V4）

## 已完成

- [x] P0：强制关键基础设施密钥配置 + Compose 脱敏配置检查脚本 — 2026-07-06
- [x] P1：上传限流、队列削峰、摄入补偿扫描、批量上传逐文件结果、残留 Milvus 向量补偿清理 — 2026-07-06
- [x] P1：旧库 embedding 配置不一致提示 + 问答检索诊断 — 2026-07-06
- [x] P0：公共 API 租户隔离（`X-Dupi-Tenant-Id`，默认 `default`）— 2026-07-06
- [x] P1：旧知识库 reindex API + 摄入补偿死信状态 — 2026-07-06
- [x] P1：文档页索引维护面板（reindex、摄入任务列表、失败/死信任务重试）— 2026-07-06
- [x] P1：审计日志表与高影响操作记录 — 2026-07-06
- [x] P1：向量清理任务运维接口 + 文档页重试入口 — 2026-07-06
- [x] P1：RAG 回归评测用例与脚本 — 2026-07-06
- [x] P1：Web 维护流程 E2E 脚本 — 2026-07-06
- [x] P1：Worker `embed_batch` 按 `EMBEDDING_BATCH_SIZE` 分批请求 Embedding 供应商 — 2026-07-06
- [x] 账号/RBAC：Cookie 会话 + CSRF、Redis 登录锁定、知识库资源级授权、审计日志查询页 — 2026-07-06
- [x] Outbox/Tombstone/Ops：transactional outbox、删除 tombstone、文档/会话/任务实例级授权、账号管理只读 UI、审计导出/保留/告警 — 2026-07-07
- [x] 账号/RBAC 管理闭环：账号创建/更新、权限分配 UI、禁用/启用、tokenVersion 轮换、PBKDF2 哈希生成 — 2026-07-07
- [x] API 构建基线：Maven Wrapper 固定 Apache Maven 3.9.9，Docker 构建镜像同步固定版本 — 2026-07-07
- [x] P1：Chat 引用来源 snippet 支持 Markdown 摘要渲染 — 2026-07-11
- [x] P1：Chat 历史会话过滤空会话，Milvus transient search failure 走本地文本兜底，前端空错误文案兜底 — 2026-07-11
- [x] P1：Worker 索引清理旧向量时容忍 Milvus `Timestamp lag too large` 临时异常，避免批量上传任务被误判失败 — 2026-07-11
- [x] P1：上传限流按租户 + 已认证用户分桶，匿名请求继续按租户/IP/API Key 分桶 — 2026-07-11
- [x] P1：Milvus 已存在集合启动时校验 embedding 向量维度，不匹配直接 fail-fast — 2026-07-11
- [x] 文档：README Docker 启动排障章节（镜像加速 / pip 镜像 / 依赖锁定 / CORS / Milvus 维度重置）— 2026-07-11
- [x] 后端单元测试覆盖率门禁：`mvn verify` 通过 158 个测试，JaCoCo 行覆盖率 95.45% — 2026-07-06
- [x] Web 文档管理：批次上传（多选/拖拽，后端批量 API） — 2026-06-21
- [x] Web 文档管理：单文档删除（对接 DELETE API） — 2026-06-21
- [x] Canonical Markdown 摄入 + 结构感知分块（markdown_chunker） — 2026-06-20
- [x] Web 智能问答 Markdown 渲染与自动换行（react-markdown + remark-gfm + remark-breaks） — 2026-06-20
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
