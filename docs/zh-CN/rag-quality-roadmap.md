# RAG 质量体系 V2.0 封板路线图

更新日期：2026-07-26

V2.0 是当前 RAG 质量体系封板可发布包。它从原 V2.5-V3.0 后续路线中挑选最高价值能力，作为 MVP 合并进现有 `RagEvalRun.metrics` JSON 合约和 Web RAG 评估面板，不新增数据库迁移或生产事件流。

## 当前发布：V2.0

| 范围 | V2.0 交付物 | 证据 |
| --- | --- | --- |
| 反馈候选 | 将失败或退化的评估结果持久化为不可变反馈候选快照 | `id`、`sourceRunId`、`sourceResultId`、`retrievalProfile`、`reviewStatus`、`suggestedAction`、`judgeStatus` |
| 确定性答案裁判 | 对引用缺失、错误拒答/无答案和幻觉风险给出可解释证据 | `answerQuality.judgeStatus`、`riskCases`、`hallucinationRiskCount`、`unsupportedAnswerRiskCount` |
| 检索实验矩阵 | 汇总 TopK、profile、检索模式、case 数、评估数和 rerank 证据 | `experimentMatrix` |
| 数据/索引治理摘要 | 汇总期望来源覆盖、缺失来源、多文档/歧义用例、embedding 维度和治理状态 | `dataIndexGovernance` |
| 线上质量 SLO | 将评估运行转换为通过率、fallback、延迟和 profile 回归目标 | `onlineSlo` |
| Canary 门禁 | 只有发布指标、SLO、答案质量和实际 profile gate 都干净时才允许候选 profile promote | `canaryGate`，含 `profileGateStatuses` |
| 发布报告 | 每次运行给出封板状态、建议动作和证据清单 | `v2QualityClosure`、`releaseReport` |

## 版本口径

- `2.0.0` 是当前 API/Web 可发布包元数据。
- V1.6b-V2.4 是已经完成的本地 RAG 质量里程碑，用作 V2.0 的基础，不再单独封包。
- 原 V2.5-V3.0 不再作为计划发布名；其中被选中的 MVP 能力已经收敛进 V2.0。

## Post-V2.0 后续池

| 主题 | 延后原因 |
| --- | --- |
| 专用反馈工作流表 | V2.0 保存不可变 metrics 快照；可变评审分配、批量提升和流程历史需要单独 schema 与迁移。 |
| 生产遥测和告警路由 | V2.0 基于评估运行生成 SLO 摘要；租户/知识库/profile 级长期遥测、告警渠道和升级策略仍是生产运维项目。 |
| 可选 LLM 裁判 | V2.0 使用确定性、可解释规则；LLM rubric 需要模型/供应商控制、抽审策略和成本治理。 |
| 完整实验注册表 | V2.0 输出实验矩阵；实验生命周期、负责人、谱系 UI 和最佳候选推荐留到后续。 |
| 更广平台能力 | 多模态 OCR、可视化 Pipeline DSL、K8s/Helm、多租户合规审计、高并发压测和长期成本优化不属于 V2.0。 |

## 是否还有后续版本

完成并验证 V2.0 后，本轮选择的 RAG 质量封板 MVP 可以封板；没有必须继续做的后续版本来满足 V2.0 范围。之后是否开新版本，应由 Post-V2.0 后续池和真实生产需求决定。
