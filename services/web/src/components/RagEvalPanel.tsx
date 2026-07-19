import { useEffect, useMemo, useState } from 'react'
import {
  createRagEvalCase,
  deleteRagEvalCase,
  listRagEvalCases,
  listRagEvalRuns,
  runRagEval,
  updateRagEvalCase,
} from '@/api/knowledgeBase'
import { useToast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import type { RagEvalCase, RagEvalCaseRequest, RagEvalRun, RetrievalProfile } from '@/types'
import { CheckCircle2, Loader2, Pencil, Play, Save, Trash2, X, XCircle } from 'lucide-react'

interface RagEvalPanelProps {
  kbId: string
}

const RETRIEVAL_PROFILE_OPTIONS: Array<{ value: RetrievalProfile; label: string }> = [
  { value: 'CLASSIC', label: 'classic' },
  { value: 'PARENT_CHILD', label: 'parent-child' },
  { value: 'QA_ASSISTED', label: 'qa-assisted' },
  { value: 'COMBINED', label: 'combined' },
]

const EMPTY_FORM: RagEvalCaseRequest = {
  caseKey: '',
  query: '',
  minHits: 1,
  topK: 5,
  expectedFileName: '',
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
  const [selectedProfiles, setSelectedProfiles] = useState<RetrievalProfile[]>(['CLASSIC'])
  const { showError, showSuccess } = useToast()

  useEffect(() => {
    let active = true
    setLoading(true)
    void Promise.all([listRagEvalCases(kbId), listRagEvalRuns(kbId)])
      .then(([nextCases, nextRuns]) => {
        if (!active) return
        setCases(nextCases)
        setRuns(nextRuns)
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

  const toggleProfile = (profile: RetrievalProfile, checked: boolean) => {
    setSelectedProfiles((current) => {
      if (checked) {
        return current.includes(profile) ? current : [...current, profile]
      }
      const next = current.filter((item) => item !== profile)
      return next.length > 0 ? next : current
    })
  }

  const evalRunRequest = () => {
    const base = { useRerank }
    if (selectedProfiles.length === 1 && selectedProfiles[0] === 'CLASSIC') {
      return base
    }
    return { ...base, profiles: selectedProfiles }
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
      expectedFileName: caseDef.expectedFileName ?? '',
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
      expectedFileName: form.expectedFileName?.trim() || undefined,
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
              <label key={profile.value} className="inline-flex items-center gap-1 rounded-full border border-border px-2 py-1">
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
          <Button aria-label="Run RAG eval" onClick={run} disabled={running || loading || cases.length === 0}>
            {running ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
            运行评估
          </Button>
        </div>
      </div>

      <section className="border-y border-border py-5">
        <div className="mb-4 flex items-center justify-between gap-3">
          <div>
            <h3 className="text-sm font-semibold">评估用例</h3>
            <p className="mt-1 text-xs text-muted-foreground">来源文件和关键词均为可选校验条件。</p>
          </div>
          <Badge variant="muted">{cases.length} 条</Badge>
        </div>

        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-6">
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
          <Input
            name="expectedFileName"
            value={form.expectedFileName ?? ''}
            onChange={(event) => updateForm('expectedFileName', event.target.value)}
            placeholder="期望文件"
            aria-label="期望文件"
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
                    <p className="font-mono text-xs font-semibold">{caseDef.caseKey ?? caseDef.id}</p>
                    <p className="mt-1 max-w-xl text-xs text-muted-foreground">{caseDef.query}</p>
                  </td>
                  <td className="px-3 py-3 text-xs text-muted-foreground">
                    <p>命中 ≥ {caseDef.minHits} · TopK {caseDef.topK ?? 5}</p>
                    <p className="mt-1">文件 {caseDef.expectedFileName || '-'} · 关键词 {(caseDef.mustContainAny ?? []).join(', ') || '-'}</p>
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
            {runSummary.map((item) => (
              <div key={item.id} className="min-w-44 rounded-lg border border-border px-3 py-2 text-xs">
                <div className="flex items-center justify-between gap-3">
                  <span className="font-semibold">{item.passedCount}/{item.totalCount}</span>
                  <Badge variant={item.status === 'RUNNING' ? 'warning' : item.status === 'FAILED' ? 'error' : item.passed ? 'success' : 'error'}>
                    {item.status === 'RUNNING' ? '运行中' : item.status === 'FAILED' ? '失败' : item.passed ? '已完成 PASS' : '已完成 FAIL'}
                  </Badge>
                </div>
                <p className="mt-2 text-muted-foreground">{item.useRerank ? 'Rerank 已启用' : 'Rerank 未启用'}</p>
                {item.failureMessage && <p className="mt-1 text-destructive">{item.failureMessage}</p>}
                <p className="mt-1 text-muted-foreground">{new Date(item.createdAt).toLocaleString()}</p>
              </div>
            ))}
          </div>
        )}
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
            {latestResults.length === 0 ? (
              <tr><td colSpan={8} className="px-4 py-10 text-center text-muted-foreground">最新运行暂无结果</td></tr>
            ) : latestResults.map((result) => (
              <tr key={result.id} className="border-b last:border-0">
                <td className="px-4 py-3">
                  <p className="font-mono text-xs">{result.caseKey ?? result.caseId ?? result.id}</p>
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
                  <p className="font-mono text-xs">{result.matchedFileName ?? '-'}</p>
                  {result.expectedFileName && <p className="mt-1 text-xs text-muted-foreground">期望 {result.expectedFileName}</p>}
                </td>
                <td className="px-4 py-3 text-muted-foreground">{result.matchedToken ?? '-'}</td>
                <td className="px-4 py-3 font-mono text-xs">{result.retrievalProfile ?? 'CLASSIC'}</td>
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
