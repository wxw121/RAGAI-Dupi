# Outbox、Tombstone、实例级授权与 Ops 运维增强（2026-07-07）

<!-- language-switch -->
[English](../en/outbox-tombstone-rbac-ops-2026-07-07.md)





## 背景

本轮按 `transactional outbox → 删除/Worker tombstone → 文档/会话/任务实例级授权 → 账号管理 UI → 审计导出/保留/告警` 顺序推进。目标是把摄入投递、删除竞态、资源授权和运维审计从“基本可用”提升到更接近生产可恢复、可追踪、可管理。

## 已完成改动

- **Transactional outbox**：上传、摄入重试和 reindex 不再在业务事务内直接依赖 Redis 投递；事务内写入 `ingest_outbox_events`，由 `IngestOutboxService` 定时投递 Redis。
- **Outbox 退避重试**：Redis 投递失败时 outbox 事件标记 `FAILED`，保留错误原因和下一次投递时间；文档和任务保持 `PENDING + QUEUED`，避免出现数据库已提交但队列消息丢失的不可见状态。
- **删除 tombstone**：文档删除前写入 `document_tombstones`，记录 docId、kbId、objectKey、fileName 和删除原因。
- **Worker 迟到回调防护**：Worker 状态回调处理前检查 tombstone；已删除文档的迟到回调不会恢复 chunks、document 状态或 ingest job。
- **Outbox 删除防护**：outbox dispatcher 遇到 tombstoned document 会取消事件，不再把已删除文档重新投递给 Worker。
- **实例级授权**：在知识库范围授权基础上，文档、会话和向量清理任务等实例级操作会解析实例归属 kbId 后再校验 `SecurityContext.canAccessKnowledgeBase(...)`。
- **账号管理 UI**：新增 `/ops/accounts`，展示内置账号的 username、tenantId、role、permissions、knowledgeBaseIds、tokenVersion 和密码/哈希配置状态；仅展示脱敏元数据。该能力已在 2026-07-12 升级为数据库账号 + 角色管理，详见 `docs/rbac-ops-admin-2026-07-06.md` 与 `docs/progress.md`。
- **审计导出/保留/告警**：新增 CSV 导出接口 `/api/v1/ops/audit-logs/export`，保留清理由 `AUDIT_RETENTION_DAYS` / `AUDIT_RETENTION_CRON` 控制，告警接口 `/api/v1/ops/audit-alerts` 在近期失败审计数超过阈值时返回 `AUDIT_FAILED_SPIKE`。
- **审计运维 UI**：`/ops/audit-logs` 增加告警摘要和“导出 CSV”按钮，继续支持租户、动作、目标类型、状态和 limit 筛选。

## 新增/调整入口

| 能力 | 入口 |
|------|------|
| outbox 定时投递 | `dupi.ingest.outbox-dispatch-cron` / `INGEST_OUTBOX_DISPATCH_CRON` |
| 审计查询 | `GET /api/v1/ops/audit-logs` |
| 审计 CSV 导出 | `GET /api/v1/ops/audit-logs/export` |
| 审计告警摘要 | `GET /api/v1/ops/audit-alerts` |
| 内置账号元数据 | `GET /api/v1/ops/accounts`（2026-07-12 后为数据库账号管理入口） |
| 账号管理页 | `/ops/accounts` |
| 审计日志页 | `/ops/audit-logs` |

## 环境变量

| 变量 | 默认值 | 说明 |
|------|------|------|
| `INGEST_OUTBOX_DISPATCH_CRON` | `*/10 * * * * *` | outbox dispatcher 扫描待投递/失败事件的频率 |
| `AUDIT_RETENTION_DAYS` | `180` | 审计日志保留天数；小于等于 0 时不清理 |
| `AUDIT_RETENTION_CRON` | `0 15 2 * * *` | 审计日志过期清理 cron |
| `AUDIT_ALERT_WINDOW_MINUTES` | `30` | 失败审计告警统计窗口 |
| `AUDIT_ALERT_FAILED_THRESHOLD` | `10` | 窗口内失败审计数达到该阈值后返回告警 |

## 验证记录

- `mvn "-Dtest=EntityLifecycleTest,DtoCoverageTest,DocumentTombstoneServiceTest,IngestOutboxServiceTest,AuditLogServiceTest" test`：新增 tombstone、outbox、审计告警 DTO 与异常分支覆盖测试，29 个测试通过。
- `mvn verify`：API 177 个测试全部通过，JaCoCo 覆盖率检查通过；最终行覆盖率为 95.23%（covered=2475，missed=124）。
- `npm test`：Web 5 个测试文件、36 个测试全部通过。
- `npm run build`：Web 生产构建通过，Vite 成功生成 `dist` 产物。
- `python -m pytest`：Worker 37 个测试全部通过。

## 后续建议

- 账号管理只读元数据页已在后续版本升级为数据库账号 + 角色管理，支持账号创建/禁用、角色分配、tokenVersion 轮换和密码重置；后续重点转向 SSO/OIDC 与外部身份源同步。
- 审计告警当前为接口与页面摘要；生产部署可接入邮件、Webhook 或 IM 通知。
- outbox 当前保障投递可靠性；如后续扩展到多实例高并发，可增加行级锁/claim 字段，减少重复扫描竞争。
