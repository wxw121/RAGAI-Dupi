import { useEffect, useMemo, useState } from 'react'
import {
  confirmAddRagEvalCases,
  createRagEvalCase,
  confirmRagEvalCaseGeneration,
  deleteRagEvalCase,
  listRagEvalCases,
  listRagEvalRuns,
  listRetrievalProfiles,
  getRagQualityPolicy,
  promoteRagEvalBaseline,
  previewAddRagEvalCases,
  previewRagEvalCaseGeneration,
  runRagEval,
  updateRagQualityPolicy,
  updateRagEvalCase,
} from '@/api/knowledgeBase'
import { listDocuments } from '@/api/documents'
import { useToast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import type {
  Document,
  RagEvalCase,
  RagEvalCaseCategory,
  RagEvalCaseRequest,
  RagEvalGateDecision,
  RagEvalGenerationPreview,
  RagEvalRun,
  RagEvalRunRequest,
  RagQualityPolicy,
  RetrievalProfile,
  RetrievalIndexMode,
} from '@/types'
import { CheckCircle2, CircleHelp, Loader2, Pencil, Play, Save, Trash2, X, XCircle } from 'lucide-react'

interface RagEvalPanelProps {
  kbId: string
}

const RETRIEVAL_PROFILE_OPTIONS: Array<{ value: RetrievalIndexMode; label: string }> = [
  { value: 'CLASSIC', label: 'classic' },
  { value: 'PARENT_CHILD', label: 'parent-child' },
  { value: 'QA_ASSISTED', label: 'qa-assisted' },
  { value: 'COMBINED', label: 'combined' },
]

const CASE_CATEGORY_OPTIONS: Array<{ value: RagEvalCaseCategory; label: string }> = [
  { value: 'REAL_QUERY', label: '真实查询' },
  { value: 'HARD_NEGATIVE', label: '困难负样本' },
  { value: 'MULTI_DOCUMENT', label: '多文档' },
  { value: 'AMBIGUOUS', label: '歧义问题' },
]

const FAILURE_CATEGORY_LABELS: Record<string, string> = {
  UNEXPECTED_EVIDENCE: '出现非预期来源',
  INSUFFICIENT_HITS: '命中数量不足',
  MISSING_EXPECTED_FILE: '缺少期望来源文件',
  MISSING_EXPECTED_TOKEN: '缺少期望证据词',
  RETRIEVAL_EXCEPTION: '检索过程异常',
}

function caseCategoryLabel(category: RagEvalCaseCategory | undefined) {
  return CASE_CATEGORY_OPTIONS.find((option) => option.value === (category ?? 'REAL_QUERY'))?.label ?? '其他场景'
}

function failureCategoryLabel(category: string) {
  return FAILURE_CATEGORY_LABELS[category] ?? '其他失败原因'
}

const TERM_HELP: Record<string, { description: string; purpose: string }> = {
  Rerank: {
    description: '对首次检索出的候选内容再次排序，把与问题更相关的片段排到前面。',
    purpose: '通常能提高答案准确性，但会增加少量响应时间和模型调用成本。',
  },
  '检索 Profile': {
    description: '同一批评估用例要测试的索引与检索策略，例如 classic、parent-child、qa-assisted。',
    purpose: '用于横向比较不同检索方案，决定知识库应启用哪一种索引模式。',
  },
  'TopK override': {
    description: '本次评估临时覆盖知识库默认 TopK，即每个问题最多取回多少个候选片段。',
    purpose: '用于测试召回数量变化对命中率、引用质量和延迟的影响，不会修改知识库默认配置。',
  },
  最低通过率: {
    description: '本次评估中必须通过的用例比例下限。',
    purpose: '低于该值时阻止把当前检索方案视为可发布版本。',
  },
  最大通过率下降: {
    description: '相对基线评估允许下降的最大百分点。',
    purpose: '防止新配置总体看似可用，但相对稳定版本发生明显质量退化。',
  },
  最大新增失败: {
    description: '与基线相比，允许新出现的失败用例数量上限。',
    purpose: '用于发现被总体通过率掩盖的局部回归问题。',
  },
  无基线时阻断: {
    description: '尚未指定基线评估时，是否直接将发布门禁判定为阻断。',
    purpose: '避免在没有稳定版本可比较的情况下贸然发布新检索配置。',
  },
  'Profile quality gates': {
    description: '逐个检索 Profile 检查命中率、引用通过率及相对基线的变化。',
    purpose: '用于判断某个候选检索策略可以推广，还是应继续调整。',
  },
  'Quality dashboard': {
    description: '汇总离线评估、线上信号、数据完整性和发布门禁的质量总览。',
    purpose: '帮助快速判断当前 RAG 方案是否达到发布条件，以及主要阻塞项在哪里。',
  },
  'Release readiness': {
    description: '综合各质量信号计算当前版本的发布准备状态和阻塞项数量。',
    purpose: '作为是否允许进入发布或灰度阶段的总体判断。',
  },
  'V2.0 Quality closure': {
    description: '检查 V2.0 质量体系要求的能力是否形成完整闭环，并给出待办动作。',
    purpose: '确保发现问题后有评估、反馈、修复和复验链路，而不只是展示指标。',
  },
  'Real query feedback': {
    description: '从真实问答中的失败或降级信号提取出的候选评估问题。',
    purpose: '把线上暴露的问题沉淀为可重复运行的回归用例。',
  },
  'Experiment matrix': {
    description: '本次实验覆盖的 TopK、检索 Profile、检索模式和评估次数组合。',
    purpose: '确认对比实验覆盖是否完整，避免不同方案在不一致条件下比较。',
  },
  'Answer quality': {
    description: '衡量回答是否由检索证据支持、引用是否有效，以及是否存在幻觉风险。',
    purpose: '用于判断答案不仅“检索到了内容”，而且最终回答可信、可追溯。',
  },
  'Online SLO': {
    description: '线上服务等级目标，关注通过率、回退率、延迟等是否超过预设阈值。',
    purpose: '用于持续监控生产环境是否满足稳定性与质量承诺。',
  },
  'Canary gate': {
    description: '灰度发布门禁，根据离线质量和线上 SLO 决定继续推广还是回滚。',
    purpose: '在影响全部用户之前拦截质量回退或性能异常。',
  },
  'Online observability': {
    description: '汇总线上回退次数、回退率及 P95 响应延迟等运行信号。',
    purpose: '用于发现真实流量中的性能下降、检索失败和降级调用。',
  },
  'Data/index governance': {
    description: '检查评估所需来源文档是否齐全，以及索引是否覆盖这些预期来源。',
    purpose: '帮助区分“模型回答不好”和“文档缺失或索引不完整”两类问题。',
  },
  'Release gate rollup': {
    description: '汇总所有用例类别和检索 Profile 的门禁结果，给出最终 PASS 或 BLOCKED。',
    purpose: '提供统一的发布决策，避免只看单个高分指标。',
  },
  'Category summaries / trend': {
    description: '按真实查询、困难负样本、多文档等场景统计通过率、命中率、引用率及近期趋势。',
    purpose: '用于定位哪一类问题正在改善或退化。',
  },
  'Profile A/B comparison': {
    description: '将候选检索 Profile 与基线 Profile 对比通过率、命中率、引用率和 P95 延迟变化。',
    purpose: '用于判断新检索策略带来的质量收益是否值得其性能成本。',
  },
  'Diagnostic drilldown': {
    description: '按场景、通过状态和失败类型筛选最新一次评估的明细结果。',
    purpose: '用于从汇总指标下钻到具体失败问题，便于复现和修复。',
  },
}

function TermHelp({ term, align = 'right' }: { term: string; align?: 'left' | 'right' }) {
  const help = TERM_HELP[term]
  if (!help) return null

  return (
    <span className="group relative inline-flex shrink-0 align-middle">
      <button
        type="button"
        aria-label={`查看“${term}”的解释`}
        className="inline-flex h-5 w-5 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
      >
        <CircleHelp className="h-3.5 w-3.5" />
      </button>
      <span
        role="tooltip"
        className={`pointer-events-none invisible absolute top-full z-50 mt-2 w-72 rounded-lg border border-border bg-card p-3 text-left text-xs font-normal normal-case tracking-normal text-card-foreground opacity-0 shadow-xl transition-opacity group-hover:visible group-hover:opacity-100 group-focus-within:visible group-focus-within:opacity-100 ${align === 'left' ? 'left-0' : 'right-0'}`}
      >
        <span className="block font-semibold text-foreground">{term}</span>
        <span className="mt-1 block leading-5">{help.description}</span>
        <span className="mt-2 block leading-5 text-muted-foreground"><strong className="text-foreground">作用：</strong>{help.purpose}</span>
      </span>
    </span>
  )
}

const formatPercent = (value: number | null | undefined) => `${((value ?? 0) * 100).toFixed(1)}%`
const formatDelta = (value: number | null | undefined) => `${(value ?? 0) >= 0 ? '+' : ''}${formatPercent(value)}`
const normalizeCommaSeparatedInput = (value: string) => value.replace(/,\s+/g, ', ')

const EMPTY_FORM: RagEvalCaseRequest = {
  caseKey: '',
  query: '',
  minHits: 1,
  topK: 5,
  category: 'REAL_QUERY',
  expectedFileName: '',
  expectedDocumentId: undefined,
  expectedFileNames: [],
  expectedDocumentIds: [],
  mustContainAny: [],
}

export function RagEvalPanel({ kbId }: RagEvalPanelProps) {
  const [cases, setCases] = useState<RagEvalCase[]>([])
  const [runs, setRuns] = useState<RagEvalRun[]>([])
  const [form, setForm] = useState<RagEvalCaseRequest>(EMPTY_FORM)
  const [expectedFileNamesInput, setExpectedFileNamesInput] = useState('')
  const [mustContainAnyInput, setMustContainAnyInput] = useState('')
  const [editingId, setEditingId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [running, setRunning] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [generationPreview, setGenerationPreview] = useState<RagEvalGenerationPreview | null>(null)
  const [generationError, setGenerationError] = useState<string | null>(null)
  const [addPanelOpen, setAddPanelOpen] = useState(false)
  const [addDocuments, setAddDocuments] = useState<Document[]>([])
  const [sourceDocuments, setSourceDocuments] = useState<Document[]>([])
  const [selectedAddDocumentIds, setSelectedAddDocumentIds] = useState<string[]>([])
  const [addCasesPerDocument, setAddCasesPerDocument] = useState(2)
  const [addGenerationPreview, setAddGenerationPreview] = useState<RagEvalGenerationPreview | null>(null)
  const [addGenerating, setAddGenerating] = useState(false)
  const [addDocumentsLoading, setAddDocumentsLoading] = useState(false)
  const [addGenerationError, setAddGenerationError] = useState<string | null>(null)
  const [useRerank, setUseRerank] = useState(false)
  const [selectedProfiles, setSelectedProfiles] = useState<RetrievalIndexMode[]>(['CLASSIC'])
  const [retrievalProfiles, setRetrievalProfiles] = useState<RetrievalProfile[]>([])
  const [selectedRetrievalProfileId, setSelectedRetrievalProfileId] = useState('')
  const [experimentLabel, setExperimentLabel] = useState('')
  const [topKOverride, setTopKOverride] = useState('')
  const [diagnosticCategory, setDiagnosticCategory] = useState<'ALL' | RagEvalCaseCategory>('ALL')
  const [diagnosticStatus, setDiagnosticStatus] = useState<'ALL' | 'PASS' | 'FAIL'>('ALL')
  const [diagnosticFailureCategory, setDiagnosticFailureCategory] = useState('ALL')
  const [policy, setPolicy] = useState<RagQualityPolicy | null>(null)
  const { showError, showSuccess } = useToast()

  useEffect(() => {
    let active = true
    setLoading(true)
    void Promise.all([
      listRagEvalCases(kbId),
      listRagEvalRuns(kbId),
      getRagQualityPolicy(kbId),
      Promise.resolve(listRetrievalProfiles(kbId)),
      Promise.resolve(listDocuments(kbId)).catch(() => []),
    ])
      .then(([nextCases, nextRuns, nextPolicy, nextRetrievalProfiles, nextDocuments]) => {
        if (!active) return
        const profiles = nextRetrievalProfiles ?? []
        setCases(nextCases)
        setRuns(nextRuns)
        setPolicy(nextPolicy)
        setRetrievalProfiles(profiles)
        setSourceDocuments((nextDocuments ?? []).filter((document) => document.status === 'COMPLETED'))
        setSelectedRetrievalProfileId((current) => current || profiles.find((profile) => !profile.active)?.id || '')
      })
      .catch((error: unknown) => {
        if (active) showError(error instanceof Error ? error.message : 'RAG 评估数据加载失败')
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [kbId])

  const latestRun = runs[0]
  const selectedRetrievalProfile = retrievalProfiles.find((profile) => profile.id === selectedRetrievalProfileId)
  const selectedProfileHasUnbaselinedGate = selectedRetrievalProfile != null && policy?.blockWhenUnbaselined === false && runs.some((run) => (
    run.status === 'COMPLETED'
    && run.gateStatus === 'UNBASELINED'
    && run.profileSnapshot?.profileId === selectedRetrievalProfile.id
    && !run.profileSnapshot?.experimentLabel
    && run.profileSnapshot?.topKOverride == null
  ))
  const selectedProfileCanActivate = selectedRetrievalProfile != null && runs.some((run) => (
    run.status === 'COMPLETED'
    && (run.gateStatus === 'PASS' || (run.gateStatus === 'UNBASELINED' && selectedProfileHasUnbaselinedGate))
    && run.profileSnapshot?.profileId === selectedRetrievalProfile.id
    && !run.profileSnapshot?.experimentLabel
    && run.profileSnapshot?.topKOverride == null
  ))
  const invalidCases = cases.filter((caseDef) => caseDef.sourceValid === false)
  const latestResults = latestRun?.results ?? []
  const latestMetrics = latestRun?.metrics
  const releaseGate = latestMetrics?.releaseGate
  const categorySummaryEntries = Object.entries(latestMetrics?.categorySummaries ?? {})
  const profileComparisonEntries = Object.entries(latestMetrics?.profileComparisons ?? {})
  const canaryProfileGateDetail = Object.entries(latestMetrics?.canaryGate?.profileGateStatuses ?? {})
    .map(([profile, status]) => `${profile}:${status}`)
    .join(', ')
  const qualitySystemCards = [
    latestMetrics?.releaseReadiness && {
      title: 'Release readiness',
      version: latestMetrics.releaseReadiness.version ?? 'V2.0',
      value: latestMetrics.releaseReadiness.status ?? 'UNKNOWN',
      detail: `Score ${(latestMetrics.releaseReadiness.readinessScore ?? 0).toFixed(1)} · Blockers ${latestMetrics.releaseReadiness.blockerCount ?? 0}`,
    },
    latestMetrics?.v2QualityClosure && {
      title: 'V2.0 Quality closure',
      version: latestMetrics.v2QualityClosure.version ?? 'V2.0',
      value: latestMetrics.v2QualityClosure.status ?? 'UNKNOWN',
      detail: `Capabilities ${(latestMetrics.v2QualityClosure.completedCapabilities ?? []).length} · Actions ${(latestMetrics.v2QualityClosure.recommendedActions ?? []).join(', ') || '-'}`,
    },
    latestMetrics?.realQueryFeedback && {
      title: 'Real query feedback',
      version: latestMetrics.realQueryFeedback.version ?? 'V2.0',
      value: `${latestMetrics.realQueryFeedback.candidateCount ?? 0} candidates`,
      detail: latestMetrics.realQueryFeedback.source ?? 'rag_eval_failures_and_degraded_signals',
    },
    latestMetrics?.experimentMatrix && {
      title: 'Experiment matrix',
      version: latestMetrics.experimentMatrix.version ?? 'V2.0',
      value: `${latestMetrics.experimentMatrix.evaluationCount ?? 0} evals`,
      detail: `TopK ${(latestMetrics.experimentMatrix.topKValues ?? []).join(', ') || '-'} · Profiles ${(latestMetrics.experimentMatrix.profiles ?? []).join(', ') || '-'}`,
    },
    latestMetrics?.answerQuality && {
      title: 'Answer quality',
      version: latestMetrics.answerQuality.version ?? 'V2.0',
      value: formatPercent(latestMetrics.answerQuality.groundedPassRate),
      detail: `${latestMetrics.answerQuality.judgeStatus ?? 'JUDGED'} · Citation ${latestMetrics.answerQuality.citationPassedCount ?? 0}/${latestMetrics.answerQuality.citationEligibleCount ?? 0} · Risk ${latestMetrics.answerQuality.hallucinationRiskCount ?? 0}`,
    },
    latestMetrics?.onlineSlo && {
      title: 'Online SLO',
      version: latestMetrics.onlineSlo.version ?? 'V2.0',
      value: latestMetrics.onlineSlo.status ?? 'UNKNOWN',
      detail: `Breached ${(latestMetrics.onlineSlo.breachedObjectives ?? []).join(', ') || '-'}`,
    },
    latestMetrics?.canaryGate && {
      title: 'Canary gate',
      version: latestMetrics.canaryGate.version ?? 'V2.0',
      value: latestMetrics.canaryGate.decision ?? 'UNKNOWN',
      detail: `Reasons ${(latestMetrics.canaryGate.reasons ?? []).join(', ') || '-'} · Gates ${canaryProfileGateDetail || '-'}`,
    },
    latestMetrics?.onlineObservability && {
      title: 'Online observability',
      version: latestMetrics.onlineObservability.version ?? 'V2.0',
      value: `${latestMetrics.onlineObservability.fallbackCount ?? 0} fallback`,
      detail: `${latestMetrics.onlineObservability.sloStatus ?? 'OK'} · Fallback ${formatPercent(latestMetrics.onlineObservability.fallbackRate)} · P95 ${latestMetrics.onlineObservability.latencyP95Ms ?? 0} ms`,
    },
    latestMetrics?.dataIndexGovernance && {
      title: 'Data/index governance',
      version: latestMetrics.dataIndexGovernance.version ?? 'V2.0',
      value: formatPercent(latestMetrics.dataIndexGovernance.expectedSourceCoverageRate),
      detail: `${latestMetrics.dataIndexGovernance.governanceStatus ?? 'OK'} · Sources ${latestMetrics.dataIndexGovernance.matchedExpectedSourceCount ?? 0}/${latestMetrics.dataIndexGovernance.expectedSourceCount ?? 0} · Missing ${latestMetrics.dataIndexGovernance.missingSourceCount ?? 0}`,
    },
  ].filter((card): card is { title: string; version: string; value: string; detail: string } => Boolean(card))
  const failureCategoryOptions = useMemo(
    () => Array.from(new Set(latestResults.flatMap((result) => result.failureCategories ?? []))).sort(),
    [latestResults],
  )
  const availableCategoryOptions = useMemo(
    () => CASE_CATEGORY_OPTIONS
      .map((option) => ({
        ...option,
        count: latestResults.filter((result) => (result.category ?? 'REAL_QUERY') === option.value).length,
      }))
      .filter((option) => option.count > 0),
    [latestResults],
  )
  const passedResultCount = latestResults.filter((result) => result.passed).length
  const failedResultCount = latestResults.length - passedResultCount
  const filteredResults = useMemo(
    () => latestResults.filter((result) => {
      if (diagnosticCategory !== 'ALL' && (result.category ?? 'REAL_QUERY') !== diagnosticCategory) return false
      if (diagnosticStatus === 'PASS' && !result.passed) return false
      if (diagnosticStatus === 'FAIL' && result.passed) return false
      if (diagnosticFailureCategory !== 'ALL' && !(result.failureCategories ?? []).includes(diagnosticFailureCategory)) {
        return false
      }
      return true
    }),
    [diagnosticCategory, diagnosticFailureCategory, diagnosticStatus, latestResults],
  )
  const latestGateDecisions = Object.entries(latestRun?.gateSummary ?? {})
    .filter((entry): entry is [string, RagEvalGateDecision] => Boolean(entry[1]))
  const canSave = form.caseKey.trim().length > 0 && form.query.trim().length > 0
  const runSummary = useMemo(
    () => runs.map((run) => ({
      ...run,
      passed: run.status === 'COMPLETED' && run.passedCount === run.totalCount && run.totalCount > 0,
    })),
    [runs],
  )

  const updateForm = <K extends keyof RagEvalCaseRequest>(key: K, value: RagEvalCaseRequest[K]) => {
    setForm((current) => ({ ...current, [key]: value }))
  }

  const toggleProfile = (profile: RetrievalIndexMode, checked: boolean) => {
    setSelectedProfiles((current) => {
      if (checked) return current.includes(profile) ? current : [...current, profile]
      const next = current.filter((item) => item !== profile)
      return next.length > 0 ? next : current
    })
  }

  const evalRunRequest = (): RagEvalRunRequest => {
    if (selectedRetrievalProfileId) {
      return { profileId: selectedRetrievalProfileId }
    }
    const request: RagEvalRunRequest = { useRerank }
    if (!(selectedProfiles.length === 1 && selectedProfiles[0] === 'CLASSIC')) {
      request.profiles = selectedProfiles
    }
    const label = experimentLabel.trim()
    if (label) request.experimentLabel = label
    const topKValue = Number(topKOverride)
    if (topKOverride.trim() && Number.isFinite(topKValue)) {
      request.topKOverride = Math.min(50, Math.max(1, Math.trunc(topKValue)))
    }
    return request
  }

  const resetForm = () => {
    setForm(EMPTY_FORM)
    setExpectedFileNamesInput('')
    setMustContainAnyInput('')
    setEditingId(null)
  }

  const editCase = (caseDef: RagEvalCase) => {
    setEditingId(caseDef.id)
    setExpectedFileNamesInput((caseDef.expectedFileNames ?? []).join(', '))
    setMustContainAnyInput((caseDef.mustContainAny ?? []).join(', '))
    setForm({
      caseKey: caseDef.caseKey ?? caseDef.id,
      query: caseDef.query,
      minHits: caseDef.minHits,
      topK: caseDef.topK ?? 5,
      category: caseDef.category ?? 'REAL_QUERY',
      expectedFileName: caseDef.expectedFileName ?? '',
      expectedDocumentId: caseDef.expectedDocumentId,
      expectedFileNames: caseDef.expectedFileNames ?? [],
      expectedDocumentIds: caseDef.expectedDocumentIds ?? [],
      mustContainAny: caseDef.mustContainAny ?? [],
    })
  }

  const saveCase = async () => {
    if (!canSave) return
    setSaving(true)
    const selectedDocumentIds = Array.from(new Set([
      form.expectedDocumentId,
      ...(form.expectedDocumentIds ?? []),
    ].filter((id): id is string => Boolean(id))))
    const selectedDocuments = selectedDocumentIds
      .map((id) => sourceDocuments.find((document) => document.id === id))
      .filter((document): document is Document => Boolean(document))
    if (selectedDocuments.length !== selectedDocumentIds.length) {
      setSaving(false)
      showError('所选来源文档已不可用，请重新选择')
      return
    }
    const request: RagEvalCaseRequest = {
      caseKey: form.caseKey.trim(),
      query: form.query.trim(),
      minHits: Math.max(0, Number(form.minHits) || 0),
      topK: Math.max(1, Number(form.topK) || 5),
      category: form.category ?? 'REAL_QUERY',
      mustContainAny: mustContainAnyInput.split(',').map((token) => token.trim()).filter(Boolean),
      ...(selectedDocuments.length === 1 ? {
        expectedDocumentId: selectedDocuments[0].id,
        expectedFileName: selectedDocuments[0].fileName,
      } : selectedDocuments.length > 1 ? {
        expectedDocumentIds: selectedDocuments.map((document) => document.id),
        expectedFileNames: selectedDocuments.map((document) => document.fileName),
      } : {
        expectedFileName: form.expectedFileName?.trim() || undefined,
        expectedFileNames: expectedFileNamesInput.split(',').map((fileName) => fileName.trim()).filter(Boolean),
      }),
    }
    try {
      const saved = editingId
        ? await updateRagEvalCase(kbId, editingId, request)
        : await createRagEvalCase(kbId, request)
      setCases((current) =>
        editingId
          ? current.map((caseDef) => (caseDef.id === editingId ? saved : caseDef))
          : [...current, saved],
      )
      resetForm()
      showSuccess(editingId ? '评估用例已更新' : '评估用例已创建')
    } catch (error) {
      showError(error instanceof Error ? error.message : '评估用例保存失败')
    } finally {
      setSaving(false)
    }
  }

  const removeCase = async (caseDef: RagEvalCase) => {
    try {
      await deleteRagEvalCase(kbId, caseDef.id)
      setCases((current) => current.filter((item) => item.id !== caseDef.id))
      if (editingId === caseDef.id) resetForm()
      showSuccess('评估用例已删除')
    } catch (error) {
      showError(error instanceof Error ? error.message : '评估用例删除失败')
    }
  }

  const run = async () => {
    if (invalidCases.length > 0) {
      showError('存在引用已删除文档的失效用例，请先重新生成')
      return
    }
    setRunning(true)
    try {
      const nextRun = await runRagEval(kbId, evalRunRequest())
      setRuns((current) => [nextRun, ...current.filter((item) => item.id !== nextRun.id)].slice(0, 10))
      if (nextRun.totalCount === 0) {
        showError('请先创建至少一个评估用例')
      } else if (selectedRetrievalProfile && (nextRun.gateStatus === 'PASS'
          || (nextRun.gateStatus === 'UNBASELINED' && policy?.blockWhenUnbaselined === false))) {
        showSuccess(nextRun.gateStatus === 'UNBASELINED'
          ? `方案 v${selectedRetrievalProfile.version} 未设置基线，但当前策略允许激活`
          : `方案 v${selectedRetrievalProfile.version} 评估通过，现在可以激活`)
      } else if (nextRun.gateStatus === 'PASS') {
        showSuccess(`RAG 评估通过：${nextRun.passedCount}/${nextRun.totalCount}`)
      } else if (nextRun.passedCount === nextRun.totalCount) {
        showError(`用例已通过，但质量门禁为 ${nextRun.gateStatus ?? '未通过'}，暂不能激活方案`)
      } else {
        showError(`RAG 评估未通过：${nextRun.passedCount}/${nextRun.totalCount}`)
      }
    } catch (error) {
      showError(error instanceof Error ? error.message : 'RAG 评估运行失败')
    } finally {
      setRunning(false)
    }
  }

  const previewGeneration = async () => {
    setGenerating(true)
    setGenerationError(null)
    try {
      setGenerationPreview(await previewRagEvalCaseGeneration(kbId))
    } catch (error) {
      const message = error instanceof Error ? error.message : 'AI 用例预览生成失败'
      setGenerationError(message)
      showError(message)
    } finally {
      setGenerating(false)
    }
  }

  const confirmGeneration = async () => {
    if (!generationPreview?.confirmable) return
    setGenerating(true)
    try {
      const nextCases = await confirmRagEvalCaseGeneration(kbId, {
        documentFingerprint: generationPreview.documentFingerprint,
        caseFingerprint: generationPreview.caseFingerprint,
        replaceCaseIds: generationPreview.replacedCases.map((caseDef) => caseDef.id),
        generatedCases: generationPreview.documents.flatMap((document) => document.proposals.map((proposal) => ({
          ...proposal,
          expectedDocumentId: proposal.expectedDocumentId ?? document.documentId,
        }))),
      })
      setCases(nextCases)
      setGenerationPreview(null)
      showSuccess('评估用例已按当前知识库更新')
    } catch (error) {
      showError(error instanceof Error ? error.message : '评估用例更新失败，请重新生成预览')
    } finally {
      setGenerating(false)
    }
  }

  const openAddGeneration = async () => {
    if (addPanelOpen) {
      setAddPanelOpen(false)
      setAddGenerationPreview(null)
      setAddGenerationError(null)
      return
    }
    setAddPanelOpen(true)
    setAddDocumentsLoading(true)
    setAddGenerationError(null)
    try {
      const documents = (await listDocuments(kbId)).filter((document) => document.status === 'COMPLETED')
      setAddDocuments(documents)
      setSelectedAddDocumentIds((current) => current.filter((id) => documents.some((document) => document.id === id)))
    } catch (error) {
      setAddGenerationError(error instanceof Error ? error.message : '知识库文档加载失败')
    } finally {
      setAddDocumentsLoading(false)
    }
  }

  const toggleAddDocument = (documentId: string, checked: boolean) => {
    setSelectedAddDocumentIds((current) => checked
      ? Array.from(new Set([...current, documentId]))
      : current.filter((id) => id !== documentId))
    setAddGenerationPreview(null)
  }

  const previewAddGeneration = async () => {
    if (selectedAddDocumentIds.length === 0) return
    setAddGenerating(true)
    setAddGenerationError(null)
    try {
      setAddGenerationPreview(await previewAddRagEvalCases(kbId, {
        documentIds: selectedAddDocumentIds,
        casesPerDocument: addCasesPerDocument,
      }))
    } catch (error) {
      setAddGenerationError(error instanceof Error ? error.message : 'AI 新增用例预览生成失败')
    } finally {
      setAddGenerating(false)
    }
  }

  const confirmAddGeneration = async () => {
    if (!addGenerationPreview?.confirmable) return
    setAddGenerating(true)
    setAddGenerationError(null)
    try {
      const nextCases = await confirmAddRagEvalCases(kbId, {
        documentFingerprint: addGenerationPreview.documentFingerprint,
        caseFingerprint: addGenerationPreview.caseFingerprint,
        documentIds: selectedAddDocumentIds,
        casesPerDocument: addCasesPerDocument,
        generatedCases: addGenerationPreview.documents.flatMap((document) => document.proposals.map((proposal) => ({
          ...proposal,
          expectedDocumentId: proposal.expectedDocumentId ?? document.documentId,
        }))),
      })
      setCases(nextCases)
      setAddGenerationPreview(null)
      setAddPanelOpen(false)
      setSelectedAddDocumentIds([])
      showSuccess('AI 评估用例已新增')
    } catch (error) {
      const message = error instanceof Error ? error.message : 'AI 评估用例新增失败，请重新生成预览'
      setAddGenerationError(message)
      showError(message)
    } finally {
      setAddGenerating(false)
    }
  }

  const savePolicy = async () => {
    if (!policy) return
    setSaving(true)
    try {
      setPolicy(await updateRagQualityPolicy(kbId, policy))
      showSuccess('质量策略已更新')
    } catch (error) { showError(error instanceof Error ? error.message : '质量策略更新失败') }
    finally { setSaving(false) }
  }

  const promoteBaseline = async (runId: string) => {
    setSaving(true)
    try {
      setPolicy(await promoteRagEvalBaseline(kbId, runId))
      showSuccess('评测运行已设为基线')
    } catch (error) { showError(error instanceof Error ? error.message : '基线设置失败') }
    finally { setSaving(false) }
  }

  return (
    <div className="mx-auto max-w-6xl space-y-6 px-4 py-6 md:px-8">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div>
          <h2 className="text-base font-semibold">RAG 评估</h2>
          <p className="mt-1 text-sm text-muted-foreground">管理可重复运行的检索用例，并对比最近评估结果。</p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            <span className="font-medium text-foreground">评估方案</span>
            <select
              aria-label="评估方案"
              value={selectedRetrievalProfileId}
              onChange={(event) => setSelectedRetrievalProfileId(event.target.value)}
              className="h-10 min-w-56 rounded-md border border-input bg-background px-3 text-sm text-foreground"
            >
              <option value="">当前默认策略（常规评估，不可用于激活）</option>
              {retrievalProfiles.map((profile) => (
                <option key={profile.id} value={profile.id}>v{profile.version} · {profile.name}</option>
              ))}
            </select>
          </label>
          {selectedRetrievalProfile ? (
            <div className="max-w-md rounded-lg border border-primary/30 bg-primary/5 px-3 py-2 text-xs">
              <p className="font-medium text-foreground">正在评估 v{selectedRetrievalProfile.version} · {selectedRetrievalProfile.name}</p>
              <p className="mt-1 text-muted-foreground">将使用该方案保存的候选数、Rerank 和 TopK 参数；本次结果可作为激活依据。</p>
              <Badge className="mt-2" variant={selectedProfileCanActivate ? 'success' : 'muted'}>
                {selectedProfileCanActivate
                  ? (selectedProfileHasUnbaselinedGate ? '未设基线，但策略允许激活' : '已通过门禁，可激活')
                  : '尚未通过门禁'}
              </Badge>
            </div>
          ) : (
            <>
              <div className="inline-flex items-center gap-1">
                <label className="inline-flex items-center gap-2 text-sm">
                  <input
                    name="useRerank"
                    type="checkbox"
                    checked={useRerank}
                    onChange={(event) => setUseRerank(event.target.checked)}
                    className="h-4 w-4 accent-primary"
                  />
                  启用 Rerank
                </label>
                <TermHelp term="Rerank" align="left" />
              </div>
              <div className="flex flex-wrap items-center gap-2 text-xs" aria-label="Retrieval profiles">
                <TermHelp term="检索 Profile" align="left" />
                {RETRIEVAL_PROFILE_OPTIONS.map((profile) => (
                  <label key={profile.value} className="inline-flex items-center gap-1 rounded border border-border px-2 py-1">
                    <input
                      name={`profile-${profile.value}`}
                      type="checkbox"
                      checked={selectedProfiles.includes(profile.value)}
                      onChange={(event) => toggleProfile(profile.value, event.target.checked)}
                      className="h-3 w-3 accent-primary"
                    />
                    {profile.label}
                  </label>
                ))}
              </div>
              <Input
                name="experimentLabel"
                value={experimentLabel}
                onChange={(event) => setExperimentLabel(event.target.value)}
                placeholder="Experiment label"
                aria-label="Experiment label"
                className="w-44"
              />
              <Input
                name="topKOverride"
                type="number"
                min={1}
                max={50}
                value={topKOverride}
                onChange={(event) => setTopKOverride(event.target.value)}
                placeholder="TopK override"
                aria-label="TopK override"
                className="w-32"
              />
              <TermHelp term="TopK override" />
            </>
          )}
          <Button onClick={run} disabled={running || loading || cases.length === 0 || invalidCases.length > 0}>
            {running ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
            {selectedRetrievalProfile ? '运行方案评估' : '运行常规评估'}
          </Button>
        </div>
      </div>

      {invalidCases.length > 0 && (
        <section className="rounded-lg border border-destructive/40 bg-destructive/5 p-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h3 className="text-sm font-semibold">发现 {invalidCases.length} 条失效评估用例</h3>
              <p className="mt-1 text-xs text-muted-foreground">
                引用的文档已不在当前知识库中，运行评估已暂停。系统只会替换失效用例，并为当前文档补足每篇 2 条用例。
              </p>
            </div>
            <Button size="sm" onClick={() => void previewGeneration()} disabled={generating}>
              {generating && <Loader2 className="h-4 w-4 animate-spin" />}
              {generating ? '正在生成（约 1 分钟）' : 'AI 修复失效用例'}
            </Button>
          </div>
          <ul className="mt-3 space-y-1 text-xs text-muted-foreground">
            {invalidCases.map((caseDef) => (
              <li key={caseDef.id}>{caseDef.caseKey ?? caseDef.id}：缺少 {(caseDef.missingExpectedFileNames ?? []).join('、') || '有效来源文档'}</li>
            ))}
          </ul>
          {generationError && <p className="mt-3 text-xs text-destructive">生成失败：{generationError}</p>}
        </section>
      )}

      {generationPreview && (
        <section className="rounded-lg border border-border p-4" aria-label="AI 评估用例预览">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h3 className="text-sm font-semibold">AI 评估用例预览</h3>
              <p className="mt-1 text-xs text-muted-foreground">将替换 {generationPreview.replacedCases.length} 条失效用例，保留 {generationPreview.retainedCases.length} 条有效用例。</p>
            </div>
            <div className="flex gap-2">
              <Button variant="ghost" size="sm" onClick={() => setGenerationPreview(null)}>取消</Button>
              <Button size="sm" onClick={() => void confirmGeneration()} disabled={generating || !generationPreview.confirmable}>
                {generating && <Loader2 className="h-4 w-4 animate-spin" />}
                确认替换
              </Button>
            </div>
          </div>
          <div className="mt-4 grid gap-3 md:grid-cols-2">
            {generationPreview.documents.map((document) => (
              <article key={document.documentId} className="rounded border border-border p-3 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-semibold">{document.fileName}</span>
                  <Badge variant={document.error ? 'error' : 'muted'}>{document.covered ? '已有 2 条' : `新增 ${document.proposals.length} 条`}</Badge>
                </div>
                {document.error ? <p className="mt-2 text-destructive">{document.error}</p> : document.proposals.map((draft) => (
                  <div key={draft.caseKey} className="mt-3 border-t border-border pt-2">
                    <p className="font-medium">{draft.query}</p>
                    <p className="mt-1 text-muted-foreground">期望证据词：{draft.mustContainAny.join('、')}</p>
                  </div>
                ))}
              </article>
            ))}
          </div>
          {!generationPreview.confirmable && <p className="mt-3 text-xs text-destructive">部分文档生成失败，请重新生成预览后再确认。</p>}
        </section>
      )}

      {policy && <section className="border-y border-border py-4">
        <div className="grid gap-3 md:grid-cols-5">
          <div className="text-xs text-muted-foreground"><div className="flex items-center justify-between gap-1"><label htmlFor="minimum-pass-rate">最低通过率</label><TermHelp term="最低通过率" /></div><Input id="minimum-pass-rate" type="number" min={0} max={100} value={policy.minimumPassRate} onChange={(e) => setPolicy({ ...policy, minimumPassRate: Number(e.target.value) })} /></div>
          <div className="text-xs text-muted-foreground"><div className="flex items-center justify-between gap-1"><label htmlFor="maximum-pass-rate-drop">最大通过率下降</label><TermHelp term="最大通过率下降" /></div><Input id="maximum-pass-rate-drop" type="number" min={0} max={100} value={policy.maximumPassRateDrop} onChange={(e) => setPolicy({ ...policy, maximumPassRateDrop: Number(e.target.value) })} /></div>
          <div className="text-xs text-muted-foreground"><div className="flex items-center justify-between gap-1"><label htmlFor="maximum-new-failures">最大新增失败</label><TermHelp term="最大新增失败" /></div><Input id="maximum-new-failures" type="number" min={0} value={policy.maximumNewFailures} onChange={(e) => setPolicy({ ...policy, maximumNewFailures: Number(e.target.value) })} /></div>
          <div className="flex items-center gap-1 pt-5"><label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={policy.blockWhenUnbaselined} onChange={(e) => setPolicy({ ...policy, blockWhenUnbaselined: e.target.checked })} />无基线时阻断</label><TermHelp term="无基线时阻断" /></div>
          <Button className="self-end" onClick={() => void savePolicy()} disabled={saving}><Save className="h-4 w-4" />保存策略</Button>
        </div>
      </section>}

      {latestGateDecisions.length > 0 && (
        <section className="border-y border-border py-4">
          <h3 className="mb-3 flex items-center gap-1 text-sm font-semibold">Profile quality gates <TermHelp term="Profile quality gates" /></h3>
          <div className="grid gap-3 md:grid-cols-2">
            {latestGateDecisions.map(([profile, decision]) => (
              <div key={profile} className="rounded border border-border p-3 text-xs">
                <div className="flex items-center justify-between gap-3">
                  <span className="font-mono font-semibold">{profile} {decision.status}</span>
                  <Badge variant={decision.status === 'PASSED' ? 'success' : 'error'}>
                    {decision.reason}
                  </Badge>
                </div>
                <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-muted-foreground">
                  <span>Hit {formatPercent(decision.metrics?.hitRate)}</span>
                  <span>Citation {formatPercent(decision.metrics?.citationPassRate)}</span>
                  <span>Hit delta {formatDelta(decision.hitRateDelta)}</span>
                  <span>Citation delta {formatDelta(decision.citationPassRateDelta)}</span>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}

      {(releaseGate || qualitySystemCards.length > 0 || categorySummaryEntries.length > 0 || profileComparisonEntries.length > 0) && (
        <section className="border-y border-border py-4">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
            <h3 className="flex items-center gap-1 text-sm font-semibold">Quality dashboard <TermHelp term="Quality dashboard" /></h3>
            {latestRun?.profileSnapshot?.experimentLabel && (
              <Badge variant="muted">Experiment {latestRun.profileSnapshot.experimentLabel}</Badge>
            )}
          </div>
          {qualitySystemCards.length > 0 && (
            <div className="mb-4 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
              {qualitySystemCards.map((card) => (
                <div key={card.title} className="rounded border border-border p-3 text-xs">
                  <div className="mb-2 flex items-center justify-between gap-2">
                    <span className="font-semibold">{card.title}</span>
                    <span className="flex items-center gap-1"><Badge variant="muted">{card.version}</Badge><TermHelp term={card.title} /></span>
                  </div>
                  <p className="text-sm font-semibold">{card.value}</p>
                  <p className="mt-1 text-muted-foreground">{card.detail}</p>
                </div>
              ))}
            </div>
          )}
          {releaseGate && (
            <div className="mb-4 rounded border border-border p-3 text-xs">
              <div className="flex flex-wrap items-center gap-3">
                 <span className="flex items-center gap-1 font-semibold">Release gate rollup <TermHelp term="Release gate rollup" /></span>
                <Badge variant={releaseGate.status === 'PASS' ? 'success' : 'error'}>{releaseGate.status ?? 'UNKNOWN'}</Badge>
                <span>Pass {releaseGate.passed ?? latestRun?.passedCount ?? 0}/{releaseGate.total ?? latestRun?.totalCount ?? 0}</span>
                <span>{formatPercent(releaseGate.passRate)}</span>
              </div>
              <p className="mt-2 text-muted-foreground">
                Category blockers {(releaseGate.categoryBlockers ?? []).join(', ') || '-'} · Profile blockers {(releaseGate.profileGateBlockers ?? []).join(', ') || '-'}
              </p>
            </div>
          )}
          {categorySummaryEntries.length > 0 && (
            <div className="mb-4">
              <h4 className="mb-2 flex items-center gap-1 text-xs font-semibold uppercase text-muted-foreground">Category summaries / trend <TermHelp term="Category summaries / trend" /></h4>
              <div className="grid gap-2 md:grid-cols-2 xl:grid-cols-4">
                {categorySummaryEntries.map(([category, summary]) => (
                  <div key={category} className="rounded border border-border p-3 text-xs">
                    <p className="font-mono font-semibold">{category}</p>
                    <p className="mt-1">Pass {summary.passed ?? 0}/{summary.total ?? 0} ({formatPercent(summary.passRate)})</p>
                    <p className="text-muted-foreground">Hit {formatPercent(summary.hitPassRate ?? summary.hitRate)} · Citation {formatPercent(summary.citationPassRate ?? summary.citationRate)}</p>
                    <p className="text-muted-foreground">Trend {runs.slice(0, 5).map((run) => {
                      const item = run.metrics?.categorySummaries?.[category]
                      return item ? `${item.passed ?? 0}/${item.total ?? 0}` : '-'
                    }).join(' → ')}</p>
                  </div>
                ))}
              </div>
            </div>
          )}
          {profileComparisonEntries.length > 0 && (
            <div className="rounded border border-border">
              <div className="flex items-center gap-1 border-b border-border px-3 py-2 text-xs font-semibold">
                Profile A/B comparison <TermHelp term="Profile A/B comparison" />
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead className="border-b bg-muted/50 text-muted-foreground">
                    <tr>
                      <th className="px-3 py-2 text-left">Profiles</th>
                      <th className="px-3 py-2 text-left">Pass delta</th>
                      <th className="px-3 py-2 text-left">Hit delta</th>
                      <th className="px-3 py-2 text-left">Citation delta</th>
                      <th className="px-3 py-2 text-left">P95 delta</th>
                    </tr>
                  </thead>
                  <tbody>
                    {profileComparisonEntries.map(([profile, comparison]) => (
                      <tr key={profile} className="border-b last:border-0">
                        <td className="px-3 py-2 font-mono">{comparison.candidate ?? profile} vs {comparison.baseline ?? 'classic'}</td>
                        <td className="px-3 py-2">{formatDelta(comparison.passRateDelta)}</td>
                        <td className="px-3 py-2">{formatDelta(comparison.hitRateDelta)}</td>
                        <td className="px-3 py-2">{formatDelta(comparison.citationRateDelta)}</td>
                        <td className="px-3 py-2">{comparison.latencyP95MsDelta ?? 0} ms</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </section>
      )}

      <section className="border-y border-border py-5">
        <div className="mb-4 flex items-center justify-between gap-3">
          <div>
            <h3 className="text-sm font-semibold">评估用例</h3>
            <p className="mt-1 text-xs text-muted-foreground">来源文件和期望证据词均为可选校验条件；多个证据词任一命中即可。</p>
          </div>
          <div className="flex items-center gap-2">
            <Badge variant="muted">{cases.length} 条</Badge>
            <Button size="sm" onClick={() => void openAddGeneration()} disabled={addDocumentsLoading || addGenerating}>
              {addDocumentsLoading && <Loader2 className="h-4 w-4 animate-spin" />}
              {addPanelOpen ? '收起 AI 新增' : 'AI 新增用例'}
            </Button>
          </div>
        </div>

        {addPanelOpen && (
          <div className="mb-4 rounded-lg border border-border bg-muted/20 p-4" aria-label="AI 新增评估用例">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h4 className="text-sm font-semibold">AI 新增评估用例</h4>
                <p className="mt-1 text-xs text-muted-foreground">选择已完成文档并生成预览；确认后只会追加，不会替换现有用例。</p>
              </div>
              <label className="space-y-1 text-xs text-muted-foreground">
                <span>每篇生成数量</span>
                <Input
                  name="addCasesPerDocument"
                  type="number"
                  min={1}
                  max={5}
                  value={addCasesPerDocument}
                  onChange={(event) => {
                    setAddCasesPerDocument(Math.min(5, Math.max(1, Number(event.target.value) || 1)))
                    setAddGenerationPreview(null)
                  }}
                  className="w-28"
                />
              </label>
            </div>

            {addDocumentsLoading ? (
              <p className="mt-4 text-sm text-muted-foreground">正在加载知识库文档...</p>
            ) : addDocuments.length === 0 ? (
              <p className="mt-4 text-sm text-muted-foreground">当前知识库没有可用于生成用例的已完成文档。</p>
            ) : (
              <div className="mt-4 grid gap-2 md:grid-cols-2">
                {addDocuments.map((document) => (
                  <label key={document.id} className="flex items-center gap-2 rounded border border-border bg-background px-3 py-2 text-sm">
                    <input
                      name={`addDocument-${document.id}`}
                      type="checkbox"
                      checked={selectedAddDocumentIds.includes(document.id)}
                      onChange={(event) => toggleAddDocument(document.id, event.target.checked)}
                    />
                    <span className="truncate">{document.fileName}</span>
                  </label>
                ))}
              </div>
            )}

            <div className="mt-4 flex flex-wrap items-center justify-end gap-2">
              <span className="mr-auto text-xs text-muted-foreground">已选择 {selectedAddDocumentIds.length} 篇文档</span>
              <Button variant="ghost" size="sm" onClick={() => void openAddGeneration()}>取消</Button>
              <Button size="sm" onClick={() => void previewAddGeneration()} disabled={addGenerating || selectedAddDocumentIds.length === 0}>
                {addGenerating && <Loader2 className="h-4 w-4 animate-spin" />}
                {addGenerating ? '正在生成（约 1 分钟）' : '生成预览'}
              </Button>
            </div>

            {addGenerationError && <p className="mt-3 text-xs text-destructive">生成失败：{addGenerationError}</p>}

            {addGenerationPreview && (
              <div className="mt-4 border-t border-border pt-4">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <h5 className="text-sm font-semibold">AI 新增用例预览</h5>
                    <p className="mt-1 text-xs text-muted-foreground">将新增 {addGenerationPreview.documents.reduce((sum, document) => sum + document.proposals.length, 0)} 条用例，现有 {cases.length} 条保持不变。</p>
                  </div>
                  <div className="flex gap-2">
                    <Button variant="ghost" size="sm" onClick={() => setAddGenerationPreview(null)}>取消预览</Button>
                    <Button size="sm" onClick={() => void confirmAddGeneration()} disabled={addGenerating || !addGenerationPreview.confirmable}>
                      {addGenerating && <Loader2 className="h-4 w-4 animate-spin" />}
                      确认新增
                    </Button>
                  </div>
                </div>
                <div className="mt-3 grid gap-3 md:grid-cols-2">
                  {addGenerationPreview.documents.map((document) => (
                    <article key={document.documentId} className="rounded border border-border bg-background p-3 text-xs">
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-semibold">{document.fileName}</span>
                        <Badge variant={document.error ? 'error' : 'muted'}>新增 {document.proposals.length} 条</Badge>
                      </div>
                      {document.error && <p className="mt-2 text-destructive">{document.error}</p>}
                      {document.proposals.map((proposal) => (
                        <div key={proposal.caseKey} className="mt-2 border-t border-border pt-2">
                          <p className="font-medium text-foreground">{proposal.query}</p>
                          <p className="mt-1 text-muted-foreground">期望证据词：{proposal.mustContainAny.join('、')}</p>
                        </div>
                      ))}
                    </article>
                  ))}
                </div>
                {!addGenerationPreview.confirmable && <p className="mt-3 text-xs text-destructive">部分文档生成失败，请重新生成预览后再确认。</p>}
              </div>
            )}
          </div>
        )}

        <div className="rounded-lg border border-border bg-muted/20 p-3">
          <p className="mb-3 text-xs font-medium text-foreground">{editingId ? '编辑评估用例' : '添加评估用例'}</p>
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <label className="space-y-1 text-xs text-muted-foreground">
              <span>用例标识</span>
              <Input name="caseKey" value={form.caseKey} onChange={(event) => updateForm('caseKey', event.target.value)} placeholder="例如：虚拟环境创建" />
            </label>
            <label className="space-y-1 text-xs text-muted-foreground md:col-span-2">
              <span>检索问题</span>
              <Input name="query" value={form.query} onChange={(event) => updateForm('query', event.target.value)} placeholder="输入要验证的用户问题" />
            </label>
            <label className="space-y-1 text-xs text-muted-foreground">
              <span>场景类型</span>
              <select name="category" value={form.category ?? 'REAL_QUERY'} onChange={(event) => updateForm('category', event.target.value as RagEvalCaseCategory)} className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm text-foreground">
                {CASE_CATEGORY_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </select>
            </label>
            <label className="space-y-1 text-xs text-muted-foreground">
              <span>主要来源文件（可选）</span>
              <Input name="expectedFileName" readOnly={Boolean(form.expectedDocumentId)} value={form.expectedFileName ?? ''} onChange={(event) => updateForm('expectedFileName', event.target.value)} placeholder="例如：教程.md" />
            </label>
            <label className="space-y-1 text-xs text-muted-foreground">
              <span>主要来源文档（稳定 ID）</span>
              <select
                name="expectedDocumentId"
                value={form.expectedDocumentId ?? ''}
                onChange={(event) => {
                  const document = sourceDocuments.find((item) => item.id === event.target.value)
                  setForm((current) => ({
                    ...current,
                    expectedDocumentId: document?.id,
                    expectedFileName: document?.fileName ?? current.expectedFileName,
                  }))
                }}
                className="h-9 w-full rounded-md border border-input bg-background px-3 text-sm text-foreground"
              >
                <option value="">未选择</option>
                {sourceDocuments.map((document) => <option key={document.id} value={document.id}>{document.fileName}</option>)}
              </select>
            </label>
            <label className="space-y-1 text-xs text-muted-foreground md:col-span-2">
              <span>附加来源文件（可选）</span>
              <Input name="expectedFileNames" readOnly={Boolean(form.expectedDocumentIds?.length)} value={expectedFileNamesInput} onChange={(event) => setExpectedFileNamesInput(normalizeCommaSeparatedInput(event.target.value))} placeholder="多个文件请用逗号分隔" />
            </label>
            <label className="space-y-1 text-xs text-muted-foreground md:col-span-2">
              <span>附加来源文档（稳定 ID，可多选）</span>
              <select
                name="expectedDocumentIds"
                multiple
                value={form.expectedDocumentIds ?? []}
                onChange={(event) => {
                  const ids = Array.from(event.target.selectedOptions, (option) => option.value)
                  setForm((current) => ({ ...current, expectedDocumentIds: ids }))
                  setExpectedFileNamesInput(sourceDocuments.filter((document) => ids.includes(document.id)).map((document) => document.fileName).join(', '))
                }}
                className="min-h-20 w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground"
              >
                {sourceDocuments.map((document) => <option key={document.id} value={document.id}>{document.fileName}</option>)}
              </select>
            </label>
            <label className="space-y-1 text-xs text-muted-foreground">
              <span>期望证据词（可选，任一命中即可）</span>
              <Input name="mustContainAny" value={mustContainAnyInput} onChange={(event) => setMustContainAnyInput(normalizeCommaSeparatedInput(event.target.value))} placeholder="填写期望召回内容包含的词，多个请用逗号分隔" />
            </label>
            <label className="space-y-1 text-xs text-muted-foreground">
              <span>最少命中数量</span>
              <Input name="minHits" type="number" min={0} value={form.minHits} onChange={(event) => updateForm('minHits', Number(event.target.value))} />
            </label>
            <label className="space-y-1 text-xs text-muted-foreground">
              <span>最多返回片段数</span>
              <Input name="topK" type="number" min={1} value={form.topK ?? 5} onChange={(event) => updateForm('topK', Number(event.target.value))} />
            </label>
          </div>
        </div>
        <div className="mt-3 flex justify-end gap-2">
          {editingId && (
            <Button variant="ghost" size="sm" onClick={resetForm}>
              <X className="h-4 w-4" />
              取消编辑
            </Button>
          )}
          <Button size="sm" onClick={saveCase} disabled={!canSave || saving}>
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
            保存用例
          </Button>
        </div>

        <div className="mt-4 overflow-x-auto rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
              <tr>
                <th className="px-3 py-2 text-left font-medium">标识 / 问题</th>
                <th className="px-3 py-2 text-left font-medium">判定条件</th>
                <th className="w-20 px-3 py-2 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={3} className="px-3 py-8 text-center text-muted-foreground">正在加载评估用例...</td></tr>
              ) : cases.length === 0 ? (
                <tr><td colSpan={3} className="px-3 py-8 text-center text-muted-foreground">暂无评估用例</td></tr>
              ) : cases.map((caseDef) => (
                <tr key={caseDef.id} className="border-b last:border-0">
                  <td className="px-3 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="font-mono text-xs font-semibold">{caseDef.caseKey ?? caseDef.id}</p>
                      <Badge variant="muted">{caseCategoryLabel(caseDef.category)}</Badge>
                      {caseDef.sourceValid === false && <Badge variant="error">来源失效</Badge>}
                    </div>
                    <p className="mt-1 max-w-xl text-xs text-muted-foreground">{caseDef.query}</p>
                  </td>
                  <td className="px-3 py-3 text-xs text-muted-foreground">
                    <p>命中 ≥ {caseDef.minHits} · TopK {caseDef.topK ?? 5}</p>
                    <p className="mt-1">文件 {[caseDef.expectedFileName, ...(caseDef.expectedFileNames ?? [])].filter(Boolean).join(', ') || '-'} · 期望证据词 {(caseDef.mustContainAny ?? []).join(', ') || '-'}</p>
                  </td>
                  <td className="px-3 py-3">
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" size="sm" onClick={() => editCase(caseDef)} aria-label={`Edit eval case ${caseDef.caseKey ?? caseDef.id}`} title="编辑用例">
                        <Pencil className="h-4 w-4" />
                      </Button>
                      <Button variant="ghost" size="sm" onClick={() => void removeCase(caseDef)} aria-label={`Delete eval case ${caseDef.caseKey ?? caseDef.id}`} title="删除用例">
                        <Trash2 className="h-4 w-4 text-destructive" />
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <div className="mb-3 flex items-center justify-between gap-3">
          <h3 className="text-sm font-semibold">最近运行</h3>
          {latestRun && <span className="text-xs text-muted-foreground">最新 {latestRun.passedCount}/{latestRun.totalCount}</span>}
        </div>
        {runs.length === 0 ? (
          <p className="border-y border-border py-8 text-center text-sm text-muted-foreground">尚未运行评估</p>
        ) : (
          <div className="flex gap-2 overflow-x-auto pb-2">
            {runSummary.map((item) => {
              const failureCategoryEntries = Object.entries(item.metrics?.failureCategoryCounts ?? {})
                .filter(([, count]) => Number(count) > 0)
              const displayedGateStatus = item.gateStatus ?? item.metrics?.releaseGate?.status
              const displayedGateLabel = displayedGateStatus ? `门禁 ${displayedGateStatus}` : '门禁未评估'
              return (
              <div key={item.id} className="min-w-52 rounded border border-border px-3 py-2 text-xs">
                <div className="mb-2 flex items-center justify-between gap-2">
                  <Badge variant={displayedGateStatus === 'PASS' ? 'success' : displayedGateStatus === 'WARN' || displayedGateStatus === 'UNBASELINED' ? 'warning' : displayedGateStatus ? 'error' : 'muted'}>{displayedGateLabel}</Badge>
                  {item.gateStatus === 'PASS' && policy?.baselineRunId !== item.id && <Button size="sm" variant="ghost" onClick={() => void promoteBaseline(item.id)}>设为基线</Button>}
                </div>
                <p className="mb-2 font-mono text-muted-foreground">P95 {item.metrics?.latencyP95Ms ?? '-'} ms</p>
                <div className="flex items-center justify-between gap-3">
                  <span className="font-semibold">{item.passedCount}/{item.totalCount}</span>
                  <Badge variant={item.status === 'RUNNING' ? 'warning' : item.status === 'FAILED' ? 'error' : item.passed ? 'success' : 'error'}>
                    {item.status === 'RUNNING' ? '运行中' : item.status === 'FAILED' ? '失败' : item.passed ? '已完成 PASS' : '已完成 FAIL'}
                  </Badge>
                </div>
                <p className="mt-2 text-muted-foreground">{item.useRerank ? 'Rerank 已启用' : 'Rerank 未启用'}</p>
                {failureCategoryEntries.length > 0 && (
                  <div className="mt-2 rounded bg-muted/50 px-2 py-1">
                    <p className="font-semibold text-foreground">失败分类</p>
                    <div className="mt-1 space-y-1 text-muted-foreground">
                      {failureCategoryEntries.map(([category, count]) => (
                        <p key={category}>{failureCategoryLabel(category)} {count}</p>
                      ))}
                    </div>
                  </div>
                )}
                {item.failureMessage && <p className="mt-1 text-destructive">{item.failureMessage}</p>}
                <p className="mt-1 text-muted-foreground">{new Date(item.createdAt).toLocaleString()}</p>
              </div>
            )})}
          </div>
        )}
      </section>

      <section className="rounded-lg border border-border p-3">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <h3 className="flex items-center gap-1 text-sm font-semibold">结果明细筛选 <TermHelp term="Diagnostic drilldown" /></h3>
          <span className="text-xs text-muted-foreground">显示 {filteredResults.length}/{latestResults.length} 条结果</span>
        </div>
        <div className="grid gap-2 md:grid-cols-3">
          <label className="space-y-1 text-xs text-muted-foreground">
            <span>场景类型</span>
            <select name="diagnosticCategory" value={diagnosticCategory} onChange={(event) => setDiagnosticCategory(event.target.value as 'ALL' | RagEvalCaseCategory)} className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm text-foreground">
              <option value="ALL">全部场景（{latestResults.length}）</option>
              {availableCategoryOptions.map((option) => <option key={option.value} value={option.value}>{option.label}（{option.count}）</option>)}
            </select>
          </label>
          <label className="space-y-1 text-xs text-muted-foreground">
            <span>评估结果</span>
            <select
              name="diagnosticStatus"
              value={diagnosticStatus}
              onChange={(event) => {
                const status = event.target.value as 'ALL' | 'PASS' | 'FAIL'
                setDiagnosticStatus(status)
                if (status === 'PASS') setDiagnosticFailureCategory('ALL')
              }}
              className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm text-foreground"
            >
              <option value="ALL">全部结果（{latestResults.length}）</option>
              <option value="PASS" disabled={passedResultCount === 0}>通过（{passedResultCount}）</option>
              <option value="FAIL" disabled={failedResultCount === 0}>未通过（{failedResultCount}）</option>
            </select>
          </label>
          <label className="space-y-1 text-xs text-muted-foreground">
            <span>失败类型</span>
            <select
              name="diagnosticFailureCategory"
              value={diagnosticFailureCategory}
              onChange={(event) => {
                setDiagnosticFailureCategory(event.target.value)
                if (event.target.value !== 'ALL') setDiagnosticStatus('FAIL')
              }}
              disabled={diagnosticStatus === 'PASS' || failureCategoryOptions.length === 0}
              className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm text-foreground disabled:cursor-not-allowed disabled:opacity-60"
            >
              <option value="ALL">{failureCategoryOptions.length === 0 ? '当前没有失败类型' : '全部失败类型'}</option>
              {failureCategoryOptions.map((category) => <option key={category} value={category}>{failureCategoryLabel(category)}</option>)}
            </select>
          </label>
        </div>
      </section>

      <div className="overflow-x-auto rounded-lg border border-border bg-background">
        <table className="w-full text-sm">
          <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
            <tr>
              <th className="px-4 py-3 text-left font-medium">用例</th>
              <th className="px-4 py-3 text-left font-medium">结果</th>
              <th className="px-4 py-3 text-left font-medium">命中</th>
              <th className="px-4 py-3 text-left font-medium">文件</th>
              <th className="px-4 py-3 text-left font-medium">Token</th>
              <th className="px-4 py-3 text-left font-medium">模式</th>
              <th className="px-4 py-3 text-left font-medium">诊断</th>
            </tr>
          </thead>
          <tbody>
            {filteredResults.length === 0 ? (
              <tr><td colSpan={7} className="px-4 py-10 text-center text-muted-foreground">{latestResults.length === 0 ? '最新运行暂无结果' : '没有符合当前筛选条件的结果'}</td></tr>
            ) : filteredResults.map((result) => (
              <tr key={result.id} className="border-b last:border-0">
                <td className="px-4 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="font-mono text-xs">{result.caseKey ?? result.caseId ?? result.id}</p>
                    <Badge variant="muted">{caseCategoryLabel(result.category)}</Badge>
                  </div>
                  <p className="mt-1 max-w-xs text-xs text-muted-foreground">{result.query}</p>
                </td>
                <td className="px-4 py-3">
                  <span className="inline-flex items-center gap-1 font-mono text-xs font-semibold">
                    {result.passed ? <CheckCircle2 className="h-4 w-4 text-emerald-600" /> : <XCircle className="h-4 w-4 text-destructive" />}
                    {result.passed ? '通过' : '未通过'}
                  </span>
                </td>
                <td className="px-4 py-3 text-muted-foreground">{result.hitCount}</td>
                <td className="px-4 py-3">
                  <p className="font-mono text-xs">{(result.matchedFileNames ?? []).join(', ') || result.matchedFileName || '-'}</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    期望 {[result.expectedFileName, ...(result.expectedFileNames ?? [])].filter(Boolean).join(', ') || '-'}
                  </p>
                </td>
                <td className="px-4 py-3 text-muted-foreground">{result.matchedToken ?? '-'}</td>
                <td className="px-4 py-3">
                  <p className="font-mono text-xs">{result.retrievalMode ?? '-'}</p>
                  <p className="mt-1 text-xs text-muted-foreground">dim {result.embeddingDimension ?? '-'}</p>
                </td>
                <td className="max-w-sm px-4 py-3 text-xs text-muted-foreground">
                  {result.failureReasons.length > 0 ? result.failureReasons.join('；') : result.fallbackReason ?? '-'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
