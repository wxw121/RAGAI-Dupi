# 安全加固与薄弱点修复记录（2026-07-06）

## 背景

本次扫描重点关注默认本地/单机部署的误暴露风险、internal API 调用边界，以及知识库删除在外部依赖异常时的可恢复性。

## 已修复薄弱点

| 薄弱点 | 风险 | 修复方式 | 验证 |
|------|------|------|------|
| 默认 Compose 暴露 PostgreSQL、Redis、MinIO、Milvus、API、Worker 端口 | 本机或共享网络环境下容易被非预期访问 | 默认只暴露 Web `8080`，其余服务改为 Docker 内部网络通信 | 使用脱敏 Compose 配置脚本检查端口拓扑 |
| 公开 API 与 internal API 缺少最低限度门禁 | 任何能访问端口的客户端都可直接调用业务接口或内部回调接口 | API 新增可选共享密钥过滤器：公开接口校验 `X-Dupi-API-Key`，internal 接口校验 `X-Dupi-Internal-Key` | `ConfigAndExceptionTest` 覆盖缺 key、正确 key、本地空 key |
| Web 反代在启用公开 API Key 后无法自动携带请求头 | 浏览器端请求会因缺少共享密钥被 API 拒绝 | Nginx 模板从 `DUPI_API_KEY` 注入 `X-Dupi-API-Key`，且只向 Web 容器传递所需变量 | 使用脱敏 Compose 配置脚本检查 Web 环境变量收窄 |
| Worker 调用 internal API 不携带内部密钥 | 启用 internal key 后摄入状态回调与混合检索语料拉取会失败 | Worker `post_status` 与 `fetch_kb_corpus` 在配置存在时携带 `X-Dupi-Internal-Key` | Worker 新增/更新测试覆盖 header 行为 |
| 原始 `docker compose config` 会展开真实 `.env` | 终端、聊天记录或截图可能泄露 DeepSeek/智谱 Key | 新增 `scripts/compose-config-redacted.ps1`，统一使用 JSON 展开后按敏感变量名脱敏 | 使用脱敏脚本检查配置，避免再次打印原始 Key |
| Compose 对 PostgreSQL/MinIO 有弱默认回退 | 共享或部署环境可能忘记替换默认密码 | `docker-compose.yml` 对关键密码改为必填，`.env.example` 改为强随机占位说明 | Compose 配置缺值时 fail-fast |
| 批量上传缺少入口保护 | 大量重复请求可能压垮上传/摄入链路 | 新增 `UploadRateLimitFilter`，按客户端 IP + API Key 分桶限流 | `ConfigAndExceptionTest` 覆盖超额返回 429 |
| 摄入队列缺少削峰 | Redis 待处理任务过多时仍继续上传文件，容易把 MinIO 和数据库写满后才暴露失败 | 上传落 MinIO 前检查 `INGEST_QUEUE_MAX_PENDING_JOBS`，达到阈值时快速拒绝 | `IngestJobProducerTest`、`DocumentServiceTest` 覆盖队列满拒绝 |
| Redis 入队失败后任务容易卡死 | 外部 Redis 短暂不可用会让文档进入不可恢复失败或长时间卡住 | 入队失败时保留 `PENDING + QUEUED`，`IngestJobService` 定时补偿扫描重新投递 | `IngestJobServiceTest` 覆盖重新投递和 Redis 持续不可用 |
| 批量上传单文件失败语义不清 | 一个坏文件可能造成整批失败，前端无法精确提示 | 批量上传返回逐文件 `success/errorMessage/document` 结果 | `DocumentServiceTest` 覆盖成功+空文件混合场景 |
| 知识库/文档删除依赖 Milvus 清理成功 | Milvus 短暂不可用时，用户无法删除存量记录或留下残留向量 | 删除前登记 `vector_cleanup_tasks`；Milvus 删除 best-effort，失败后由定时任务补偿 | `KnowledgeBaseServiceTest`、`DocumentServiceTest`、`VectorCleanupTaskServiceTest` 覆盖 |
| 旧知识库 embedding 配置不一致排查困难 | 切换智谱/其他 embedding 后，旧库可能因模型或维度不一致导致摄入、检索异常 | 知识库响应新增 `embeddingConfigCurrent` / `embeddingConfigWarning`，前端详情页展示警告 | `KnowledgeBaseServiceTest` 覆盖旧配置告警 |
| 问答无法回答缺少诊断信息 | 用户只能看到“根据现有知识库资料无法回答”，无法判断无命中、fallback 或配置问题 | `/retrieve` 和 Chat SSE 返回 `diagnostics`，前端展示检索模式、命中数、TopK 与 fallback 原因 | `RetrievalServiceTest`、`ChatServiceTest`、前端 `chat.test.ts` 覆盖 |
| 公共 API 缺少租户边界 | 只要猜到知识库 ID，就可能跨租户访问知识库、文档、检索或聊天会话 | 新增 `X-Dupi-Tenant-Id` 请求级租户上下文，KB 根边界按租户过滤；internal/worker 路径走系统查询 | `ConfigAndExceptionTest`、`KnowledgeBaseServiceTest`、`ChatSessionServiceTest` 覆盖 |
| 旧知识库缺少一键重建索引 | 切换 embedding 模型/维度后只能删除重建，存量文档恢复成本高 | 新增 `POST /api/v1/knowledge-bases/{kbId}/reindex`，更新 KB 配置、清理 chunks、登记向量补偿并重新入队全部文档 | `IngestJobServiceTest`、`ControllerLayerTest` 覆盖 |
| 摄入补偿持续失败无终态 | Redis 或 Worker 长期异常时任务会无限停留在 `PENDING + QUEUED` | 新增 `DEAD_LETTER` 状态和 `INGEST_RECOVERY_MAX_ATTEMPTS`，达到阈值后文档标记失败等待人工处理 | `IngestJobServiceTest` 覆盖 |
| 失败/死信任务只能靠 internal 接口处理 | 前端若调用 internal retry 会绕过租户边界，人工恢复路径不安全 | 新增公共 `POST /api/v1/knowledge-bases/{kbId}/ingest-jobs/{jobId}/retry`，服务层先校验租户 KB 与任务归属；文档页索引维护面板展示任务并支持重试 | `IngestJobServiceTest`、`ControllerLayerTest`、`resources.test.ts` 覆盖 |
| 大文档 Embedding 请求可能超过供应商单次 input 限制 | chunk 数量多时一次性提交全部文本，可能触发供应商 400/限流 | Worker `embed_batch` 按 `EMBEDDING_BATCH_SIZE` 拆分请求，默认 32，按原顺序合并结果并保留数量/形状校验 | `test_embedder_hybrid.py` 覆盖 |
| 高影响操作缺少审计记录 | 删除、reindex、retry 只能从业务结果反推，排障和责任追踪困难 | 新增 `audit_logs` 与 `AuditLogService`，覆盖知识库/文档删除、会话批量删除、reindex、摄入 retry、向量清理 retry | `AuditLogServiceTest` 与相关服务测试覆盖 |
| 向量清理补偿缺少人工运维入口 | 残留向量清理失败只能等定时任务，前端不可见 | 新增 `/api/v1/ops/vector-cleanup-tasks` 列表和 retry 接口，文档页索引维护面板展示并重试任务 | `VectorCleanupTaskServiceTest`、`ControllerLayerTest`、`resources.test.ts` 覆盖 |
| RAG 质量缺少稳定回归基线 | 修复检索/分块/embedding 后难以及时发现命中退化 | 新增 `examples/rag-eval-cases.json` 和 `scripts/rag-regression-eval.ps1`，以命中数、文件名和关键片段做检索层断言 | PowerShell 解析检查；需运行中服务执行端到端评测 |
| 关键后端分支单测覆盖不足 | 后续重构 Milvus、摄入补偿、审计和运维入口时容易出现回归 | 补充 Milvus 加载/删除异常、摄入补偿/死信/reindex、审计查询、Cookie/CSRF、Redis 登录锁定、资源级授权、会话引用快照、上传限流边界测试 | `mvn verify` 通过 158 个测试，JaCoCo 行覆盖率 95.45% |

## 配置说明

- `DUPI_API_KEY`：公开 API 共享密钥；本地可信开发可留空，部署/共享环境建议设置。
- `DUPI_INTERNAL_KEY`：internal API 共享密钥；API 与 Worker 必须保持一致。
- `UPLOAD_RATE_LIMIT_ENABLED` / `UPLOAD_RATE_LIMIT_REQUESTS` / `UPLOAD_RATE_LIMIT_WINDOW_SECONDS`：上传入口限流配置。
- `INGEST_QUEUE_MAX_PENDING_JOBS`：摄入队列高水位；达到阈值时上传入口快速拒绝，默认 `200`。
- `INGEST_RECOVERY_CRON`：摄入任务补偿扫描 Cron；默认每 2 分钟扫描 `PENDING + QUEUED` 任务并重新投递。
- `INGEST_RECOVERY_MAX_ATTEMPTS`：摄入补偿最大自动重试次数；默认 `3`，达到后进入 `DEAD_LETTER`。
- `EMBEDDING_BATCH_SIZE`：Worker 单次 Embedding 请求文本数；默认 `32`，可按供应商限制下调。
- `ORPHAN_VECTOR_CLEANUP_CRON`：残留 Milvus 向量补偿清理定时任务 Cron。
- `X-Dupi-Tenant-Id`：公共 API 租户上下文请求头；未传时使用 `default`。
- 默认 Compose 只暴露 `web:8080`；直连调试 API、数据库或对象存储时应使用临时 override 文件显式映射端口。
- 检查 Compose 展开配置时使用 `powershell -ExecutionPolicy Bypass -NoProfile -File scripts/compose-config-redacted.ps1`，不要共享原始 `docker compose config` 输出。

## 剩余建议

- 生产级多用户访问已补齐内置账号、RBAC、Cookie 会话、CSRF、Redis 登录锁定、知识库资源级授权和基础审计日志查询；后续仍需 SSO/OIDC、账号管理 UI、权限分配 UI、审计导出/告警和密钥轮换。
- 本地 `deploy/.env` 存放真实第三方 API Key，应继续保持 gitignore；本次原始配置输出曾暴露真实 DeepSeek/智谱 Key，需在对应平台人工轮换后再继续共享环境使用。
- 上传限流已按租户 + 已认证用户分桶，匿名/API Key 请求按租户 + IP + API Key 分桶；后续仍可升级为长期配额、上传取消与可视化运维告警。
- 知识库 embedding 配置已提供不一致提示、reindex API、前端索引维护入口，以及 Milvus collection 启动维度 fail-fast；审计日志已提供基础查询页面，后续可继续补充导出、保留策略、告警和管理员权限配置。
