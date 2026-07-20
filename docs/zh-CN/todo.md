# 待办清单

<!-- language-switch -->
[English](../en/todo.md)





V1.5.0版本关闭包

- [x]将API， Web和lockfile发布元数据调整为1.5.0。
- [x] V18-V20版本文档升级、配置文件V2 rollout、质检关、监控、回退。
- [x]通过API， Worker, Web, Pester, Compose， diff和release-scan闸门在最终的发布diff。

V1.4.2治理操作稳定性

- [x]增加OPS_ADMIN操作符的只读权限GET /api/v1/ops/governance-summary。
- [x]汇总上传配额、摄取作业、摄取发件箱、故障通知、矢量清理和现有警报。
- [x]添加烟雾脚本scripts/smoke-governance-summary。ps1和Pester测试覆盖率。
- [x]忽略local。npm-cache工件。
- [x]记录节点16本地Web验证约束：使用npm脚本所以services/ Web /scripts/node16-webcrypto。cj加载;不要运行未加工的葡萄或葡萄。
- [ ] 在任何合并，标记或发布声明之前运行完整的V1.4.2 gate。

V1.4.1上传治理

- [x]保留租户/用户保留的字节、文档和滚动窗口上传配额。
- [x]增加每个文件的幂等保留，重播，冲突，配额和有效负载大小响应。
- [x]增加执行id、索赔/租赁状态、取消、回调序列保护和终端不可变性。
通过处理列表移动Worker消耗，并添加取消清理检查点。
- [x]保留重复数据删除失败/DEAD_LETTER通知事件与webhook调度/重试/耗尽，不通知取消。
- [x]将Web批处理工作流替换为有边界的每个文件上传、配额、中止/重试、摄取取消和序列化轮询。
- [x]对齐API/Web版本并同步发布文档。
- [x]通过完整的API/Worker/Web/Pester/Compose/release-scan gate，然后创建本地V1.4.1发布提交和标签。

V1.3版本结束

V1.4.0可验证恢复

- [x]保存存档/还原状态并隐藏“还原”目标。
- [x]捕获和验证记录，对象，密集向量，稀疏向量和密封清单。
- [x]用重试和放弃恢复到一个隐藏目标。
- [x]增加`KB_RECOVERY`，审计事件，有界执行，范围API和恢复UI。
- [x]添加纠缠策略门和确定性恢复浏览器流。
- [x]保留一个真正的'工件/v1.4-恢复/排练。Json '具有两个固定装置，9项/ 10,946字节，对象/矢量校验和，恢复的记录计数，检索等价，损坏阻塞和范围清理。
- [x]运行凭据剧作家门对整个撰写堆栈（' 1 passed '）。
- [x]在V1.4.0关闭前注入经典对象损坏并保留一个带有‘ corruptionBlocked=true ’的排练工件
- [x]通过非根CPU Worker映像和过期结构化异常的dependency/SBOM/license/CVE/image-size扫描。
- [x]在V1.4.0关闭后启动V1.4.1上传配额、取消和通知。

- [x] Milvus Sparse 回填、双写、Shadow、Cutover、Rollback 和删除同步。
- [x] 质量策略、baseline 和 Retrieval Profile 控制台。
- [x] 真实 Milvus 2.5.4 迁移演练和真实浏览器 E2E。
- [x] CPU reranker 镜像和带非空 `rerankRank` 的最终 benchmark artifact。
- [x] API JaCoCo 行覆盖率达到项目 95% 门禁，未降低阈值。
- [x] 在同一最终 diff 上重跑 API/Worker/Web/build/benchmark/E2E 全部门禁。

## 进行中

（无）

## 待办

- [ ] 生产级鉴权增强：SSO/OIDC、外部身份源同步、密钥轮换审批流
- [x] 上传保护升级：按租户/用户配额、上传取消与告警
- [ ] 运维面板增强：任务高级筛选、邮件/IM 等更多通知渠道与审计归档对接
- [ ] 完整灾备恢复：MinIO 原始二进制、文档主记录与 Milvus 向量的一致性导入/校验
- [ ] Milvus BM25 sparse 字段生产调优与索引参数压测
- [ ] 可视化 Knowledge Pipeline DSL（V3）
- [ ] K8s Helm Chart（V4）

## 已完成

- [x] V1.5 P2：配置文件v2可过滤超集，组合加权RRF，修订绑定默认质量门，Web准备/门比较——2026-07-19

- [x] V1.5 P0/P1：Parent-Child / QA-assisted 索引、四种 retrieval profile、知识库设置、多 profile RAG 评估、QA source provenance 与失败降级 — 2026-07-19
- [x] V1.2.1：真实浏览器门禁数据隔离到 `e2e` 租户；成功自动清理临时知识库和 `e2e_*` 账号，失败保留 Playwright 证据；新增仅 `OPS_ADMIN` 可调用且严格限于 `e2e` 租户 `e2e_*` 账号的删除接口，账号管理页面不提供通用删除 — 2026-07-14
- [x] V1.2-P0-1：扩展真实浏览器门禁，覆盖混合检索建库、持久化 RAG 用例、账号真实创建/禁用与 CSRF/控制台/网络错误拦截 — 2026-07-13
- [x] V1.2-P0-2：单文档上传/摄入/索引详情 API 与 Web 面板，展示对象、最近任务诊断、分块样例和索引状态 — 2026-07-13
- [x] V1.2-P0-3：Chat HTTP/SSE 结构化错误与前端阶段化友好提示 — 2026-07-13
- [x] V1.2-P0-4：RAG 评估用例持久化 CRUD、运行历史、逐用例诊断与 Web 管理面板 — 2026-07-13
- [x] V1.2-P1：知识库 `VECTOR/HYBRID` 选择、评估 Rerank、审计告警 Webhook、知识库元数据/分块快照导出恢复 — 2026-07-13
- [x] V1.2 启动韧性：Milvus collection 异步加载，避免 QueryNode 加载等待阻塞 API 启动和 Web 健康检查 — 2026-07-13
- [x] V1.2 发布加固：Flyway V8 运行状态、评估并发锁和 100 用例上限、索引/导出边界、版本化导入、Webhook 独立权限与超时配置 — 2026-07-13
- [x] V1.2 质量门禁：API 240 测试与 JaCoCo 95.1913%，Web 57 测试和生产构建，真实 Chromium `1 passed (3.6s)` — 2026-07-14

- [x] Chat HTTP 500 修复：Embedding 服务网络/DNS 故障时降级本地文本检索，并补强中英混合问题关键词拆分 — 2026-07-12
- [x] Chat Markdown 可读性修复：修复问答结果/历史消息中的空列表项、拆裂行内代码、Python 版本号误编号、片段编号污染标题等坏 Markdown 渲染问题 — 2026-07-12
- [x] 账号新建 CSRF 修复：移除前端本地跳过登录入口，写操作缺少 CSRF token 时本地拦截并提示重新登录 — 2026-07-12
- [x] 角色/账号权限说明：`/api/v1/ops/metadata` 返回权限名称、用途、允许动作和不允许动作，角色/账号页面支持权限悬停说明浮层 — 2026-07-12
- [x] V1.1：真实浏览器 E2E 门禁脚本，使用真实登录、Cookie 和 CSRF 检查核心页面 — 2026-07-12
- [x] V1.1：摄入/索引诊断闭环，摄入任务响应和文档表格展示诊断摘要、建议动作、可重试和停滞状态 — 2026-07-12
- [x] V1.1：知识库详情 `RAG 评估` 标签与 `/retrieve` 评估器，展示命中质量和检索诊断 — 2026-07-12
- [x] V1.1：RAG 回归报告 `caseResults` 明细，记录每条用例的 query、pass/fail、命中、引用和 embedding 信息 — 2026-07-12
- [x] V1.1：上传治理可视化与聚合运维告警，展示 guardrails 并聚合审计、摄入和向量清理告警 — 2026-07-12
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
- [x] 账号/角色管理小版本：数据库账号与角色、账号密码重置、账号角色下拉、角色管理页、审计筛选元数据下拉、本地开放模式持久账号收口 — 2026-07-12
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
# V1.3 正式发布前

- [x] 30 条、六分类 RAG 基准清单与发布门禁自动化 — 2026-07-15
- [x] Rerank 启动预热、持久化模型缓存和健康状态 — 2026-07-15
- [x] PostgreSQL、etcd、MinIO、Milvus 备份/升级/Cutover/Rollback 演练脚本 — 2026-07-15
- [x] PyTorch、sentence-transformers 和 Worker 镜像的 CVE/许可证/体积扫描门禁 — 2026-07-15
- [x] Sparse Migration Web 运维页面与受保护 Cutover — 2026-07-15
- [ ] 在生产同规格环境执行 30 Case 基准、完整供应链扫描和升级/回滚演练，并归档脱敏报告
- [ ] 基于真实业务查询将基准从 30 条扩展到 100 条
