# 知识库问答历史会话持久化设计

## Summary

当前知识库的问答消息只保存在前端 `ChatPanel` 的组件状态中。用户离开知识库、刷新页面或重新打开知识库后，原有会话记录会消失；后端现有 `sessionId` 主要用于流式问答取消，不承担历史会话存储职责。

本设计目标是在每个知识库内持久化历史会话，支持重新进入知识库后恢复历史问答，并提供类似千问、豆包、ChatGPT 的历史管理体验：会话列表、切换会话、单条删除、批量选择删除、标题重命名。

## Chosen Approach

采用后端持久化会话与消息的方案：

- 每个知识库独立保存历史会话。
- 后端新增会话表和消息表，消息中保存文本与引用来源快照。
- 前端问答页在桌面端展示左侧历史栏，移动端使用抽屉展示历史会话。
- 会话默认标题使用第一条用户问题生成，支持用户手动重命名。
- 删除支持两种入口：单条会话右侧菜单删除，以及管理模式下勾选多条批量删除。

选择该方案的原因：

- 比 localStorage 更可靠，刷新、换页面、重启浏览器后仍可恢复。
- 与知识库业务模型一致，避免不同知识库的问答上下文混在一起。
- 第一版保留必要的 RAG 可追溯性：恢复消息正文时也恢复引用来源。
- 不引入额外 LLM 标题总结调用，避免成本、失败兜底和延迟复杂度。
- 后续可平滑扩展到全局历史入口、审计参数快照、会话搜索等能力。

## Out Of Scope

第一版不实现以下能力：

- 全局跨知识库历史入口。
- 使用 LLM 自动总结会话标题。
- 保存完整检索参数、模型参数、prompt 快照等审计信息。
- 多租户权限模型重构。
- 会话全文搜索。

这些能力可基于本设计的数据模型后续扩展。

## Product Behavior

### 会话范围

历史会话归属于单个知识库。用户进入知识库详情页并切换到“智能问答”后，只看到当前知识库下的会话。

### 会话创建

- 用户点击“新建会话”或在无活动会话状态下发送第一条问题时创建会话。
- 会话标题默认取第一条用户问题，前端显示时可截断。
- 如果用户在已有会话中继续提问，消息追加到当前会话。

### 会话恢复

- 用户重新打开知识库并进入问答页时，前端拉取当前知识库的会话列表。
- 默认选中最近更新的会话。
- 选中某个会话后，前端拉取该会话的消息列表并恢复聊天窗口。
- 历史助手消息应继续使用 Markdown 渲染。
- 历史引用来源应继续在引用来源区域展示。

### 会话重命名

- 用户可从会话项右侧菜单触发重命名。
- 重命名为空时不提交。
- 标题长度建议限制为 120 字符。

### 单条删除

- 每条历史会话右侧提供更多菜单，包含“删除”。
- 删除前弹出确认。
- 删除成功后，该会话从列表移除。
- 如果删除的是当前会话，前端切换到最近的剩余会话；若无剩余会话，则进入空会话状态。

### 批量删除

- 历史栏提供“管理”入口。
- 管理模式下展示复选框，用户可选择多条会话。
- 批量删除前弹出确认，并显示删除数量。
- 删除后清空选择状态并退出或保留管理模式，具体交互以实现时最自然的方式为准；核心要求是避免误删。

### 空状态

当前知识库无会话时，问答区展示原有空状态文案，并提供“新建会话”入口。若当前知识库没有已完成摄入的文档，继续沿用现有不可问答提示。

## Data Model

### `chat_sessions`

建议新增表：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | UUID | 会话主键 |
| `kb_id` | UUID | 所属知识库，外键关联 `knowledge_bases(id)` |
| `tenant_id` | VARCHAR(64) | 预留租户字段，默认 `default` |
| `title` | VARCHAR(255) | 会话标题，默认第一条用户问题 |
| `status` | VARCHAR(32) | 会话状态，建议 `active` / `deleted` |
| `created_at` | TIMESTAMPTZ | 创建时间 |
| `updated_at` | TIMESTAMPTZ | 最近更新时间 |

索引：

- `idx_chat_sessions_kb_updated`：`(kb_id, updated_at DESC)`

删除策略：

- 第一版可采用硬删除，依赖外键级联删除消息。
- 如果希望后续支持回收站或审计，可改为软删除 `status='deleted'`。第一版 API 行为不暴露已删除会话。

### `chat_messages`

建议新增表：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | UUID | 消息主键 |
| `session_id` | UUID | 所属会话，外键关联 `chat_sessions(id)` |
| `role` | VARCHAR(32) | `user` 或 `assistant` |
| `content` | TEXT | 消息正文 |
| `citations` | JSONB | 引用来源快照，仅助手消息通常有值 |
| `status` | VARCHAR(32) | `completed` / `interrupted` / `failed` |
| `created_at` | TIMESTAMPTZ | 创建时间 |

索引：

- `idx_chat_messages_session_created`：`(session_id, created_at ASC)`

引用来源 JSON 建议复用现有前端 `Citation` 结构：

```json
[
  {
    "chunkId": "uuid-or-string",
    "docId": "uuid",
    "fileName": "sample.md",
    "snippet": "引用片段",
    "score": 0.92
  }
]
```

## API Design

所有接口挂在当前知识库路径下：

### 列表会话

`GET /api/v1/knowledge-bases/{kbId}/chat-sessions`

返回当前知识库下的会话摘要列表，按 `updatedAt` 倒序。

### 创建会话

`POST /api/v1/knowledge-bases/{kbId}/chat-sessions`

请求体可为空，也可包含可选标题：

```json
{ "title": "可选标题" }
```

### 获取会话详情

`GET /api/v1/knowledge-bases/{kbId}/chat-sessions/{sessionId}`

返回会话元数据和消息列表。

### 重命名会话

`PATCH /api/v1/knowledge-bases/{kbId}/chat-sessions/{sessionId}`

```json
{ "title": "新的标题" }
```

### 删除单条会话

`DELETE /api/v1/knowledge-bases/{kbId}/chat-sessions/{sessionId}`

### 批量删除会话

`POST /api/v1/knowledge-bases/{kbId}/chat-sessions/batch-delete`

```json
{ "sessionIds": ["uuid-1", "uuid-2"] }
```

### 发送聊天消息

沿用现有：

`POST /api/v1/knowledge-bases/{kbId}/chat`

扩展语义：

- 如果请求体包含 `sessionId`，则追加消息到该会话。
- 如果请求体没有 `sessionId`，后端创建新会话并在 `done` SSE 事件中返回真实 `sessionId`。
- 后端保存用户问题、助手回答和引用来源。
- 流式响应中的 `retrieval` 事件仍返回引用来源，前端可以即时展示。
- `done` 事件返回 `sessionId`，必要时可补充 `messageId` 或会话标题。

## Backend Flow

### 流式问答成功路径

1. 校验知识库存在。
2. 查找或创建 `chat_session`。
3. 保存用户消息。
4. 执行检索，生成 citations。
5. 发送 `retrieval` SSE 事件。
6. 调用 LLM 流式接口。
7. 聚合 token 到内存中的 assistant buffer，同时向前端持续发送 `token` 事件。
8. 流结束后保存助手消息，写入 `content` 和 `citations`。
9. 更新会话 `updated_at`。
10. 发送 `done` 事件。

### 中断和失败

- 用户点击停止时，前端继续调用现有取消接口。
- 如果已产生部分 assistant 内容，后端保存为 `interrupted` 状态。
- 如果 LLM 调用失败，后端保存用户消息；助手失败消息可按当前错误处理策略决定是否保存，建议保存为 `failed`，便于用户复盘。
- 前端展示失败状态时，不应破坏已经保存的历史消息。

### 事务边界

- 创建会话与保存用户消息应在一个短事务内完成。
- 流式 token 聚合不应长时间持有数据库事务。
- 助手消息在流结束或中断时单独写入。

## Frontend Design

### 组件结构

建议拆分：

- `ChatPanel`：问答容器，协调历史会话与当前消息。
- `ChatHistorySidebar`：桌面端左侧历史栏。
- `ChatHistoryDrawer`：移动端抽屉。
- `ChatSessionListItem`：单条会话项，包含标题、更新时间、更多菜单。
- `ChatSessionBulkToolbar`：管理模式下的批量操作栏。

### 状态模型

前端维护：

- `sessions`：当前知识库会话列表。
- `activeSessionId`：当前会话。
- `messages`：当前会话消息。
- `citations`：当前助手消息或选中历史助手消息的引用来源。
- `selectionMode`：是否处于管理模式。
- `selectedSessionIds`：批量删除选中集合。

### 交互细节

- 点击“新建会话”时清空当前消息区，但不立刻创建数据库记录；用户发送第一条消息时创建，避免空会话污染列表。
- 切换会话时，如果当前没有流式请求，直接加载对应消息；如果正在流式输出，提示先停止或等待完成。
- 当前会话有流式请求时，历史删除和切换应避免破坏正在进行的请求。
- 单删和批量删除都需要确认。
- 删除当前会话后自动选择最近会话或进入空状态。

## Error Handling

- 会话不存在或不属于当前知识库时返回 404。
- 批量删除中包含不存在或不属于当前知识库的 ID 时，建议整体返回 400，避免用户误以为全部成功。
- 重命名标题为空或过长时返回校验错误。
- 获取会话列表失败时，前端保留当前问答区并提示“历史会话加载失败”。
- 保存历史失败但 LLM 已正常回答时，后端应记录日志并通过 SSE error 或 done payload 暴露可理解的状态；第一版可以优先保证回答不中断，再补充错误提示。

## Testing Plan

### API 单元测试

- 创建新会话并保存首条用户消息。
- 追加消息到已有会话。
- 流式完成后保存助手消息和 citations。
- 取消或异常时保存 interrupted/failed 状态。
- 列表只返回当前知识库会话。
- 删除单条会话。
- 批量删除会话。
- 重命名校验。

### 前端测试

- 进入知识库问答页后加载会话列表。
- 点击历史会话后加载消息并恢复 Markdown 内容。
- 新建会话后发送第一条消息，列表出现新会话。
- 单条删除有确认并移除列表项。
- 管理模式多选批量删除。
- 重命名成功后更新标题。
- 移动端抽屉入口存在并能切换会话。

### E2E 流程

扩展 `scripts/e2e-main-flow.ps1` 或新增脚本覆盖：

1. 创建知识库。
2. 上传并等待文档摄入完成。
3. 发起 chat SSE。
4. 校验会话列表出现新会话。
5. 读取会话详情，确认用户消息、助手消息、citations 已保存。
6. 重命名会话。
7. 删除会话。

## Open Risks

- 当前代码中部分中文字符串存在编码乱码，历史会话 UI 实现时应顺手修复受影响的问答文案，否则新功能会继承不佳体验。
- SSE 流式保存助手回答需要聚合 token，需避免长事务和内存无限增长；第一版可限制单次回答长度并沿用现有 `ChatRequest` 长度限制。
- 如果 LLM 流中断，用户对“部分回答是否保留”的预期可能不同；本设计选择保留部分回答并标记 interrupted。
- `.superpowers/` 是 brainstorming 可视化临时目录，不属于产品代码，提交实现前应确保不会误入版本库。

## Next Skill

设计确认并写入本文档后，下一步进入 `$superpower-writing-plans`，把该设计拆成数据库迁移、后端 API、前端 UI、测试与 E2E 的实施计划。
