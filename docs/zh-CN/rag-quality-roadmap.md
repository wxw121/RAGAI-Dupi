# RAG 质量体系后续版本规划

更新时间：2026-07-21

本路线图承接 V1.6b-V2.4 的评估集、质量看板和质量闭环指标，后续重点从“看见质量问题”推进到“自动沉淀问题、定位根因、推荐实验、受控发布”。

## 推荐节奏

| 版本 | 主题 | 目标 | 主要交付 | 验收口径 |
| --- | --- | --- | --- | --- |
| V2.5 | 真实反馈闭环持久化 | 将失败、低置信、用户负反馈和人工标注沉淀为候选评估用例 | feedback queue、标注状态、采样规则、用例转化脚本、Web 反馈列表 | 可从一次失败评估或线上反馈生成候选 case，且不会自动污染正式基准 |
| V2.6 | 答案质量裁判层 | 用可审计规则评估 groundedness、引用完整性、拒答合理性和幻觉风险 | deterministic citation verifier、rubric schema、可选 LLM judge adapter、人工复核字段 | 每条 case 具备可解释质量评分和失败原因，不依赖单一总分 |
| V2.7 | 检索实验注册表 | 将 profile、TopK、mode、rerank、chunk/index 参数实验结构化管理 | experiment registry、run lineage、best-candidate recommendation、差异报告 | 同一基准可比较多组实验，并输出推荐配置与阻断原因 |
| V2.8 | 数据/索引治理自动化 | 从质量结果反推语料、chunk、source、embedding 和索引问题 | corpus drift scanner、conflict detector、chunk quality report、reindex recommendation | 能列出需要补源、重分块、重建索引或人工消歧的知识库/文档 |
| V2.9 | 线上质量 SLO 与告警 | 把 fallback、no-answer、延迟、降级 profile 和质量回归纳入运营门禁 | quality telemetry API、SLO summary、alert rule、dashboard 趋势 | 支持按知识库/profile/租户查看质量 SLO，超阈值时给出动作建议 |
| V3.0 | Canary 发布与自动回滚 | 将离线评估、线上观测和发布 readiness 合并为受控推广流程 | canary policy、shadow eval、promote/rollback gate、release report | 新 profile 或索引策略只有通过 canary gate 才能成为默认配置 |

## 代码改进主线

- 将 `RagEvalService` 中持续增长的 metrics 组装逻辑拆成独立 calculators，降低后续版本耦合。
- 为 `RagEvalRun.metrics` 建立稳定 schema 文档和契约测试，保留 JSON 扩展性但避免前后端字段漂移。
- 将 Web Quality dashboard 的卡片抽为可复用组件，减少继续增加质量维度时的 JSX 重复。
- 为评估脚本增加小型 fixture 和 artifact contract，优先保持低资源本地验证。
- 逐步引入真实线上反馈表或事件流，但默认只采样、脱敏、候选化，不直接进入正式评估基准。

## 资源控制策略

- V2.5-V2.8 优先使用单元测试、组件测试、Pester、TypeScript build 和小 fixture，不默认跑 Docker Compose、浏览器 E2E 或压测。
- V2.9-V3.0 需要真实环境证据时，先写 runbook 和 dry-run 脚本，再由人工选择窗口执行重资源验证。
- 如果某项问题连续 5 次尝试仍未解决，记录到实施文档的 Deferred / Known Issues 区，不阻塞当前低风险增量。

## Post-V3.0 暂缓方向

- 多模态 OCR / 图片表格检索质量体系。
- 可视化 Knowledge Pipeline DSL。
- K8s Helm、生产级多租户合规审计和完整灾备发布演练。
- 真正高并发 load/latency 压测与长期成本优化实验。
