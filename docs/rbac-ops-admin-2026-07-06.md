# 账号 / RBAC 与 ops 管理权限记录（2026-07-06）

## 背景

本轮处理项目安全薄弱点中的账号、RBAC 与运维管理权限。此前项目已有公共 `X-Dupi-API-Key` 与 internal `X-Dupi-Internal-Key`，但共享 API Key 不能区分普通用户与管理员；`/api/v1/ops/**`、删除、重建索引、摄入重试等高风险入口缺少更细的权限边界；同时租户上下文可由客户端请求头传入，存在被伪造或覆盖的风险。

## 已完成改动

- 新增数据库账号与角色表：`roles` 保存角色绑定的权限点，`user_accounts` 保存账号、密码哈希、租户、角色、知识库范围和 token 版本；`DUPI_SECURITY_USERS_*` 仅作为启动同步/首次引导来源。
- 新增登录接口：`POST /api/v1/auth/login`，成功后写入 `DUPI_AUTH` HttpOnly Cookie，并返回用户名、租户、角色、过期时间和前端需要保存的 `csrfToken`。
- 新增 HMAC-SHA256 token 签发与解析：token payload 包含 `sub`、`tenantId`、`role`、`permissions`、`knowledgeBaseIds`、`ver`、`iat`、`exp`。
- 新增账号安全生产化能力：支持 PBKDF2 密码哈希、Redis 登录失败锁定、tokenVersion 强制失效旧 token。
- 新增请求级安全上下文：`SecurityContext` 保存主体、角色、权限点和可选知识库范围，`TenantContext` 保存租户。
- 调整认证优先级：浏览器优先使用 Cookie 会话，脚本/API 客户端可继续使用 Bearer token，最后回退兼容 `X-Dupi-API-Key`。
- 增加细粒度权限边界：ops、删除、重建索引、摄入重试、上传、问答、检索等入口按权限点判断。
- 增加知识库资源级授权：账号可通过 `knowledgeBaseIds` 限定只能访问指定知识库，`ADMIN`、`*` 或空范围保持全量访问兼容。
- 增加 Cookie 会话 CSRF 防护：Cookie 登录后的 mutating 请求必须同时携带 `DUPI_CSRF` Cookie 与 `X-Dupi-CSRF-Token` 请求头；Bearer/API Key 兼容路径不要求 CSRF。
- 增加审计日志查询：`GET /api/v1/ops/audit-logs` 支持按租户、动作、目标类型、状态和 limit 筛选，前端新增 `/ops/audit-logs` 查询页；2026-07-07 继续补充 CSV 导出、保留清理和失败告警摘要。
- 增加账号与角色管理页：`/ops/accounts` 管理数据库账号，账号通过 `roleCode` 选择角色，不再直接编辑权限点；`/ops/roles` 管理角色与权限点绑定；账号密码重置独立为管理员动作并轮换 `tokenVersion`，页面与接口不暴露 passwordHash。
- 保持 internal 边界独立：`/api/v1/internal/**` 继续只校验 `X-Dupi-Internal-Key`，不接受用户 Bearer token 替代。
- 前端支持登录页、退出登录、Cookie 会话状态识别，本地仅保存 CSRF token；普通 API、上传、SSE 问答和取消问答请求统一带 `credentials: include`，必要时携带 `X-Dupi-CSRF-Token`。
- `deploy/.env.example` 与 `application.yml` 已同步账号安全和权限相关配置项。

## 权限模型

| 权限点 | 作用范围 | 典型路由 |
|------|------|------|
| `*` | 管理员通配权限 | `ADMIN` 角色默认拥有 |
| `OPS_ADMIN` | 运维管理入口 | `/api/v1/ops/**` |
| `KB_READ` | 知识库读取、检索 | `GET /api/v1/knowledge-bases/**`、`POST .../retrieve` |
| `KB_WRITE` | 创建知识库 | `POST /api/v1/knowledge-bases` |
| `KB_DELETE` | 删除知识库 | `DELETE /api/v1/knowledge-bases/{kbId}` |
| `DOCUMENT_UPLOAD` | 上传文档 | `POST .../documents`、`POST .../documents/batch` |
| `DOCUMENT_DELETE` | 删除文档 | `DELETE .../documents/{docId}` |
| `CHAT_WRITE` | 发起知识库问答 | `POST .../chat` |
| `CHAT_DELETE` | 删除会话 | `DELETE .../chat-sessions/**` |
| `MAINTENANCE` | 维护动作 | `POST .../reindex`、`POST .../ingest-jobs/{jobId}/retry` |

`ADMIN` 角色在 `SecurityContext` 中会映射为 `*`，兼容 API Key 也继续按管理员等价处理。数据库默认种子角色包含 `ADMIN`、`OPERATOR`、`ANALYST`、`VIEWER`；账号通过 `roleCode` 继承角色上的权限点。配置项中的 `DUPI_SECURITY_USERS_*` 仍可用于首次引导账号，启动时会同步到 `user_accounts`，但后续管理以数据库账号和角色为准。如果配置 `knowledgeBaseIds`，则该账号只能访问列表内知识库；未配置或配置 `*` 时保持全量知识库访问兼容。

## 关键安全决策

### Cookie 会话优先，Bearer/API Key 保持兼容

浏览器登录后不再把 token 暴露给 JavaScript，而是由服务端写入 `DUPI_AUTH` HttpOnly Cookie。脚本和自动化客户端仍可显式传入 `Authorization: Bearer ...`，Web Nginx 仍会为兼容旧部署向后端注入 `X-Dupi-API-Key`。服务端优先使用用户级 token/Cookie 中的用户、角色、权限和租户，只有请求没有用户会话时才回退到兼容 API Key。

### CSRF 只约束 Cookie 会话

Cookie 登录会同时写入可读的 `DUPI_CSRF` Cookie，并在登录响应返回 `csrfToken`。前端对上传、删除、reindex、retry、聊天等 mutating 请求携带 `X-Dupi-CSRF-Token`。服务端只在 Cookie auth 场景执行双提交校验，Bearer token 与 API Key 路径保持脚本调用兼容。

### token 租户优先于请求头租户

Bearer token 绑定 `tenantId` 后，`TenantContextFilter` 不再使用客户端传入的 `X-Dupi-Tenant-Id` 覆盖租户上下文。这样可以避免登录用户伪造租户头访问其他租户资源。

### internal API 不接受用户 token

Worker 回调、internal chunks 拉取等系统链路继续只走 `X-Dupi-Internal-Key`，不与用户 Bearer token 混用，减少权限边界交叉。

### tokenVersion 用于强制登出

Bearer token 中写入账号当前 `tokenVersion`。管理员重置密码、轮换 tokenVersion 或禁用账号后，旧 token 解析时会因版本不匹配或账号状态被拒绝，可用于密码轮换、账号泄露后的强制失效。

### Redis 保存登录锁定状态

登录失败计数通过 `LoginFailureStore` 抽象写入 Redis，多个 API 实例共享同一锁定窗口，避免多副本部署下按实例分别计数。测试构造仍保留内存 fallback，便于单元测试和轻量本地验证。

### knowledgeBaseIds 做资源级授权

权限点决定用户能做什么，`knowledgeBaseIds` 决定用户能对哪些知识库做。所有包含 `{kbId}` 的公开知识库、文档、检索、聊天和维护入口都会在权限点通过后继续校验资源范围，防止拥有 `KB_READ` 或 `DOCUMENT_UPLOAD` 的用户横向访问其他知识库。

## 环境变量

| 变量 | 用途 | 建议 |
|------|------|------|
| `DUPI_AUTH_SECRET` | Bearer token HMAC 签名密钥 | 配置内置账号时必须设置强随机值 |
| `DUPI_AUTH_TOKEN_TTL_SECONDS` | token 有效期秒数 | 默认 `28800`，即 8 小时 |
| `DUPI_AUTH_LOGIN_MAX_FAILURES` | 连续登录失败阈值 | 默认 `5` |
| `DUPI_AUTH_LOGIN_LOCKOUT_SECONDS` | 登录失败锁定秒数 | 默认 `300`，设置 `0` 可关闭 |
| `DUPI_SECURITY_USERS_0_USERNAME` | 第一个内置账号用户名 | 例如 `admin` |
| `DUPI_SECURITY_USERS_0_PASSWORD_HASH` | PBKDF2 密码哈希 | 生产/共享环境优先使用 |
| `DUPI_SECURITY_USERS_0_PASSWORD` | 明文密码兼容字段 | 仅建议本地首次启动使用 |
| `DUPI_SECURITY_USERS_0_TENANT_ID` | 账号绑定租户 | 默认可用 `default` |
| `DUPI_SECURITY_USERS_0_ROLE` | 账号角色 | `ADMIN` 或 `USER` |
| `DUPI_SECURITY_USERS_0_PERMISSIONS` | 账号权限点 | 逗号分隔，例如 `KB_READ,DOCUMENT_UPLOAD,CHAT_WRITE` |
| `DUPI_SECURITY_USERS_0_KNOWLEDGE_BASE_IDS` | 账号可访问知识库范围 | 逗号分隔知识库 ID；空或 `*` 表示不限制 |
| `DUPI_SECURITY_USERS_0_TOKEN_VERSION` | token 版本 | 默认 `1`，变更后旧 token 失效 |
| `DUPI_SECURITY_USERS_0_DISABLED` | 是否禁用账号 | 禁用后拒绝登录并拒绝已签发 token |

未配置 `DUPI_SECURITY_USERS_*` 且未配置 `DUPI_API_KEY` 时，公共 API 继续保持本地开放模式，便于单机开发和自测。

## 登录示例

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"change-me-admin-password"}'
```

浏览器登录后依赖 `Set-Cookie: DUPI_AUTH=...; HttpOnly` 和 `Set-Cookie: DUPI_CSRF=...` 维持会话。Cookie 会话发起 mutating 请求时需携带 CSRF 请求头：

```bash
curl -X POST http://localhost:8080/api/v1/knowledge-bases \
  -H "Content-Type: application/json" \
  -H "X-Dupi-CSRF-Token: <csrfToken>" \
  -b "DUPI_AUTH=<auth-cookie>; DUPI_CSRF=<csrfToken>" \
  -d '{"name":"demo"}'
```

脚本和自动化客户端仍可使用 Bearer 兼容路径：

```bash
curl http://localhost:8080/api/v1/knowledge-bases \
  -H "Authorization: Bearer <token>"
```

## 账号管理入口

`/ops/accounts` 是数据库账号管理工作台。管理员可以在页面完成新建账号、编辑租户/角色/知识库范围、禁用/启用账号、轮换 `tokenVersion` 和重置密码。账号角色使用下拉选择，权限来自角色绑定，不再在账号页手工输入权限点；禁用账号会立即拒绝该账号登录和已签发 token；轮换 `tokenVersion` 或重置密码会使旧 token 失效。

`/ops/roles` 是角色管理工作台。管理员可以创建角色、编辑角色名称与权限点、禁用角色；账号登录和 token 解析会拒绝已禁用角色。审计日志筛选中的动作、目标类型和状态，以及账号/角色管理中的权限选择，均通过 `/api/v1/ops/metadata` 提供下拉元数据，减少手工输入错误。该元数据同时返回权限说明：每个权限点包含名称、用途、允许动作和不允许动作，前端在角色权限选择、角色列表和账号角色权限列表中以悬停/聚焦浮层展示，帮助管理员确认权限边界。

对应 API：

| 能力 | 接口 |
|------|------|
| 账号列表 | `GET /api/v1/ops/accounts` |
| 新建账号 | `POST /api/v1/ops/accounts` |
| 更新账号 | `PATCH /api/v1/ops/accounts/{username}` |
| 重置密码 | `POST /api/v1/ops/accounts/{username}/reset-password` |
| 禁用账号 | `POST /api/v1/ops/accounts/{username}/disable` |
| 启用账号 | `POST /api/v1/ops/accounts/{username}/enable` |
| 轮换 tokenVersion | `POST /api/v1/ops/accounts/{username}/rotate-token` |
| 角色列表 | `GET /api/v1/ops/roles` |
| 新建角色 | `POST /api/v1/ops/roles` |
| 更新角色 | `PATCH /api/v1/ops/roles/{roleId}` |
| 禁用角色 | `POST /api/v1/ops/roles/{roleId}/disable` |
| 运维元数据 | `GET /api/v1/ops/metadata` |

## 薄弱点修复记录

| 薄弱点 | 风险 | 解决方式 | 验证点 |
|------|------|------|------|
| 共享公共 API Key 无法区分普通用户与管理员 | 普通用户可能借助代理注入的 API Key 调用 ops 运维接口 | Cookie/Bearer 用户会话优先，API Key 仅作为兼容管理员入口 | USER 会话 + API Key 访问 ops 仍返回 403 |
| 浏览器 token 暴露给 JavaScript | XSS 会直接读取长期 Bearer token | 登录改为 `DUPI_AUTH` HttpOnly Cookie，本地仅保存 CSRF token | 登录响应不返回可读 token，前端 API 使用 `credentials: include` |
| Cookie 会话缺少 CSRF 防护 | 第三方页面可诱导浏览器发起高风险请求 | `DUPI_CSRF` Cookie + `X-Dupi-CSRF-Token` 双提交校验 | Cookie mutating 请求缺少 CSRF 被拒绝；Bearer/API Key 不受影响 |
| 高风险路由只有角色边界 | USER 可能执行删除、重建、重试等维护动作 | 新增路由权限点 `OPS_ADMIN`、`DOCUMENT_DELETE`、`MAINTENANCE` 等 | USER 默认不能删除文档；显式授权后可删除但仍不能访问 ops |
| 明文密码缺少生产化替代 | 部署配置泄露时账号密码直接暴露 | 新增 `passwordHash`，支持 PBKDF2 哈希密码 | PBKDF2 哈希账号可登录，错误密码不可登录 |
| 登录失败缺少保护 | 密码可被持续爆破，多副本下可绕过单实例计数 | 登录失败计数迁移到 Redis-backed `LoginFailureStore` | 连续失败达到阈值后跨服务实例拒绝登录，锁定窗口后恢复 |
| token 无法主动失效 | 密码轮换后旧 token 仍可用到过期 | token payload 加入 `ver`，解析时比对当前账号 `tokenVersion` | tokenVersion 变更后旧 token 被拒绝 |
| 租户隔离依赖客户端请求头 | 客户端可能伪造 `X-Dupi-Tenant-Id` | Bearer token 绑定租户，token 租户优先于请求头 | token 租户不会被请求头覆盖 |
| 全局权限点缺少资源范围 | 有 `KB_READ` 的用户可横向访问其他知识库 | `knowledgeBaseIds` 限定账号可访问知识库集合 | scoped USER 访问未授权 kbId 返回 403 |
| 审计日志只能写入不可查询 | 管理员难以追踪删除、reindex、retry 和 ops 操作 | 新增 audit logs 查询、CSV 导出、保留清理、失败告警 API 与前端审计日志页 | `/api/v1/ops/audit-logs` 支持筛选并在 `/ops/audit-logs` 展示、导出和查看告警 |
| 账号配置不可管理 | 管理员难以确认和调整账号租户、角色和知识库范围 | 新增数据库账号管理接口和 `/ops/accounts` 管理页，支持创建、更新、禁用/启用、tokenVersion 轮换和密码重置 | 页面展示 username、tenantId、roleCode、permissions、knowledgeBaseIds、禁用状态和凭证配置状态，并可执行管理动作 |
| 角色无实际管理能力 | 用户仍要逐个选择权限，角色字段价值不足 | 新增 `roles` 表、角色管理 API 和 `/ops/roles` 页面，账号通过 `roleCode` 继承角色权限 | 新建/更新角色后账号权限跟随角色；禁用角色后对应账号 token 被拒绝 |
| internal API 与用户认证边界不清晰 | 用户 token 可能误入系统链路 | internal API 继续只校验 `X-Dupi-Internal-Key` | internal key 与 Bearer 账号互不替代 |

## 后续建议

- 后续可接入外部 SSO/OIDC，并把当前数据库账号作为本地/应急模式。
- 账号/角色管理 UI、审计日志导出、审计保留策略和高风险操作告警已补基础能力；继续补外部 SSO/OIDC、外部告警通知和审计归档对接。
