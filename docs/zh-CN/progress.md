# 进展记录

<!-- language-switch -->
[English](../en/progress.md)





## V1.5.0发布结束（2026-07-20）

API和Web发布元数据与“1.5.0”保持一致。
-增加了V1.5.0版本说明和' docs/v1.5-release-runbook。md '用于Flyway V18-V20、Profile V2的推出、修订绑定评估、激活、监控和回滚。
-最终的本地产品闸门通过了发布diff: API 482/482与95% JaCoCo闸门，Worker 96/96, Web 82/82，和1794模块生产构建。
—撰写编辑配置和V1.5.0版本扫描通过。扫描记录图像摘要‘ sha256:74043096b1740cad517a78f0792d79ea25a6bed99d17c1d8c96a7af98b05121c ’，大小为640,310,825字节，没有Python发现，以及22个精确的上游未修复的操作系统异常，到期日期为2026-08-15。
发布分支推送CI #30和PR CI #31通过API、Worker、Web、存储库固定的Pester 4.10.1契约和Docker Compose。在最终文档提交和合并‘ main ’重复绿色之前，带注释的标记一直被阻塞。

V1.4.2治理操作稳定性（2026-07-18）

—增加OPS_ADMIN操作符的只读GET /api/v1/ops/governance-summary界面。
—汇总包括generatedAt、uploadQuota、ingestJobs、ingestOutbox、failureNotifications、vectorCleanup和alerts。
增加了scripts/smoke-governance-summary。ps1和Pester覆盖端点路径、安全参数、模式验证、样本模式和编校证据。
-目前重点验证：API GovernanceOpsServiceTest和ControllerLayerTest通过；鬼烟测试通过了4 / 4。
-。在本地Web依赖设置中忽略npm-cache。
本地Web验证必须使用npm脚本，所以services/ Web /scripts/node16-webcrypto。cjs加载Node 16 WebCrypto shim；不要在此工作站上直接运行原始访问或访问测试。
-此条目仅记录正在进行的V1.4.2开发；它不是一个合并、标记或发布记录。

V1.4.1上传治理（2026-07-18）

-版本元数据与API ‘ 1.4.1- snapshot ’和Web ‘ 1.4.1 ’对齐；航路推进到V17。
-增加了持续保留字节/文档和滚动窗口上传记帐，显式配额保留，每个文件的幂等重放/冲突处理，‘ GET /api/v1/upload-quota ’，以及带有Retry-After的409/413/429映射。
-增加了摄取执行id，索赔，租约，单调回调序列，排队/运行取消状态，陈旧回调确认和终端状态保护。
-增加了一个单独的重复数据删除终端故障通知表/服务，带有可选的webhook交付，有界的回退重试和明确的‘ EXHAUSTED ’；取消不会创建失败事件。
- Worker使用ready-to-processing Redis move，仅在终端处理后才确认处理有效负载，在工作之前声明执行，刷新租约，检查取消，并在索引开始后取消时清除向量。发布映像使用仅cpu的火炬‘ 2.13.0+cpu ’，删除了可修复的‘ PYSEC-2025-194 ’发现。
Web使用有边界的每个文件上传，具有稳定的等幂键，配额显示，retry - after消息，传输abort/重试，获取取消，显式‘ currentJob ’，故障通知重复数据删除和序列化轮询。
-重点验证通过：摄取失败通知webhook交付/重试5/5，API文档/当前作业和摄取端点套件，Web管理的上传套件和Worker可靠队列套件。
-最终本地gate通过：API ' mvn clean verify ‘ 430/430 with JaCoCo 95.1306%, Worker pytest 75/75, Web Vitest 78/78， Web生产构建（1,794个模块），Pester发布策略14/14，Compose config exit 0和’ git diff -check ' exit 0。
-最终版本扫描没有Python发现，一个640,389,450字节的非根CPU Worker映像，摘要‘ sha256:eec613fab9cdd1d873b95172f98d42ade5989238e2b0f76761b6b4f63b86515a ’，以及22个确切的上游未修复的操作系统异常（19 HIGH, 3 CRITICAL）将于2026-08-15到期。证据:“工件/ v1.4.1-release-scan /总结。md '，依赖锁，SBOM格式，pip-audit JSON和Trivy JSON。

V1.3 RAG质量和稀疏迁移（2026-07-15）

V1.4.0可验证恢复（2026-07-16）

-实现了私人确定性档案的记录，原始对象，密集向量，活跃稀疏向量，和一个清单最后写。
-使用确定性标识符实现隐藏目标恢复，重试，放弃，只有在验证后才准备好。
-增加了‘ KB_RECOVERY ’，审计有界后台命令，租户/ kb范围的路由，流式ZIP下载和恢复操作面板。
- Real Compose排练于2026-07-16通过，包含2个文档，9个存档项，10,946字节，匹配对象/矢量校验和，匹配恢复的记录计数和检索命中，标准对象损坏拒绝，以及范围清理。证据:“工件/ v1.4-recovery / rehearsal.json”。
凭据铬通过1/1对‘ http://localhost:8080 ’，包括恢复面板和成功的E2E清理。
-真实演练暴露和固定管理实体时间戳丢失，密集向量“Double”/“Float”转换，强读后写验证，终端异步故障记录，V15/V16存档证据清理约束。
- V1.4.0恢复范围完成：最终工件记录‘ corruptionBlocked=true ’。
-正式发布扫描在2026-07-17通过了一个634,098,035字节的非根CPU Worker映像和23个确切的上游未固定异常在2026-08-15到期。证据:“工件/ v1.4-release-scan /总结。md '，图像依赖锁，SBOM， pip-audit和Trivy JSON。
-最终通过的本地门：API 351测试与JaCoCo检查，Worker 45测试，Web 68测试加上生产构建，和Pester 26测试。V1.4.1在V1.4.0发布之后启动。

- 已完成质量策略、baseline、不可变评测证据和门禁。
- 已完成 Retrieval Profile 控制台、激活和受 PASS 证据约束的 rollback。
- 已完成 Milvus 2.5.4 原生 BM25、回填、双写、Shadow、Cutover、完成态持续写入和全版本删除同步。
- 真实 Milvus 已验证 v1/v2/v3 完整迁移、v2 -> v1 rollback、Sparse 删除同步；浏览器 E2E 1/1 通过，Web 全量 62/62 通过。
- 最终真实语料热态结果为 VECTOR P95 55 ms、HYBRID P95 99 ms、HYBRID+RERANK P95 434 ms，三组 PASS 1/1、fallback 0，重排组 `rerankRank=1`。
- API 全量 288 项通过并满足 95% JaCoCo 行覆盖率门禁；Worker 全量 41 项、Web 全量 62 项通过，Web 生产构建成功。

## 当前状态

V1.5.0 RAG质量升级P2完成：现有的VECTOR / HYBRID / RERANK模式和CLASSIC默认值保持兼容，现在添加了可过滤的配置文件v2索引超集，组合加权融合，修订绑定的RAG质量门和Web准备/门比较。

## 最近进展

2026-07-19 （V1.5.0 RAG质量升级P2）
- [Profile v2隔离索引]Worker摄取现在构建一个可重用的原始/父/子/QA超集，并写入一个可过滤的‘ MILVUS_PROFILE_COLLECTION ’；遗留的原始向量被双重写入，直到知识库安全切换，之后持久的切换状态防止回退到已删除的遗留索引。
-[准备和修订]API要求每个文档在‘ index_schema_version=2 ’时‘ COMPLETED ’，跟踪知识库‘ index_revision ’，并分离遗留/配置文件清理目标，因此eval决策仍然与当前索引状态相关联。Reindex将文档滚动到位，而不是在事务性作业创建之前删除整个动态概要索引。
-[联合加权融合]API向量和Worker混合检索独立查询子路由和QA路由，然后应用‘ COMBINED_CHILD_WEIGHT=1.0 ’， ‘ COMBINED_QA_WEIGHT=0.8 ’和‘ RRF_K=60 ’的加权RRF；重新排名在融合后运行。
-[质量门]RAG eval自动比较非经典候选与经典，存储命中/引用布尔值，并返回PASSED/BLOCKED/INSUFFICIENT_DATA/STALE/INDEX_NOT_READY/ not_evaluate决策。非经典默认激活需要当前通过的门或返回‘ 409 retrieval_profile_gate_blocked ’。
- [Web面板]知识库设置显示配置文件准备，模式/修订，和大门徽章；被阻止的候选对象在本地被禁用，RAG评估面板显示候选对象与经典对象的比率、差值和原因。

### 2026-07-19 (V1.5.0 RAG Quality Upgrade P1)
- [QA 候选生成] — Worker 通过带 internal key 的内部 API 分批请求候选问答（每批最多 16 个 source），API 复用现有 `LlmClient` 和 LLM 凭据；严格解析 JSON、过滤未知 source/空值、按 source 去重并限制最多 3 条。
- [QA 索引与降级] — `QA_ASSISTED` 以普通原文块为 QA source，`COMBINED` 以父块为 source；QA 块与可检索原文块/子块共同进入 Embedding 和 Milvus，同时保存 `source_chunk_id`、问题、答案和 source 类型。QA 调用或响应失败时不影响原文摄入完成。
- [QA 命中回填] — `QA_ASSISTED / COMBINED` 命中 QA 块后回填同知识库、同文档 source 内容，并保留命中 QA、source、问题、答案和 profile provenance；`COMBINED` 对 child source 继续回填 parent 上下文，多个入口回填同一最终块时保留最高分命中。
- [模式切换闭环] — Worker Redis payload 同时兼容 camel/snake case profile 字段；P2 统一 superset 建成后，更新知识库 profile 只切换默认检索入口，不再清理索引或重新入队文档。
- [兼容与默认值] — 旧 `CLASSIC / PARENT_CHILD` 行为保持不变，默认仍为 `CLASSIC`，不会在质量门禁通过前自动切换。
- [验证] — API 聚焦测试通过 97 个，Worker 聚焦测试通过 39 个，Web 聚焦测试通过 7 个，Web 生产构建通过。

### 2026-07-18（V1.5.0 RAG Quality Upgrade P0）
- [Retrieval profile 主干] — 新增 `CLASSIC / PARENT_CHILD / QA_ASSISTED / COMBINED`，知识库持久化默认 profile，请求可临时覆盖；保留原有 `VECTOR / HYBRID / RERANK` 作为检索执行模式。
- [Parent-child 索引] — 摄入消息携带知识库 profile；`PARENT_CHILD / COMBINED` 摄入生成父块和子块，只对子块做 Embedding/Milvus 索引，同时把父块保存到 PostgreSQL。检索命中子块后按 `parent_chunk_id` 回填父块，并保留命中子块溯源元数据；Hybrid BM25 排除父块作为直接入口。
- [多 profile 评估] — RAG eval 一次可运行多个 profile，每个 case/profile 分别保存结果，运行记录保存 profile 集合，评估页展示当前活动物理索引上的 profile 行为对比。
- [最小 Web 设置] — 知识库索引维护区可保存默认 profile；评估页可勾选多 profile 对比。旧请求不传 profile 时仍按 classic 兼容执行。
- [默认启用策略] — 本阶段不修改现有默认逻辑；只有 classic 对比命中率和引用质量不回退后，才允许调整默认 profile。
- [P1 明确保留] — 自动 QA 候选生成、QA chunk/source provenance、QA-assisted 双入口召回仍待实现；当前 `QA_ASSISTED` 仅具备 profile 传递/评估骨架。

### 2026-07-14（V1.2.1 E2E 隔离与清理）
- [受限测试账号删除] — 新增 `DELETE /api/v1/ops/accounts/{username}`；既有 `/ops/**` 的 `OPS_ADMIN` 校验之外，服务层只允许物理删除租户严格为 `e2e`、用户名以小写 `e2e_` 开头的账号。成功操作记录 `ACCOUNT_DELETE_E2E` 审计；账号管理页面不提供通用删除操作。
- [门禁数据隔离] — 浏览器门禁的配置管理员仅创建临时 `e2e_gate_<runId>` 管理员；清理浏览器 Cookie 与 CSRF 本地状态后，以该账号在 `e2e` 租户执行知识库、文档/问答/RAG 评估、审计、账号和角色页面流程。
- [成功清理与失败证据] — 所有断言通过后，门禁通过同一浏览器会话和 CSRF token 删除临时知识库、`e2e_account_<runId>` 与 `e2e_gate_<runId>`，再以配置管理员确认资源不可见；失败路径跳过删除并附加资源标识、租户和页面 URL 的 `e2e-evidence`。
- [验证] — API `./mvnw.cmd verify` 通过 241 个测试且 JaCoCo 门禁满足；Web `npm test` 通过 61 个测试、`npm run build` 通过；真实 Chromium 门禁 `1 passed (4.5s)`，核心流程耗时 `3.7s`，清理断言通过。

### 2026-07-13（V1.2 质量闭环小版本）
- [版本升级] — API 升至 `0.1.2-SNAPSHOT`，Web 升至 `0.1.2`，Flyway 升至 V8。
- [真实浏览器回归扩展] — Playwright 真实登录后选择 `HYBRID` 创建知识库，覆盖文档/问答/RAG 页签、持久化评估用例保存、审计、账号创建/禁用和角色页；拦截控制台、页面、关键请求、错误 Toast 与 `CSRF token required`。
- [上传/摄入/索引详情] — 新增 `GET .../documents/{docId}/index-detail` 与文档查看面板，聚合对象 key/可用性、最近摄入任务诊断、分块总数、最多 20 个分块样例和 `indexReady`。
- [结构化 Chat 错误] — HTTP 与 SSE 错误统一携带 `error/message/stage/suggestion/requestId`，前端按 retrieval/llm/auth/unknown 阶段显示友好提示，并兼容旧纯文本错误。
- [持久化 RAG 评估] — 新增评估用例 CRUD、最近 10 次运行及逐用例不可变结果；空库自动创建内置用例，每库最多 100 条，并以知识库级悲观锁避免初始化/创建竞态；运行状态支持 `RUNNING/COMPLETED/FAILED`，执行要求 `MAINTENANCE + KB_READ`。Web 支持新增/编辑/删除用例、Rerank 开关、运行摘要和诊断明细。
- [P1 检索与运维] — 新建知识库可选 `VECTOR/HYBRID`；评估运行支持 `useRerank`；`POST /ops/audit-alerts/notify` 要求 `OPS_ADMIN + OPS_AUDIT_READ + OPS_ALERT_NOTIFY`，支持可选 `AUDIT_ALERT_WEBHOOK_URL` 和默认 10 秒超时，并记录 `AUDIT_ALERT_NOTIFY` 审计；知识库支持 `schemaVersion=1` 元数据、文档/分块快照和评估用例导出/导入，单次限制 1,000 个文档和 10,000 个分块。
- [启动韧性] — Milvus collection 改为异步加载，保留 schema fail-fast；QueryNode 加载较慢时不再阻塞 API 健康检查，检索期间沿用本地文本 fallback。
- [恢复边界] — V1.2 导入只接受经过校验的 `schemaVersion=1` 请求，通过现有服务创建新知识库并恢复配置/评估用例，不重新上传 MinIO 原始二进制、不恢复文档主记录或直接写回 Milvus；完整灾备仍需三类存储联合备份。
- [验证] — API `./mvnw.cmd verify` 通过 240 个测试，JaCoCo 行覆盖率 95.1913%；Web `npm test` 通过 57 个测试，`npm run build` 通过；Docker V8 迁移成功；真实 Chromium 门禁 `1 passed (3.6s)`，核心流程 `2.7s`。

### 2026-07-12（V1.1 观测与评估小版本）
- [版本升级] — API 升至 `0.1.1-SNAPSHOT`，Web 升至 `0.1.1`。
- [真实浏览器 E2E 门禁] — 新增 `services/web/playwright.config.ts`、`services/web/e2e/browser-gate.spec.ts` 和根脚本 `scripts/e2e-browser-gate.ps1`；门禁使用 `E2E_BASE_URL`、`E2E_ADMIN_USERNAME`、`E2E_ADMIN_PASSWORD`，从真实登录页进入并检查 Cookie/CSRF、知识库详情、`RAG 评估`、审计、账号和角色页面。
- [摄入诊断闭环] — `IngestJobResponse` 增加 `documentFileName`、`documentStatus`、`diagnosis`；诊断由任务/文档状态、阶段、错误、重试次数和更新时间计算，标记严重级别、建议动作、是否可重试和是否停滞。文档表格展示诊断摘要和下一步动作。
- [RAG 评估闭环] — 知识库详情新增 `RAG 评估` 标签，复用 `/retrieve` 运行内置用例并展示 pass/fail、命中数、期望/命中文件、命中 token、检索模式、fallback 原因和 embedding 维度；`scripts/rag-regression-eval.ps1` 的报告新增 `caseResults` 明细。
- [上传治理与运维告警] — `/api/v1/ops/metadata` 返回 `guardrails`（上传限流、摄入队列、摄入补偿、审计阈值和 multipart 最大文件大小）；上传区展示支持格式与可见 guardrail；`/api/v1/ops/audit-alerts` 聚合审计失败峰值、摄入失败/死信任务和向量清理失败任务。

### 2026-07-12（账号/角色管理小版本）
- [数据库账号与角色] — 新增 `roles`、`user_accounts` 持久化模型和 Flyway V6；`DUPI_SECURITY_USERS_*` 改为启动同步来源，登录、token 解析和账号管理优先使用数据库账号与角色。
- [账号管理修复] — 新建账号可直接登录；账号编辑不再修改 `passwordHash`，密码重置改为独立管理员动作并轮换 `tokenVersion`；最后一个可用 `ADMIN` 账号禁止禁用。
- [角色管理] — 新增 `/ops/roles` 页面与角色 API，角色绑定权限点，账号通过 `roleCode` 选择角色，避免逐个账号手工维护权限。
- [下拉元数据] — 新增 `/api/v1/ops/metadata`，审计日志动作/目标类型/状态筛选、账号角色选择和权限选择改为下拉数据源，降低输入错误；权限元数据同步返回每个权限点的名称、用途、允许动作和不允许动作。
- [权限说明提示] — `/ops/roles` 权限选择区和角色列表、`/ops/accounts` 角色权限列表统一使用权限说明浮层，鼠标悬停或键盘聚焦权限点时展示“允许/不允许”的边界说明。
- [本地开放模式收口] — 鉴权过滤器改为运行时检查 `user_accounts`；一旦数据库已有账号，即使未配置 API Key/配置账号，也不会继续免登录开放公共 API。
- [CSRF 状态修复] — 前端移除“本地开放模式继续”入口；所有 POST/PATCH/DELETE/上传请求在缺少 CSRF token 时本地拦截并提示重新登录，避免页面伪登录后由后端报 `CSRF token required`。
- [Chat 500 修复] — 回答前的 Embedding 服务网络/DNS 故障会降级到本地 chunk 文本检索，不再在 `llmClient.embed()` 阶段直接中断为 HTTP 500；本地兜底检索同时补强 `venv是什么` 这类英文术语 + 中文问题的关键词拆分。
- [Chat Markdown 可读性修复] — 增强 `normalizeMarkdown` 对问答历史和新回答中的坏 Markdown 兜底：修复空列表项、半截行内代码、`py -3.12 -m venv .venv` 被拆成编号列表、`1. 4venv的优缺点` / `## 3.4 venv 的优缺点` 等片段编号污染标题；`ChatService` prompt 同步约束标准 Markdown 输出。
- [验证] — 账号/RBAC 聚焦测试 `.\mvnw.cmd "-Dtest=ConfigAndExceptionTest,AccountServiceTest,ApiTokenServiceTest,ControllerLayerTest" test` 通过 48 个测试；权限说明聚焦测试 `.\mvnw.cmd -Dtest=ControllerLayerTest test` 通过 7 个测试，`npm test -- PermissionTokenList.test.tsx` 通过 1 个测试；Web `npm test` 通过 40 个测试，`npm run build` 通过；本地接口实测管理员登录、新建临时账号、临时账号登录、禁用清理均成功；Chat/Retrieval 聚焦测试 `.\mvnw.cmd "-Dtest=RetrievalServiceTest,ChatServiceTest" test` 通过 28 个测试，本地真实 Chat 请求返回 200；Markdown 聚焦测试 `npm test -- src/lib/normalizeMarkdown.test.ts` 通过 11 个测试，Web 生产 bundle 已更新到 `assets/index-Dbp8OEsZ.js` 并覆盖部署。

### 2026-07-11（P1 体验与启动安全收口）
- [Chat 引用渲染] — 引用来源卡片的 snippet 改用 `MarkdownContent compact` 渲染，支持加粗、列表等 Markdown 摘要；新增 `MarkdownContent.test.tsx` 覆盖 citation compact 渲染。
- [Chat 会话与问答韧性] — 会话列表过滤尚未持久化消息的空会话，避免点击历史会话后详情为空；Milvus `Timestamp lag too large` / QueryNode transient search failure 会走本地 chunk 文本兜底，不再直接导致 Chat 500；前端对空 SSE/API error 统一显示“问答请求失败，请稍后重试”，避免只显示“错误：”。
- [Worker 索引韧性] — Worker 在重建文档索引前清理旧向量时，若 Milvus delete 返回 `Timestamp lag too large` / `failed to search/query delegator`，会按临时一致性滞后处理并继续写入新 chunks，避免批量上传后的索引维护任务全部失败。
- [上传限流分桶] — `UploadRateLimitFilter` 从 IP + API Key 分桶升级为租户感知：已认证用户按 `tenant + principal` 分桶，匿名/兼容 API Key 请求按 `tenant + IP + API Key` 分桶，减少同 IP 多账号互相挤占。
- [Milvus 维度 fail-fast] — API 启动加载已存在 Milvus collection 前会读取 schema，校验 `embedding` 字段类型与 `EMBEDDING_DIMENSION` 一致；维度不匹配时直接失败并提示重置 collection 或切换维度专用 collection，避免后续摄入/检索才暴露 400 或空命中。
- [文档同步] — README 新增 Docker 启动排障章节；`todo.md`、`architecture.md` 与本文同步本批 P1 完成项。
- [验证] — API 聚焦测试 `.\mvnw.cmd "-Dtest=ConfigAndExceptionTest,MilvusVectorServiceTest" test` 通过 40 个测试；Chat/Retrieval 聚焦测试 `.\mvnw.cmd "-Dtest=RetrievalServiceTest,ChatSessionServiceTest,ChatServiceTest" test` 通过 40 个测试；Worker 测试 `python -m pytest services/worker/tests` 通过 38 个测试；Web 聚焦测试 `npm test -- MarkdownContent` 通过 1 个测试，`npm run test -- src/api/chat.test.ts` 通过 12 个测试，`npm run build` 通过。

### 2026-07-07（Maven 3.9.9 与账号/RBAC 管理闭环）
- [构建基线] — API 新增 Maven Wrapper，`services/api/.mvn/wrapper/maven-wrapper.properties` 固定 Apache Maven 3.9.9；API Dockerfile 构建镜像同步固定为 `maven:3.9.9-eclipse-temurin-17`。
- [账号管理 API] — `/api/v1/ops/accounts` 从只读扩展为可创建、更新、禁用/启用、轮换 `tokenVersion` 和生成 PBKDF2 密码哈希；账号禁用后登录和旧 token 解析都会被拒绝。
- [账号管理 UI] — `/ops/accounts` 支持新建账号、编辑租户/角色/权限/知识库范围、禁用/启用账号、轮换 tokenVersion 与生成密码哈希。
- [验证] — Maven Wrapper `.\mvnw.cmd -version` 确认 3.9.9；账号/RBAC 聚焦测试 `mvn "-Dtest=AccountServiceTest,ApiTokenServiceTest,ControllerLayerTest" test` 通过 23 个测试；API `.\mvnw.cmd verify` 通过 184 个测试，JaCoCo 行覆盖率 95.3229%；Web `npm test` 通过 36 个测试，`npm run build` 通过；Worker `python -m pytest` 通过 37 个测试。

### 2026-07-07（Outbox、Tombstone、实例级授权与 Ops 运维增强）
- [Transactional outbox] — 上传、摄入重试和 reindex 改为事务内写 `ingest_outbox_events`，由 `IngestOutboxService` 在事务提交后定时投递 Redis；投递失败进入退避重试，避免数据库成功但队列消息丢失。
- [删除 tombstone] — 文档删除前记录 `document_tombstones`；outbox dispatcher 对已删除文档取消投递，Worker 迟到状态回调会被忽略，避免已删文档被恢复 chunks 或完成状态。
- [实例级授权] — 文档、会话和向量清理任务等实例级入口会解析归属 kbId 后再校验账号 `knowledgeBaseIds`，任务列表和 retry 只暴露当前主体可访问资源。
- [账号管理 UI] — 新增 `/ops/accounts` 页面和 `GET /api/v1/ops/accounts` 接口，只展示内置账号脱敏元数据：用户名、租户、角色、权限、知识库范围、tokenVersion 与凭证配置状态。
- [审计运维增强] — `/ops/audit-logs` 增加告警摘要和 CSV 导出；后端新增 `/api/v1/ops/audit-logs/export`、`/api/v1/ops/audit-alerts`、审计保留清理和失败峰值告警阈值配置。
- [文档同步] — 新增 [`docs/outbox-tombstone-rbac-ops-2026-07-07.md`](../outbox-tombstone-rbac-ops-2026-07-07.md)，并同步 `README.md`、`architecture.md`、`decisions.md`、`todo.md`、`.env.example` 与 `application.yml`。

### 2026-07-06（安全加固与部署收口）
- [API 门禁] — 新增可选共享密钥鉴权：公开 API 使用 `X-Dupi-API-Key`，internal API 使用 `X-Dupi-Internal-Key`；密钥为空时保持本地开发兼容
  - [`ApiKeyAuthFilter`](../../services/api/src/main/java/com/dupi/rag/config/ApiKeyAuthFilter.java) 覆盖公开/internal/actuator/OPTIONS 路径
  - [`application.yml`](../../services/api/src/main/resources/application.yml) 新增 `DUPI_API_KEY` / `DUPI_INTERNAL_KEY`
  - Worker 回调与混合检索语料拉取会在配置存在时携带 internal key
- [端口收口] — 默认 Docker Compose 只暴露 Web `8080`；API、Worker、PostgreSQL、Redis、Milvus、MinIO 均改为内部网络访问
- [P0 配置安全] — PostgreSQL/MinIO 默认弱口令回退改为必填；新增 `scripts/compose-config-redacted.ps1`，后续检查 Compose 展开配置必须走脱敏输出；已提示真实 DeepSeek/智谱 Key 需要人工轮换
- [P1 上传保护] — 上传接口新增按客户端 IP + API Key 分桶的限流，默认 20 次/60 秒，可通过 `UPLOAD_RATE_LIMIT_*` 调整
- [P1 队列削峰] — 上传落 MinIO 前检查 Redis 摄入队列高水位，默认 `INGEST_QUEUE_MAX_PENDING_JOBS=200`，队列满时快速拒绝
- [P1 摄入补偿] — Redis 入队失败时文档/任务保持 `PENDING + QUEUED`，`IngestJobService` 按 `INGEST_RECOVERY_CRON` 定时重新投递
- [P1 批量上传结果] — `documents/batch` 改为返回逐文件结果，单个空文件或失败文件不再拖垮同批次成功文件
- [P1 删除韧性] — 文档/知识库删除前登记 `vector_cleanup_tasks`，Milvus 清理失败不再阻断数据库记录删除，定时任务后续补偿清理残留向量
- [P1 问答诊断] — `/retrieve` 与 Chat SSE 返回检索诊断；前端展示命中数、检索模式、fallback 原因，并在旧 KB embedding 配置不一致时提示重建/迁移
- [P0 租户隔离] — 公共 API 通过 `X-Dupi-Tenant-Id` 建立请求级租户上下文，知识库根边界按租户过滤；internal/worker/定时任务保留系统级查询
- [P1 旧库 reindex] — 新增 `POST /api/v1/knowledge-bases/{kbId}/reindex`，更新旧 KB embedding 配置、清理 chunks、登记向量补偿并重新入队文档
- [P1 摄入死信] — `INGEST_RECOVERY_MAX_ATTEMPTS` 控制补偿最大自动重试次数，连续失败后任务进入 `DEAD_LETTER`，文档标记 `FAILED`
- [P1 索引维护 UI] — 文档页新增索引维护面板，可触发 reindex、查看最近摄入任务，并对 `FAILED` / `DEAD_LETTER` 任务手动重试
- [P1 审计日志] — 新增 `audit_logs`，覆盖知识库/文档删除、会话批量删除、reindex、摄入任务 retry、向量清理任务 retry
- [P1 向量清理运维] — 新增 `/api/v1/ops/vector-cleanup-tasks` 列表与 retry 接口，文档页索引维护面板可查看和重试清理任务
- [P1 RAG 回归评测] — 新增 `examples/rag-eval-cases.json` 与 `scripts/rag-regression-eval.ps1`，按检索命中、引用文件和关键 token 做稳定回归
- [P1 Web 维护流程 E2E] — 新增 `scripts/e2e-web-maintenance-flow.ps1`，覆盖批量上传、reindex、摄入任务重试入口、向量清理任务入口
- [P1 Worker Embedding 分批] — Worker `embed_batch` 支持 `EMBEDDING_BATCH_SIZE`，默认每批 32 条文本，按供应商限制拆分大文档 Embedding 请求
- [账号/RBAC] — 登录改为 `DUPI_AUTH` HttpOnly Cookie + `DUPI_CSRF` 双提交 CSRF，Bearer/API Key 仍保留脚本兼容路径
- [登录锁定] — 连续登录失败计数迁移到 Redis-backed `LoginFailureStore`，多副本共享锁定窗口，测试构造保留内存 fallback
- [资源级授权] — 内置账号新增 `knowledgeBaseIds` 范围，知识库、文档、检索、聊天和维护入口在权限点后继续校验 kbId
- [Ops 审计查询] — 新增 `GET /api/v1/ops/audit-logs` 和前端 `/ops/audit-logs` 页面，支持租户、动作、目标类型、状态、limit 筛选
- [测试覆盖率] — 后端新增 Milvus、摄入补偿、审计/清理实体、Cookie/CSRF、Redis 登录锁定、资源级授权、会话引用快照、上传限流边界测试，`mvn verify` 通过 158 个测试，JaCoCo 行覆盖率 95.45%
- [文档同步] — 更新 `README.md`、`architecture.md`、`decisions.md`、`rbac-ops-admin-2026-07-06.md`、`todo.md` 与 `.env.example`，修正认证、审计查询和资源级授权过期记录

### 2026-06-21（文档管理：批次上传与删除）
- [批次上传] — 文档管理页支持多选/拖拽多个文件，调用后端批量上传 API
  - [`useDropzone.ts`](../../services/web/src/hooks/useDropzone.ts) 新增 `multiple` 参数
  - [`UploadZone.tsx`](../../services/web/src/components/UploadZone.tsx) 多文件上传与进度文案（`上传中 2/5：foo.pdf`）
  - [`KbDetailPage.tsx`](../../services/web/src/pages/KbDetailPage.tsx) 消费后端逐文件结果，汇总 toast（成功 N 个 / 失败 M 个）
- [文档删除] — 对接已有 `DELETE .../documents/{docId}` API
  - [`documents.ts`](../../services/web/src/api/documents.ts) 新增 `deleteDocument`
  - [`DocTable.tsx`](../../services/web/src/components/DocTable.tsx) 操作列 + 删除按钮（确认对话框，删除中 loading）
  - 后端同步登记残留向量补偿清理任务（Milvus + chunks + MinIO + DB 仍在 `DocumentService.delete` 清理）
- [验证] — `tsc -b` 通过；本地 Compose 未运行时未做浏览器 E2E

### 2026-06-20（Canonical Markdown 摄入）
- [摄入统一 MD] — 各格式上传后先经 `canonicalize` 转为规范 Markdown，再按标题/代码块/表格边界分块，替代纯 token 切断
  - **新增** [`services/worker/app/canonical/`](../../services/worker/app/canonical)：`md_sanitizer`、`text_to_md`、`docx_to_md`、`xlsx_to_md`、`pdf_to_md`（pymupdf4llm）、`to_markdown`
  - **新增** [`markdown_chunker.py`](../../services/worker/app/chunker/markdown_chunker.py)：section 拆分、`heading`/`block_type` 元数据、表格/代码块原子切分
  - **接入** [`consumer.py`](../../services/worker/app/consumer.py)：canonicalize → markdown chunk；`recursive` 策略亦走 markdown chunker
  - **API** [`RetrievalService.buildContext`](../../services/api/src/main/java/com/dupi/rag/service/RetrievalService.java) 带 `section`/`type`；[`ChatService`](../../services/api/src/main/java/com/dupi/rag/service/ChatService.java) prompt 要求保持上下文 MD 结构
  - **枚举** `ChunkStrategy.MARKDOWN`；`pymupdf` 升至 `>=1.24.10` 以兼容 `pymupdf4llm`
- [验证] — E2E 步骤 1–6 PASS（上传 sample-knowledge.md 摄入 COMPLETED）；检索返回 chunk 含 `heading`、`block_type`、`format=canonical_md`
- [注意] — **已摄入文档需重新上传** 才能享受新分块；E2E 步骤 7 因 PowerShell JSON 编码解析失败（非 API 错误）

### 2026-06-20（Web 问答 Markdown 渲染与自动换行）
- [Chat Markdown] — 智能问答助手回复原先以纯文本 `whitespace-pre-wrap` 展示，LLM 返回的 `##`、代码块、列表等 Markdown 语法无法解析
  - **修复（第一版）**：新增 [`MarkdownContent.tsx`](../../services/web/src/components/MarkdownContent.tsx)，使用 `react-markdown` + `remark-gfm` + `@tailwindcss/typography`；[`ChatPanel.tsx`](../../services/web/src/components/ChatPanel.tsx) 助手消息改用 Markdown 渲染
- [自动换行] — 首版仍有长文本不换行、单行挤在一起的问题；根因：气泡 `overflow-x-auto`、`prose max-w-none`、flex 子项缺 `min-w-0`、标准 Markdown 单换行不渲染
  - **修复（第二版）**：气泡与容器加 `min-w-0` / `break-words` / `overflow-wrap:anywhere`；移除气泡级横向滚动；`pre`/行内 `code` 支持 `whitespace-pre-wrap`；新增 `remark-breaks` 将单 `\n` 转为 `<br>`
- [Markdown 格式] — LLM 常输出 `##标题`、`1.步骤2.步骤` 等非标准 Markdown，前端无法解析为标题/列表
  - **修复（第三版）**：[`ChatService`](../../services/api/src/main/java/com/dupi/rag/service/ChatService.java) system prompt 约束标准 Markdown 输出；新增 [`normalizeMarkdown.ts`](../../services/web/src/lib/normalizeMarkdown.ts) 在渲染前补空格与换行
- [序号/样式] — 列表从上下文片段编号（如 5、6）起跳、`5. 1本地开发` 嵌套序号、代码块与命令粘连、`#`/`---#` 残留、标题字号过大
  - **修复（第四版）**：增强 normalize（重编号、片段序号转 `##` 标题、修复 code fence、去残留符号、命令拆词）；prompt 禁止用片段编号作列表序号；[`MarkdownContent`](../../services/web/src/components/MarkdownContent.tsx) 统一标题字号、限制过长加粗
- [表格/树/链路] — 架构类回答中表格挤成一行（`||` 连接）、目录树 `├──` 不换行、`[1]#` 残留、部署箭头链路难读
  - **修复（第五版）**：normalize 新增表格行拆分、目录树/箭头流转 `text` 代码块、粘连小节标题拆分、未闭合反引号修复、`•` 转列表；prompt 要求架构分节、表格逐行、树与链路用代码块
- [部署] — `docker compose -f deploy/docker-compose.yml up -d --build web` 重建通过

### 2026-06-19（Web 排障、智谱 Embedding、E2E 全绿）
- [CORS 403] — `:8080` 页面「新建知识库」「上传文档」POST 返回 403 `Invalid CORS request`；原因：`CorsConfig` 仅允许 `localhost:5173`，浏览器 `fetch` POST 带 `Origin: http://localhost:8080` 被拒
  - **修复**：[`CorsConfig.java`](../../services/api/src/main/java/com/dupi/rag/config/CorsConfig.java) 改为 `allowedOriginPatterns`：`http://localhost:*`、`http://127.0.0.1:*`
- [智谱 Embedding] — `deploy/.env` 配置 `EMBEDDING_BASE_URL=https://open.bigmodel.cn/api/paas/v4`、`embedding-2`、维度 `1024`；[`KnowledgeBaseService`](../../services/api/src/main/java/com/dupi/rag/service/KnowledgeBaseService.java) 新建 KB 时从 `LlmProperties` 读取默认 `embeddingModel`/`embeddingDimension`
- [摄入 400] — 旧知识库元数据仍为 `text-embedding-3-small`/1536，Worker 将其发给智谱导致 400；**规避**：新建知识库后上传，或删除旧库；切换维度时需重置 Milvus `dupi_chunks` 集合
- [Chat SSE] — DeepSeek 流式无 `event:token`；**修复**：[`LlmClient.chatStream`](../../services/api/src/main/java/com/dupi/rag/client/LlmClient.java) 使用 `bodyToMono` + SSE 行解析
- [E2E 全绿] — 8 步全部 PASS（智谱摄入 + 检索 + Chat SSE）；报告 [`scripts/e2e-last-run.json`](../../scripts/e2e-last-run.json)
- [文档] — 本批更新 `progress.md`、`todo.md`、`decisions.md`、`e2e-testing.md`、`architecture.md`

### 2026-06-19（E2E 主流程自动化）
- [E2E 脚本] — 新增 [`scripts/e2e-main-flow.ps1`](../../scripts/e2e-main-flow.ps1)，8 步对应 Web 按钮：健康 → 列表 → 建库 → 详情 → 上传 → 轮询摄入 → 检索 → Chat SSE；报告输出 `scripts/e2e-last-run.json`
- [上传缺陷修复] — `DocumentService.upload()` 预置 UUID 导致 `@PrePersist` 未触发、`created_at` 为 null；builder 显式设置时间戳并预计算 `objectKey`；步骤 5 已通过
- [E2E 运行] — 初跑步骤 1–5 PASS；步骤 6 Embedding 401；后续配置智谱并修复 SSE 后 **8/8 PASS**
- [文档] — 新增 [`docs/e2e-testing.md`](../e2e-testing.md)；更新 `README.md`、`architecture.md`

### 2026-06-19（Docker 启动排障）
- [Docker 镜像拉取失败] — 直连 `registry-1.docker.io` 超时（`redis:7-alpine` 等）
  - **解决**：在 `C:\Users\Wxw\.docker\daemon.json` 配置 `registry-mirrors`（`https://docker.1ms.run`），并设置 `ipv6: false`；`docker desktop restart` 后预拉取 5 个基础镜像
- [镜像站 429] — `docker.xuanyuan.me` 返回 `429 Too Many Requests`，`eclipse-temurin:17-jre` 标签解析失败
  - **解决**：移除不稳定镜像站，仅保留 `docker.1ms.run`；API Dockerfile 改用 `eclipse-temurin:17-jre-jammy`；预拉取 `maven` / `eclipse-temurin` / `python` 基础镜像
- [Worker pip 超时] — 构建时 `pip install` 从 `files.pythonhosted.org` 下载超时
  - **解决**：[`services/worker/Dockerfile`](../../services/worker/Dockerfile) 使用清华 PyPI 镜像 + `--default-timeout=300`
- [Worker 启动崩溃] — `marshmallow` 4.x 与 `pymilvus`/`environs` 不兼容（`__version_info__` 缺失）
  - **解决**：[`services/worker/requirements.txt`](../../services/worker/requirements.txt) 锁定 `marshmallow>=3.13.0,<4.0.0` 后重建 worker
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
# 2026-07-15 V1.3 发布硬化

- 已新增 30 条、六分类检索清单，支持幂等同步、冷/热三模式基准和 fallback/排名证据门禁。
- 已新增 Rerank 启动预热、脱敏健康状态和 `hf_model_cache` 持久卷；默认模型保持 `BAAI/bge-reranker-base`。
- 已新增 Milvus 2.4.1 到 2.5.4 备份/恢复演练脚本及生产确认、备份先行、校验清单策略测试。
- 已新增 pip-audit、Syft、Trivy、许可证 deny list 和 3 GB Worker 镜像门禁脚本。
- 已新增 Sparse Migration Web 运维面板、Cutover 证据对话框和浏览器 Gate 流程。
- 已通过新增的 Worker、API DTO、Web 组件、Pester 策略测试和 Web 构建；生产同规格演练、真实环境 30 Case 基准、完整扫描和真实浏览器 Gate 待发布环境执行。
