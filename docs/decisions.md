# 技术决策

## 2026-07-07 — Transactional Outbox、删除 Tombstone、实例级授权与 Ops 运维增强

**背景**：上传、重试和 reindex 需要把数据库状态与 Redis 摄入队列投递解耦，避免事务已提交但队列消息丢失；文档删除后仍可能遇到 outbox 或 Worker 迟到回调；知识库级授权还需要继续下钻到文档、会话和任务实例；运维侧也需要账号可视化、审计导出、保留和告警。
**决策**：新增 `ingest_outbox_events` 和 `IngestOutboxService`，上传/重试/reindex 事务内只写 outbox，事务提交后由 dispatcher 投递 Redis，失败按退避重试。新增 `document_tombstones` 和 `DocumentTombstoneService`，删除前记录 tombstone，outbox dispatcher 与 Worker 回调遇到已删 docId 时取消/忽略。资源授权从知识库范围扩展到文档、会话和向量清理任务实例级：先解析实例归属 kbId，再校验 `SecurityContext.canAccessKnowledgeBase(...)`。Ops 增加 `/ops/accounts` 只读账号管理页、`/api/v1/ops/accounts` 脱敏账号元数据接口、审计 CSV 导出、保留清理和失败审计告警摘要。
**理由**：outbox 让摄入投递具备事务后可靠性；tombstone 关闭删除后迟到写入的竞态窗口；实例级授权避免拥有全局权限点的用户横向操作其他知识库资源；账号和审计运维入口让管理员能查看配置、导出留痕并发现异常失败峰值。
**影响范围**：`IngestOutboxEvent` / `IngestOutboxService` / `IngestOutboxEventRepository`、`DocumentTombstone` / `DocumentTombstoneService` / `DocumentTombstoneRepository`、`V5__ingest_outbox_and_document_tombstones.sql`、`DocumentService`、`IngestJobService`、`VectorCleanupTaskService`、`AuditLogService`、`OpsController`、`AccountService`、`OpsAuditPage.tsx`、`OpsAccountsPage.tsx`、`AppLayout.tsx`、`knowledgeBase.ts`、`deploy/.env.example`。
**遗留**：账号管理当前为只读配置视图，后续可补账号创建/禁用、权限分配、密码哈希生成和 tokenVersion 轮换；审计告警当前为接口/页面摘要，生产可继续接入 Webhook/邮件/IM；多实例 outbox 高并发场景可增加 claim/行级锁避免重复扫描竞争。

## 2026-07-06 — 默认部署收窄端口暴露并增加共享密钥门禁

**背景**：扫描项目薄弱点时发现默认 Compose 将 PostgreSQL、Redis、MinIO、Milvus、API、Worker 等内部服务映射到宿主机，且公开 API 与 internal API 缺少最低限度访问门禁。
**决策**：默认 Compose 只暴露 Web Nginx `:8080`；API、Worker 与基础设施仅保留 Docker 内部网络访问。API 新增可选共享密钥鉴权：公开接口使用 `X-Dupi-API-Key`，internal 接口使用 `X-Dupi-Internal-Key`；环境变量为空时保持本地开发兼容。Web Nginx 代理自动注入公开 API Key，Worker 回调与混合检索语料拉取自动注入 internal Key。
**理由**：以最小侵入方式降低本机/单机部署的误暴露风险，同时不破坏当前本地自测流程；后续多租户版本再升级为用户级认证、权限模型与审计。
**影响范围**：`deploy/docker-compose.yml`、`deploy/.env.example`、`services/api/src/main/java/com/dupi/rag/config/`、`services/web/nginx.conf`、`services/web/Dockerfile`、`services/worker/app/callback.py`、`services/worker/app/retrieval/hybrid.py`。
**遗留**：共享密钥只适合作为单机/内网最低限度门禁；生产级多用户访问已补充内置账号、RBAC、Cookie 会话和审计查询，后续仍需 SSO/OIDC、账号管理 UI、密钥轮换与审计告警。

## 2026-07-06 — P0/P1 上传保护与残留向量补偿

**背景**：P0 安全扫描确认真实 `.env` 展开值曾通过原始 Compose 配置输出暴露，且默认弱口令回退不适合共享环境；P1 继续聚焦批量上传过载、批次内部分失败语义、best-effort 删除后的残留向量。
**决策**：Compose 对 PostgreSQL/MinIO 密码改为必填环境变量，并新增 `scripts/compose-config-redacted.ps1` 作为脱敏配置检查入口；上传接口增加 `UPLOAD_RATE_LIMIT_*` 限流和 `INGEST_QUEUE_MAX_PENDING_JOBS` 队列高水位；批量上传返回 `BatchDocumentUploadResponse`，逐文件携带成功/失败结果；Redis 入队失败时任务保持 `PENDING + QUEUED`，由 `INGEST_RECOVERY_CRON` 定时补偿扫描重新投递；新增 `vector_cleanup_tasks` 表与 `VectorCleanupTaskService` 定时补偿清理 Milvus 残留向量。
**理由**：P0 优先阻止弱默认值和二次泄露；P1 通过入口限流、队列削峰和持久化补偿任务提升上传/摄入链路韧性，并用补偿任务消化 Redis 或 Milvus 短暂不可用导致的卡滞与残留。
**影响范围**：`deploy/.env.example`、`deploy/docker-compose.yml`、`scripts/compose-config-redacted.ps1`、`DocumentController`、`DocumentService`、`IngestJobProducer`、`IngestJobService`、`UploadRateLimitFilter`、`VectorCleanupTaskService`、`V3__vector_cleanup_tasks.sql`、`services/web/src/api/documents.ts`、`KbDetailPage.tsx`。
**遗留**：真实第三方 Key 的轮换需由持有人在 DeepSeek/智谱平台完成；生产级上传保护仍可继续升级为按租户/用户配额、上传取消与审计。

## 2026-07-06 — Embedding 配置提示与问答诊断

**背景**：旧知识库可能保留历史 embedding 模型/维度，切换到智谱 `embedding-2` 后会造成摄入或检索异常；同时“根据现有知识库资料无法回答”缺少可见诊断，难以区分无命中、fallback 或配置不一致。
**决策**：知识库响应新增 `embeddingConfigCurrent` / `embeddingConfigWarning`；`/retrieve` 返回 `diagnostics`；Chat SSE 的 `retrieval` 事件从旧数组兼容升级为 `{ citations, diagnostics }`；前端知识库详情页展示 embedding 警告，问答输入区展示检索诊断。
**理由**：先给用户和运维提供可见诊断，避免把所有问答失败都归因到 LLM；真正的 embedding 配置迁移/重建索引 API 后续单独实现。

## 2026-07-06 — 租户隔离、旧库 reindex 与摄入死信

**背景**：公共 API 之前主要依赖知识库 ID 访问资源，缺少请求级租户边界；旧知识库 embedding 配置提示后仍需要一键重建路径；摄入补偿持续失败时缺少终态，任务会长期停留在 `PENDING + QUEUED`。
**决策**：新增 `TenantContextFilter`，公共 `/api/**` 请求读取 `X-Dupi-Tenant-Id`，默认 `default`，并让知识库 CRUD、文档、检索、聊天会话等公共路径通过租户限定 KB 根边界；internal/worker/定时补偿路径使用系统级查询。新增 `POST /api/v1/knowledge-bases/{kbId}/reindex`，按当前 embedding 配置更新知识库、清理旧 chunks、登记 Milvus 向量补偿并重新入队全部文档。新增 `DEAD_LETTER` 摄入状态，补偿失败达到 `INGEST_RECOVERY_MAX_ATTEMPTS` 后进入死信。公共重试入口为 `POST /api/v1/knowledge-bases/{kbId}/ingest-jobs/{jobId}/retry`，先校验租户 KB 和任务归属，再复用摄入重试逻辑；Web 文档页通过“索引维护”面板承接 reindex、任务列表和失败/死信任务重试。
**理由**：用最小改动补上多租户预留字段的真实隔离语义，同时为 embedding 切换后的旧库提供自助恢复路径；死信状态让持续失败从“无限重试”变成可观察、可人工处理的运维事件。
**影响范围**：`TenantContextFilter`、`TenantContext`、`KnowledgeBaseRepository`、`KnowledgeBaseService`、`ChatSessionService`、`InternalController`、`IngestJobService`、`KnowledgeBaseController`、`IngestJobStatus`、`IngestStage`、`application.yml`、`deploy/.env.example`、`services/web/src/api/knowledgeBase.ts`、`KbDetailPage.tsx`。
**遗留**：当前租户 ID 在未登录兼容路径仍可由请求头传入，适合本地/内网隔离；登录用户已由 token 租户覆盖请求头。生产级多用户后续仍需 SSO/OIDC、账号管理 UI 与更完整的管理员运维面板。
**影响范围**：`KnowledgeBaseResponse`、`KnowledgeBaseService`、`RetrieveResponse`、`RetrievalService`、`ChatService`、`services/web/src/api/chat.ts`、`ChatPanel.tsx`、`KbDetailPage.tsx`。
**遗留**：旧库已支持手动 reindex；后续可补充批量迁移、审计记录与更细粒度的重建进度。

## 2026-07-06 — 审计日志、向量清理运维与 RAG 回归评测

**背景**：删除、reindex、retry 等高影响操作此前只能从业务状态间接推断；向量清理补偿任务缺少可见运维入口；RAG 质量也缺少一组可重复的基线用例。

**决策**：新增 `audit_logs` 表和 `AuditLogService`，记录知识库删除、文档删除、聊天会话批量删除、知识库 reindex、摄入任务手动 retry、向量清理任务手动 retry，并通过 `GET /api/v1/ops/audit-logs` 提供按租户、动作、目标类型、状态和 limit 的查询。新增 `OpsController` 暴露 `GET /api/v1/ops/vector-cleanup-tasks` 与 `POST /api/v1/ops/vector-cleanup-tasks/{taskId}/retry`，Web 文档页“索引维护”区域展示向量清理任务并支持重试，Web 运维区新增 `/ops/audit-logs` 审计查询页。新增 `examples/rag-eval-cases.json` 与 `scripts/rag-regression-eval.ps1`，优先用检索命中、引用文件和关键片段做稳定回归，而不是依赖 LLM 自由文本完全一致。

**理由**：审计日志让高风险操作可追踪；向量清理任务入口让残留向量补偿从“后台黑盒”变成可人工处理的运维事件；RAG 回归评测用检索层断言降低随机生成文本对测试稳定性的影响。

**影响范围**：`AuditLog` / `AuditLogService` / `AuditLogRepository`、`AuditLogQuery`、`AuditLogResponse`、`V4__audit_logs.sql`、`OpsController`、`VectorCleanupTaskService`、`KbDetailPage.tsx`、`OpsAuditPage.tsx`、`AppLayout.tsx`、`knowledgeBase.ts`、`examples/rag-eval-cases.json`、`scripts/rag-regression-eval.ps1`、`scripts/e2e-web-maintenance-flow.ps1`。

**遗留**：审计日志已支持基础查询页面；后续可增加导出、告警、保留策略和更细的管理员权限配置。

## 2026-07-06 — Cookie 会话、CSRF、Redis 登录锁定与知识库资源授权

**背景**：内置账号/RBAC 已补齐角色和权限点，但浏览器侧仍存在可读 Bearer token 的暴露面；Cookie 化后需要 CSRF 防护；登录失败锁定在多副本场景需要共享状态；全局权限点也不足以表达“只能访问部分知识库”的资源边界。
**决策**：登录接口写入 `DUPI_AUTH` HttpOnly Cookie，并返回 `csrfToken`；前端本地只保存 CSRF token，所有请求使用 `credentials: include`，mutating 请求携带 `X-Dupi-CSRF-Token`；服务端只对 Cookie auth 执行 `DUPI_CSRF` Cookie + Header 双提交校验，Bearer/API Key 保持脚本兼容。新增 `LoginFailureStore` 抽象和 Redis 实现，登录失败计数跨 API 实例共享。账号配置新增 `knowledgeBaseIds`，token 与 `SecurityContext` 携带知识库范围，包含 `{kbId}` 的公开入口在权限点后继续执行资源级授权。
**理由**：HttpOnly Cookie 降低 token 被前端脚本直接读取的风险；CSRF 双提交保护浏览器 Cookie 会话；Redis 锁定避免多副本绕过；资源级授权把“能做什么”和“能访问哪些知识库”拆开，便于下一步账号管理 UI 承接。
**影响范围**：`AuthController`、`LoginResponse`、`ApiTokenService`、`ApiKeyAuthFilter`、`ApiSecurityProperties`、`SecurityContext`、`LoginFailureStore`、`RedisLoginFailureStore`、`services/web/src/api/client.ts`、`services/web/src/api/chat.ts`、`services/web/src/api/knowledgeBase.ts`、`services/web/src/pages/OpsAuditPage.tsx`、`deploy/.env.example`。
**遗留**：仍需外部 SSO/OIDC、账号/权限配置页面、CSRF token 轮换策略和资源授权扩展到文档/会话实例级。

## 2026-07-06 — Worker Embedding 请求分批

**背景**：大文档分块数量较多时，Worker 原先会把所有 chunk 文本一次性传给 Embedding 供应商，容易触发单次 `input` 数组长度限制或限流。
**决策**：Worker `embed_batch` 按 `EMBEDDING_BATCH_SIZE` 拆分请求，默认 `32`；每批仍按供应商返回的 `index` 排序，缺失 `index` 时保留响应顺序，最终按原 chunk 顺序合并向量并执行数量/形状校验。
**理由**：以配置项适配不同 Embedding 供应商限制，避免为大文档单独引入新的队列模型，同时保持摄入任务的原有状态回调契约不变。
**影响范围**：`services/worker/app/config.py`、`services/worker/app/embedder.py`、`services/worker/tests/test_embedder_hybrid.py`、`deploy/.env.example`。

## 2026-07-06 — 知识库删除以数据库为最终事实源

**背景**：知识库删除会先清理 Milvus 向量，再删除数据库记录；当 Milvus 短暂不可用或集合状态异常时，用户无法删除知识库，容易被历史残留向量库状态卡住。
**决策**：`KnowledgeBaseService.delete()` 和 `DocumentService.delete()` 在删除前登记向量清理任务，随后 best-effort 清理 Milvus；向量清理失败时记录 warn 日志，但继续删除数据库记录。
**理由**：知识库列表和业务可见状态以数据库为最终事实源；外部向量库残留由持久化补偿任务重试，不能阻断用户释放不可用知识库或文档。
**影响范围**：`KnowledgeBaseService`、`DocumentService`、`VectorCleanupTaskService`、`VectorCleanupTaskRepository`、`KnowledgeBaseServiceTest`、`DocumentServiceTest`。
**遗留**：后续可补充清理任务管理页面与运维告警。

## 2026-06-21 — 批次上传改为后端批量文档 API

**背景**：用户需在文档管理页一次上传多个文件；摄入链路已是「一文件一 Document + 一 IngestJob」，Worker 无需 batch 契约。  
**决策**：当前实现提供 `POST /api/v1/knowledge-bases/{kbId}/documents/batch`，前端 `uploadDocuments` 通过 `files` FormData 字段一次提交多文件；后端仍按单文件粒度创建 Document 与 IngestJob，并返回逐文件结果。
**理由**：保留“一文件一任务”的摄入模型，同时减少前端多文件上传时的 N 次 HTTP 往返，并让文档管理页的批量上传行为更接近用户预期。
**影响范围**：`DocumentController`、`DocumentService.uploadBatch`、`services/web/src/api/documents.ts`、`UploadZone.tsx`、`KbDetailPage.tsx`。
**遗留**：大批量文件仍需考虑请求体大小、上传队列取消与后台异步上传编排。

## 2026-06-20 — 摄入统一 Canonical Markdown + 结构感知分块

**背景**：`.md` 等文档摄入时仅 `read_text` + `recursive_split`，表格/标题/代码块在 chunk 边界被切断；Chat 乱格式根因在检索上下文结构丢失，非单纯前端渲染。  
**决策**：Worker 摄入前 `canonicalize()` 将 PDF/DOCX/XLSX/TXT/MD 转为规范 Markdown；`markdown_chunker` 按 ATX 标题、GFM 表格、fenced code 边界切分，chunk 元数据含 `heading`、`block_type`；`recursive` 默认策略亦走 markdown chunker；PDF 用 `pymupdf4llm`。  
**理由**：从源头保留可检索、可生成的结构，优于统一转 PDF（二次有损）或仅前端 normalize 兜底。  
**影响范围**：`services/worker/app/canonical/`、`markdown_chunker.py`、`consumer.py`、`ChunkStrategy`、`RetrievalService.buildContext`、`ChatService` prompt、`requirements.txt`。  
**遗留**：已索引文档需 re-ingest；semantic 分块仍基于 canonical 全文而非 MD 结构边界。

## 2026-06-20 — Web 问答使用 react-markdown 渲染助手回复

**背景**：RAG 问答助手返回 Markdown 格式文本（标题、列表、代码块），前端以纯文本展示，用户阅读困难；首版 `react-markdown` 后仍存在长文本不换行。  
**决策**：助手消息统一经 `MarkdownContent` 组件渲染；栈为 `react-markdown` + `remark-gfm`（表格/任务列表）+ `remark-breaks`（单换行转 `<br>`）+ `@tailwindcss/typography`（`prose` 排版）；气泡与 flex 容器使用 `min-w-0` + `break-words` + `overflow-wrap:anywhere` 保证自动换行；代码块 `pre` 使用 `whitespace-pre-wrap` 软换行。  
**理由**：展示层问题无需改后端 SSE；remark-breaks 改善 LLM 单换行输出；flex `min-w-0` 是 Web 聊天 UI 自动换行的常见必要条件。  
**影响范围**：[`services/web/src/components/MarkdownContent.tsx`](services/web/src/components/MarkdownContent.tsx)、[`ChatPanel.tsx`](services/web/src/components/ChatPanel.tsx)、[`tailwind.config.js`](services/web/tailwind.config.js)、`package.json`。

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
