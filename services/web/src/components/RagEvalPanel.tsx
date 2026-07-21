import { useEffect, useMemo, useState } from 'react'
import {
  createRagEvalCase,
  deleteRagEvalCase,
  listRagEvalCases,
  listRagEvalRuns,
  getRagQualityPolicy,
  promoteRagEvalBaseline,
  runRagEval,
  updateRagQualityPolicy,
  updateRagEvalCase,
} from '@/api/knowledgeBase'
import { useToast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import type {
  RagEvalCase,
  RagEvalCaseCategory,
  RagEvalCaseRequest,
  RagEvalGateDecision,
  RagEvalRun,
  RagEvalRunRequest,
  RagQualityPolicy,
  RetrievalIndexMode,
} from '@/types'
import { CheckCircle2, Loader2, Pencil, Play, Save, Trash2, X, XCircle } from 'lucide-react'

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

const formatPercent = (value: number | null | undefined) => `${((value ?? 0) * 100).toFixed(1)}%`
const formatDelta = (value: number | null | undefined) => `${(value ?? 0) >= 0 ? '+' : ''}${formatPercent(value)}`

const EMPTY_FORM: RagEvalCaseRequest = {
  caseKey: '',
  query: '',
  minHits: 1,
  topK: 5,
  category: 'REAL_QUERY',
  expectedFileName: '',
  expectedFileNames: [],
  mustContainAny: [],
}

export function RagEvalPanel({ kbId }: RagEvalPanelProps) {
  const [cases, setCases] = useState<RagEvalCase[]>([])
  const [runs, setRuns] = useState<RagEvalRun[]>([])
  const [form, setForm] = useState<RagEvalCaseRequest>(EMPTY_FORM)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [running, setRunning] = useState(false)
  const [useRerank, setUseRerank] = useState(false)
  const [selectedProfiles, setSelectedProfiles] = useState<RetrievalIndexMode[]>(['CLASSIC'])
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
    void Promise.all([listRagEvalCases(kbId), listRagEvalRuns(kbId), getRagQualityPolicy(kbId)])
      .then(([nextCases, nextRuns, nextPolicy]) => {
        if (!active) return
        setCases(nextCases)
        setRuns(nextRuns)
        setPolicy(nextPolicy)
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
  const latestResults = latestRun?.results ?? []
  const latestMetrics = latestRun?.metrics
  const releaseGate = latestMetrics?.releaseGate
  const categorySummaryEntries = Object.entries(latestMetrics?.categorySummaries ?? {})
  const profileComparisonEntries = Object.entries(latestMetrics?.profileComparisons ?? {})
  const qualitySystemCards = [
    latestMetrics?.releaseReadiness && {
      title: 'Release readiness',
      version: latestMetrics.releaseReadiness.version ?? 'V1.9',
      value: latestMetrics.releaseReadiness.status ?? 'UNKNOWN',
      detail: `Score ${(latestMetrics.releaseReadiness.readinessScore ?? 0).toFixed(1)} · Blockers ${latestMetrics.releaseReadiness.blockerCount ?? 0}`,
    },
    latestMetrics?.realQueryFeedback && {
      title: 'Real query feedback',
      version: latestMetrics.realQueryFeedback.version ?? 'V2.0',
      value: `${latestMetrics.realQueryFeedback.candidateCount ?? 0} candidates`,
      detail: latestMetrics.realQueryFeedback.source ?? 'rag_eval_failures_and_degraded_signals',
    },
    latestMetrics?.experimentMatrix && {
      title: 'Experiment matrix',
      version: latestMetrics.experimentMatrix.version ?? 'V2.1',
      value: `${latestMetrics.experimentMatrix.evaluationCount ?? 0} evals`,
      detail: `TopK ${(latestMetrics.experimentMatrix.topKValues ?? []).join(', ') || '-'} · Profiles ${(latestMetrics.experimentMatrix.profiles ?? []).join(', ') || '-'}`,
    },
    latestMetrics?.answerQuality && {
      title: 'Answer quality',
      version: latestMetrics.answerQuality.version ?? 'V2.2',
      value: formatPercent(latestMetrics.answerQuality.groundedPassRate),
      detail: `Citation ${latestMetrics.answerQuality.citationPassedCount ?? 0}/${latestMetrics.answerQuality.citationEligibleCount ?? 0} · Risk ${latestMetrics.answerQuality.hallucinationRiskCount ?? 0}`,
    },
    latestMetrics?.onlineObservability && {
      title: 'Online observability',
      version: latestMetrics.onlineObservability.version ?? 'V2.3',
      value: `${latestMetrics.onlineObservability.fallbackCount ?? 0} fallback`,
      detail: `Fallback ${formatPercent(latestMetrics.onlineObservability.fallbackRate)} · P95 ${latestMetrics.onlineObservability.latencyP95Ms ?? 0} ms`,
    },
    latestMetrics?.dataIndexGovernance && {
      title: 'Data/index governance',
      version: latestMetrics.dataIndexGovernance.version ?? 'V2.4',
      value: formatPercent(latestMetrics.dataIndexGovernance.expectedSourceCoverageRate),
      detail: `Sources ${latestMetrics.dataIndexGovernance.matchedExpectedSourceCount ?? 0}/${latestMetrics.dataIndexGovernance.expectedSourceCount ?? 0} · Missing ${latestMetrics.dataIndexGovernance.missingSourceCount ?? 0}`,
    },
  ].filter((card): card is { title: string; version: string; value: string; detail: string } => Boolean(card))
  const failureCategoryOptions = useMemo(
    () => Array.from(new Set(latestResults.flatMap((result) => result.failureCategories ?? []))).sort(),
    [latestResults],
  )
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
    setEditingId(null)
  }

  const editCase = (caseDef: RagEvalCase) => {
    setEditingId(caseDef.id)
    setForm({
      caseKey: caseDef.caseKey ?? caseDef.id,
      query: caseDef.query,
      minHits: caseDef.minHits,
      topK: caseDef.topK ?? 5,
      category: caseDef.category ?? 'REAL_QUERY',
      expectedFileName: caseDef.expectedFileName ?? '',
      expectedFileNames: caseDef.expectedFileNames ?? [],
      mustContainAny: caseDef.mustContainAny ?? [],
    })
  }

  const saveCase = async () => {
    if (!canSave) return
    setSaving(true)
    const request: RagEvalCaseRequest = {
      caseKey: form.caseKey.trim(),
      query: form.query.trim(),
      minHits: Math.max(0, Number(form.minHits) || 0),
      topK: Math.max(1, Number(form.topK) || 5),
      category: form.category ?? 'REAL_QUERY',
      expectedFileName: form.expectedFileName?.trim() || undefined,
      expectedFileNames: (form.expectedFileNames ?? []).map((fileName) => fileName.trim()).filter(Boolean),
      mustContainAny: (form.mustContainAny ?? []).map((token) => token.trim()).filter(Boolean),
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
    setRunning(true)
    try {
      const nextRun = await runRagEval(kbId, evalRunRequest())
      setRuns((current) => [nextRun, ...current.filter((item) => item.id !== nextRun.id)].slice(0, 10))
      if (nextRun.totalCount === 0) {
        showError('请先创建至少一个评估用例')
      } else if (nextRun.passedCount === nextRun.totalCount) {
        showSuccess(`RAG 评估通过：${nextRun.passedCount}/${nextRun.totalCount}`)
      } else {
        showError(`RAG 评估未通过：${nextRun.passedCount}/${nextRun.totalCount}`)
      }
    } catch (error) {
      showError(error instanceof Error ? error.message : 'RAG 评估运行失败')
    } finally {
      setRunning(false)
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
          <div className="flex flex-wrap items-center gap-2 text-xs" aria-label="Retrieval profiles">
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
          <Button aria-label="Run RAG eval" onClick={run} disabled={running || loading || cases.length === 0}>
            {running ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
            运行评估
          </Button>
        </div>
      </div>

      {policy && <section className="border-y border-border py-4">
        <div className="grid gap-3 md:grid-cols-5">
          <label className="text-xs text-muted-foreground">最低通过率<Input type="number" min={0} max={100} value={policy.minimumPassRate} onChange={(e) => setPolicy({ ...policy, minimumPassRate: Number(e.target.value) })} /></label>
          <label className="text-xs text-muted-foreground">最大通过率下降<Input type="number" min={0} max={100} value={policy.maximumPassRateDrop} onChange={(e) => setPolicy({ ...policy, maximumPassRateDrop: Number(e.target.value) })} /></label>
          <label className="text-xs text-muted-foreground">最大新增失败<Input type="number" min={0} value={policy.maximumNewFailures} onChange={(e) => setPolicy({ ...policy, maximumNewFailures: Number(e.target.value) })} /></label>
          <label className="flex items-center gap-2 pt-5 text-sm"><input type="checkbox" checked={policy.blockWhenUnbaselined} onChange={(e) => setPolicy({ ...policy, blockWhenUnbaselined: e.target.checked })} />无基线时阻断</label>
          <Button className="self-end" onClick={() => void savePolicy()} disabled={saving}><Save className="h-4 w-4" />保存策略</Button>
        </div>
      </section>}

      {latestGateDecisions.length > 0 && (
        <section className="border-y border-border py-4">
          <h3 className="mb-3 text-sm font-semibold">Profile quality gates</h3>
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
            <h3 className="text-sm font-semibold">Quality dashboard</h3>
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
                    <Badge variant="muted">{card.version}</Badge>
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
                <span className="font-semibold">Release gate rollup</span>
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
              <h4 className="mb-2 text-xs font-semibold uppercase text-muted-foreground">Category summaries / trend</h4>
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
            <div className="overflow-x-auto rounded border border-border">
              <table className="w-full text-xs">
                <thead className="border-b bg-muted/50 text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2 text-left">Profile A/B comparison</th>
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
          )}
        </section>
      )}

      <section className="border-y border-border py-5">
        <div className="mb-4 flex items-center justify-between gap-3">
          <div>
            <h3 className="text-sm font-semibold">评估用例</h3>
            <p className="mt-1 text-xs text-muted-foreground">来源文件和关键词均为可选校验条件。</p>
          </div>
          <Badge variant="muted">{cases.length} 条</Badge>
        </div>

        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-8">
          <Input
            name="caseKey"
            value={form.caseKey}
            onChange={(event) => updateForm('caseKey', event.target.value)}
            placeholder="用例标识"
            aria-label="用例标识"
          />
          <Input
            name="query"
            value={form.query}
            onChange={(event) => updateForm('query', event.target.value)}
            placeholder="检索问题"
            aria-label="检索问题"
            className="md:col-span-2"
          />
          <select
            name="category"
            value={form.category ?? 'REAL_QUERY'}
            onChange={(event) => updateForm('category', event.target.value as RagEvalCaseCategory)}
            aria-label="场景分类"
            className="h-10 rounded-md border border-input bg-background px-3 text-sm text-foreground"
          >
            {CASE_CATEGORY_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
          <Input
            name="expectedFileName"
            value={form.expectedFileName ?? ''}
            onChange={(event) => updateForm('expectedFileName', event.target.value)}
            placeholder="期望文件"
            aria-label="期望文件"
          />
          <Input
            name="expectedFileNames"
            value={(form.expectedFileNames ?? []).join(', ')}
            onChange={(event) => updateForm('expectedFileNames', event.target.value.split(','))}
            placeholder="附加来源，逗号分隔"
            aria-label="附加期望文件"
          />
          <Input
            name="mustContainAny"
            value={(form.mustContainAny ?? []).join(', ')}
            onChange={(event) => updateForm('mustContainAny', event.target.value.split(','))}
            placeholder="关键词，逗号分隔"
            aria-label="期望关键词"
          />
          <div className="flex gap-2">
            <Input
              name="minHits"
              type="number"
              min={0}
              value={form.minHits}
              onChange={(event) => updateForm('minHits', Number(event.target.value))}
              aria-label="最少命中数"
              title="最少命中数"
            />
            <Input
              name="topK"
              type="number"
              min={1}
              value={form.topK ?? 5}
              onChange={(event) => updateForm('topK', Number(event.target.value))}
              aria-label="TopK"
              title="TopK"
            />
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
                      <Badge variant="muted">{caseDef.category ?? 'REAL_QUERY'}</Badge>
                    </div>
                    <p className="mt-1 max-w-xl text-xs text-muted-foreground">{caseDef.query}</p>
                  </td>
                  <td className="px-3 py-3 text-xs text-muted-foreground">
                    <p>命中 ≥ {caseDef.minHits} · TopK {caseDef.topK ?? 5}</p>
                    <p className="mt-1">文件 {[caseDef.expectedFileName, ...(caseDef.expectedFileNames ?? [])].filter(Boolean).join(', ') || '-'} · 关键词 {(caseDef.mustContainAny ?? []).join(', ') || '-'}</p>
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
              return (
              <div key={item.id} className="min-w-52 rounded border border-border px-3 py-2 text-xs">
                <div className="mb-2 flex items-center justify-between gap-2">
                  <Badge variant={item.gateStatus === 'PASS' ? 'success' : item.gateStatus === 'WARN' || item.gateStatus === 'UNBASELINED' ? 'warning' : 'error'}>{item.gateStatus ?? 'NO GATE'}</Badge>
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
                        <p key={category} className="font-mono">{category} {count}</p>
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
          <h3 className="text-sm font-semibold">Diagnostic drilldown</h3>
          <span className="text-xs text-muted-foreground">Showing {filteredResults.length}/{latestResults.length} results</span>
        </div>
        <div className="grid gap-2 md:grid-cols-3">
          <select
            name="diagnosticCategory"
            value={diagnosticCategory}
            onChange={(event) => setDiagnosticCategory(event.target.value as 'ALL' | RagEvalCaseCategory)}
            aria-label="Diagnostic category"
            className="h-10 rounded-md border border-input bg-background px-3 text-sm text-foreground"
          >
            <option value="ALL">All categories</option>
            {CASE_CATEGORY_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.value}</option>
            ))}
          </select>
          <select
            name="diagnosticStatus"
            value={diagnosticStatus}
            onChange={(event) => setDiagnosticStatus(event.target.value as 'ALL' | 'PASS' | 'FAIL')}
            aria-label="Diagnostic status"
            className="h-10 rounded-md border border-input bg-background px-3 text-sm text-foreground"
          >
            <option value="ALL">All statuses</option>
            <option value="PASS">PASS</option>
            <option value="FAIL">FAIL</option>
          </select>
          <select
            name="diagnosticFailureCategory"
            value={diagnosticFailureCategory}
            onChange={(event) => setDiagnosticFailureCategory(event.target.value)}
            aria-label="Diagnostic failure category"
            className="h-10 rounded-md border border-input bg-background px-3 text-sm text-foreground"
          >
            <option value="ALL">All failure categories</option>
            {failureCategoryOptions.map((category) => (
              <option key={category} value={category}>{category}</option>
            ))}
          </select>
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
              <tr><td colSpan={7} className="px-4 py-10 text-center text-muted-foreground">最新运行暂无结果</td></tr>
            ) : filteredResults.map((result) => (
              <tr key={result.id} className="border-b last:border-0">
                <td className="px-4 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="font-mono text-xs">{result.caseKey ?? result.caseId ?? result.id}</p>
                    <Badge variant="muted">{result.category ?? 'REAL_QUERY'}</Badge>
                  </div>
                  <p className="mt-1 max-w-xs text-xs text-muted-foreground">{result.query}</p>
                </td>
                <td className="px-4 py-3">
                  <span className="inline-flex items-center gap-1 font-mono text-xs font-semibold">
                    {result.passed ? <CheckCircle2 className="h-4 w-4 text-emerald-600" /> : <XCircle className="h-4 w-4 text-destructive" />}
                    {result.passed ? 'PASS' : 'FAIL'}
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
