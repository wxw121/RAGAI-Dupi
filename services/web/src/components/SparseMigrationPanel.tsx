import { useEffect, useMemo, useState } from 'react'
import {
  backfillSparseMigration, beginSparseShadowValidation, completeSparseMigration,
  cutoverSparseMigration, listRetrievalProfiles, listSparseMigrations, setLegacySparseFallback, startSparseMigration,
} from '@/api/knowledgeBase'
import { useToast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Badge, Dialog, statusBadgeVariant } from '@/components/ui/dialog'
import type { RetrievalProfile, SparseMigration, SparseMigrationState } from '@/types'
import { DatabaseZap, Loader2, Play, RefreshCw, ShieldCheck } from 'lucide-react'

const STATES: SparseMigrationState[] = [
  'PREPARING', 'BACKFILLING', 'DUAL_WRITING', 'SHADOW_VALIDATING', 'CUTOVER', 'COMPLETED', 'FAILED',
]
const POLLED_STATES: SparseMigrationState[] = ['PREPARING', 'BACKFILLING', 'DUAL_WRITING', 'SHADOW_VALIDATING', 'CUTOVER']

const STAGE_DETAILS: Record<SparseMigrationState, { label: string; description: string }> = {
  PREPARING: { label: '准备迁移', description: '创建新索引并校验所选方案。' },
  BACKFILLING: { label: '回填历史文档', description: '将已有文档写入新索引。' },
  DUAL_WRITING: { label: '双写同步', description: '新旧索引同时接收新增文档。' },
  SHADOW_VALIDATING: { label: '影子验证', description: '后台比较新旧结果，不影响线上问答。' },
  CUTOVER: { label: '切换中', description: '准备将线上关键词检索切换到新索引。' },
  COMPLETED: { label: '已完成', description: '新索引已投入使用。' },
  FAILED: { label: '迁移失败', description: '迁移已停止，请查看失败原因后再处理。' },
}

function canCutover(value: SparseMigration) {
  const coverage = value.sourceChunkCount > 0 && value.indexedChunkCount === value.sourceChunkCount
  const dimensions = value.expectedDimension != null && value.expectedDimension === value.actualDimension
  const latency = value.baselineP95Ms != null && value.candidateP95Ms != null && value.candidateP95Ms <= value.baselineP95Ms * 1.25
  const fallback = value.baselineFallbackRate != null && value.candidateFallbackRate != null && value.candidateFallbackRate <= value.baselineFallbackRate
  return value.state === 'SHADOW_VALIDATING' && coverage && dimensions && latency && fallback
}

export function SparseMigrationPanel({ kbId, profiles: providedProfiles }: { kbId: string; profiles?: RetrievalProfile[] }) {
  const [migrations, setMigrations] = useState<SparseMigration[]>([])
  const [loadedProfiles, setLoadedProfiles] = useState<RetrievalProfile[]>([])
  const [profileId, setProfileId] = useState('')
  const [busy, setBusy] = useState<string | null>('load')
  const [confirming, setConfirming] = useState<SparseMigration | null>(null)
  const { showError, showSuccess } = useToast()

  const reload = async () => {
    try { setMigrations(await listSparseMigrations(kbId)) }
    catch (error) { showError(error instanceof Error ? error.message : 'Migration status failed to load') }
    finally { setBusy(null) }
  }
  useEffect(() => { void reload() }, [kbId])
  useEffect(() => {
    if (providedProfiles) return
    void listRetrievalProfiles(kbId).then(setLoadedProfiles).catch((error) => showError(error instanceof Error ? error.message : 'Profiles failed to load'))
  }, [kbId, providedProfiles])
  useEffect(() => {
    if (!migrations.some((item) => POLLED_STATES.includes(item.state))) return
    const timer = window.setInterval(() => void reload(), 5000)
    return () => window.clearInterval(timer)
  }, [migrations])

  const profiles = providedProfiles ?? loadedProfiles
  const profileNames = useMemo(() => new Map(profiles.map((profile) => [profile.id, `v${profile.version} ${profile.name}`])), [profiles])
  const command = async (migration: SparseMigration, action: () => Promise<SparseMigration>, success: string) => {
    setBusy(migration.id)
    try { await action(); await reload(); showSuccess(success) }
    catch (error) { showError(error instanceof Error ? error.message : 'Migration command failed'); setBusy(null) }
  }
  const start = async () => {
    if (!profileId) return
    setBusy('start')
    try { await startSparseMigration(kbId, profileId); await reload(); showSuccess('已开始稀疏检索迁移') }
    catch (error) { showError(error instanceof Error ? error.message : '启动稀疏检索迁移失败'); setBusy(null) }
  }

  return (
    <section className="border-t border-border px-4 py-6 md:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h2 className="flex items-center gap-2 text-base font-semibold"><DatabaseZap className="h-4 w-4" />稀疏检索迁移（Sparse Migration）</h2>
            <p className="mt-1 max-w-2xl text-sm text-muted-foreground">仅在升级关键词检索索引时使用：系统会为选定的 Retrieval Profile 建立新索引、补齐历史文档，并在不影响线上问答的情况下验证结果。日常问答无需操作。</p>
          </div>
          <div className="flex min-w-0 gap-2">
            <label className="min-w-0 space-y-1">
              <span className="block text-xs font-medium text-muted-foreground">选择检索方案</span>
              <select aria-label="选择迁移检索方案" value={profileId} onChange={(event) => setProfileId(event.target.value)} className="h-9 min-w-0 border border-input bg-background px-3 text-sm">
                <option value="">请选择方案</option>
              {profiles.map((profile) => <option key={profile.id} value={profile.id}>v{profile.version} {profile.name}</option>)}
              </select>
            </label>
            <Button className="self-end" onClick={() => void start()} disabled={!profileId || busy === 'start'}>{busy === 'start' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}开始迁移</Button>
            <Button className="self-end" variant="outline" size="sm" title="刷新迁移状态" aria-label="刷新迁移状态" onClick={() => void reload()}><RefreshCw className="h-4 w-4" /></Button>
          </div>
        </div>
        <div className="mt-5 space-y-4">
          {migrations.map((migration) => {
            const activeIndex = STATES.indexOf(migration.state)
            const ratio = migration.baselineP95Ms && migration.candidateP95Ms ? migration.candidateP95Ms / migration.baselineP95Ms : null
            const stage = STAGE_DETAILS[migration.state]
            return <div key={migration.id} className="border-y border-border py-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div><span className="font-mono text-xs text-muted-foreground">迁移编号：{migration.id}</span><p className="mt-1 text-sm font-semibold">{profileNames.get(migration.profileId) ?? migration.profileId}</p></div>
                <span title={migration.state}><Badge variant={statusBadgeVariant(migration.state)}>阶段：{stage.label}</Badge></span>
              </div>
              <p className="mt-2 text-sm text-muted-foreground">当前阶段说明：{stage.description}</p>
              <ol className="mt-4 grid grid-cols-4 border border-border md:grid-cols-7">
                {STATES.map((state, index) => <li key={state} className={`min-w-0 border-r border-border p-2 last:border-r-0 ${index === activeIndex ? 'bg-primary text-primary-foreground' : index < activeIndex && migration.state !== 'FAILED' ? 'bg-muted' : ''}`}><span className="block font-mono text-[10px] tabular-nums">{String(index + 1).padStart(2, '0')}</span><span className="block truncate text-[10px] font-medium" title={`${STAGE_DETAILS[state].label}（${state}）`}>{STAGE_DETAILS[state].label}</span></li>)}
              </ol>
              <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-2 text-xs md:grid-cols-5">
                <div><dt className="text-muted-foreground" title="已写入新索引的片段数 / 当前知识库需要处理的片段数">索引覆盖率</dt><dd className="mt-1 font-mono tabular-nums">{migration.indexedChunkCount} / {migration.sourceChunkCount}</dd></div>
                <div><dt className="text-muted-foreground" title="新索引实际向量维度 / 预期维度，必须一致">向量维度</dt><dd className="mt-1 font-mono tabular-nums">{migration.actualDimension ?? '-'} / {migration.expectedDimension ?? '-'}</dd></div>
                <div><dt className="text-muted-foreground" title="新索引 P95 响应时间 / 当前基线 P95；越接近 1 越好">P95 耗时比例</dt><dd className="mt-1 font-mono tabular-nums">{ratio == null ? '-' : `${ratio.toFixed(2)}x`}</dd></div>
                <div><dt className="text-muted-foreground" title="新索引兜底率 / 当前基线兜底率；不应升高">兜底率</dt><dd className="mt-1 font-mono tabular-nums">{migration.candidateFallbackRate ?? '-'} / {migration.baselineFallbackRate ?? '-'}</dd></div>
                <div><dt className="text-muted-foreground">最近更新</dt><dd className="mt-1 font-mono tabular-nums">{migration.updatedAt ? new Date(migration.updatedAt).toLocaleString() : '-'}</dd></div>
              </dl>
              {migration.errorMessage && <p className="mt-3 border-l-2 border-destructive pl-3 text-sm text-destructive">{migration.errorMessage}</p>}
              <div className="mt-4 flex flex-wrap items-center justify-end gap-2">
                {(migration.state === 'DUAL_WRITING' || migration.state === 'SHADOW_VALIDATING') && <button role="switch" aria-label="保留旧版关键词检索兜底" aria-checked={migration.legacyBm25Enabled} className="h-8 border border-input px-3 text-xs" title="新索引异常时是否继续使用旧版关键词检索兜底" onClick={() => void command(migration, () => setLegacySparseFallback(kbId, migration.id, !migration.legacyBm25Enabled), '已更新旧版关键词检索兜底设置')}>旧版关键词检索兜底：{migration.legacyBm25Enabled ? '开启' : '关闭'}</button>}
                {(migration.state === 'PREPARING' || migration.state === 'FAILED') && <Button size="sm" variant="outline" onClick={() => void command(migration, () => backfillSparseMigration(kbId, migration.id), '已开始回填历史文档')}>开始回填</Button>}
                {migration.state === 'DUAL_WRITING' && <Button size="sm" variant="outline" onClick={() => void command(migration, () => beginSparseShadowValidation(kbId, migration.id), '已开始影子验证')}>开始影子验证</Button>}
                {migration.state === 'SHADOW_VALIDATING' && <Button size="sm" aria-label="切换到新索引" disabled={!canCutover(migration)} onClick={() => setConfirming(migration)}><ShieldCheck className="h-4 w-4" />切换到新索引</Button>}
                {migration.state === 'CUTOVER' && <Button size="sm" onClick={() => void command(migration, () => completeSparseMigration(kbId, migration.id), '迁移已完成')}>完成迁移</Button>}
                {busy === migration.id && <Loader2 className="h-4 w-4 animate-spin" />}
              </div>
            </div>
          })}
          {!busy && migrations.length === 0 && <div className="py-8 text-center text-sm text-muted-foreground"><p>暂无稀疏检索迁移任务</p><p className="mt-1 text-xs">仅在需要升级关键词检索索引时，选择一个已评估的检索方案后开始迁移。</p></div>}
        </div>
      </div>
      <Dialog open={confirming != null} onClose={() => setConfirming(null)} title="确认切换到新索引" footer={<><Button variant="outline" onClick={() => setConfirming(null)}>取消</Button><Button onClick={() => { const value = confirming; setConfirming(null); if (value) void command(value, () => cutoverSparseMigration(kbId, value.id), '已切换到新索引') }}>确认切换</Button></>}>
        {confirming && <><p className="mb-4 text-sm text-muted-foreground">切换后，线上关键词检索将开始使用新索引。系统会在执行前再次校验以下条件。</p><dl className="grid grid-cols-2 gap-3 text-sm"><div><dt className="text-muted-foreground">索引覆盖率</dt><dd className="font-mono">{confirming.indexedChunkCount} / {confirming.sourceChunkCount}</dd></div><div><dt className="text-muted-foreground">P95 耗时比例</dt><dd className="font-mono">{((confirming.candidateP95Ms ?? 0) / (confirming.baselineP95Ms ?? 1)).toFixed(2)}x</dd></div><div><dt className="text-muted-foreground">兜底率差异</dt><dd className="font-mono">{((confirming.candidateFallbackRate ?? 0) - (confirming.baselineFallbackRate ?? 0)).toFixed(3)}</dd></div><div><dt className="text-muted-foreground">质量校验</dt><dd>由系统再次验证</dd></div></dl></>}
      </Dialog>
    </section>
  )
}
