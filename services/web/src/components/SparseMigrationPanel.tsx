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
    try { await startSparseMigration(kbId, profileId); await reload(); showSuccess('Sparse migration started') }
    catch (error) { showError(error instanceof Error ? error.message : 'Migration failed to start'); setBusy(null) }
  }

  return (
    <section className="border-t border-border px-4 py-6 md:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div><h2 className="flex items-center gap-2 text-base font-semibold"><DatabaseZap className="h-4 w-4" />Sparse Migration</h2></div>
          <div className="flex min-w-0 gap-2">
            <select aria-label="Migration profile" value={profileId} onChange={(event) => setProfileId(event.target.value)} className="h-9 min-w-0 border border-input bg-background px-3 text-sm">
              <option value="">Select profile</option>
              {profiles.map((profile) => <option key={profile.id} value={profile.id}>v{profile.version} {profile.name}</option>)}
            </select>
            <Button onClick={() => void start()} disabled={!profileId || busy === 'start'}>{busy === 'start' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}Start</Button>
            <Button variant="outline" size="sm" title="Refresh" aria-label="Refresh migrations" onClick={() => void reload()}><RefreshCw className="h-4 w-4" /></Button>
          </div>
        </div>
        <div className="mt-5 space-y-4">
          {migrations.map((migration) => {
            const activeIndex = STATES.indexOf(migration.state)
            const ratio = migration.baselineP95Ms && migration.candidateP95Ms ? migration.candidateP95Ms / migration.baselineP95Ms : null
            return <div key={migration.id} className="border-y border-border py-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div><span className="font-mono text-xs text-muted-foreground">{migration.id}</span><p className="mt-1 text-sm font-semibold">{profileNames.get(migration.profileId) ?? migration.profileId}</p></div>
                <Badge variant={statusBadgeVariant(migration.state)}>{migration.state}</Badge>
              </div>
              <ol className="mt-4 grid grid-cols-4 border border-border md:grid-cols-7">
                {STATES.map((state, index) => <li key={state} className={`min-w-0 border-r border-border p-2 last:border-r-0 ${index === activeIndex ? 'bg-primary text-primary-foreground' : index < activeIndex && migration.state !== 'FAILED' ? 'bg-muted' : ''}`}><span className="block font-mono text-[10px] tabular-nums">{String(index + 1).padStart(2, '0')}</span><span className="block truncate text-[10px] font-medium" title={state}>{state}</span></li>)}
              </ol>
              <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-2 text-xs md:grid-cols-5">
                <div><dt className="text-muted-foreground">Coverage</dt><dd className="mt-1 font-mono tabular-nums">{migration.indexedChunkCount} / {migration.sourceChunkCount}</dd></div>
                <div><dt className="text-muted-foreground">Dimension</dt><dd className="mt-1 font-mono tabular-nums">{migration.actualDimension ?? '-'} / {migration.expectedDimension ?? '-'}</dd></div>
                <div><dt className="text-muted-foreground">P95 ratio</dt><dd className="mt-1 font-mono tabular-nums">{ratio == null ? '-' : `${ratio.toFixed(2)}x`}</dd></div>
                <div><dt className="text-muted-foreground">Fallback</dt><dd className="mt-1 font-mono tabular-nums">{migration.candidateFallbackRate ?? '-'} / {migration.baselineFallbackRate ?? '-'}</dd></div>
                <div><dt className="text-muted-foreground">Updated</dt><dd className="mt-1 font-mono tabular-nums">{migration.updatedAt ? new Date(migration.updatedAt).toLocaleString() : '-'}</dd></div>
              </dl>
              {migration.errorMessage && <p className="mt-3 border-l-2 border-destructive pl-3 text-sm text-destructive">{migration.errorMessage}</p>}
              <div className="mt-4 flex flex-wrap items-center justify-end gap-2">
                {(migration.state === 'DUAL_WRITING' || migration.state === 'SHADOW_VALIDATING') && <button role="switch" aria-label="Legacy BM25 fallback" aria-checked={migration.legacyBm25Enabled} className="h-8 border border-input px-3 text-xs" onClick={() => void command(migration, () => setLegacySparseFallback(kbId, migration.id, !migration.legacyBm25Enabled), 'Fallback updated')}>Fallback {migration.legacyBm25Enabled ? 'on' : 'off'}</button>}
                {(migration.state === 'PREPARING' || migration.state === 'FAILED') && <Button size="sm" variant="outline" onClick={() => void command(migration, () => backfillSparseMigration(kbId, migration.id), 'Backfill started')}>Backfill</Button>}
                {migration.state === 'DUAL_WRITING' && <Button size="sm" variant="outline" onClick={() => void command(migration, () => beginSparseShadowValidation(kbId, migration.id), 'Shadow validation started')}>Shadow validation</Button>}
                {migration.state === 'SHADOW_VALIDATING' && <Button size="sm" aria-label="Cutover sparse migration" disabled={!canCutover(migration)} onClick={() => setConfirming(migration)}><ShieldCheck className="h-4 w-4" />Cutover</Button>}
                {migration.state === 'CUTOVER' && <Button size="sm" onClick={() => void command(migration, () => completeSparseMigration(kbId, migration.id), 'Migration completed')}>Complete</Button>}
                {busy === migration.id && <Loader2 className="h-4 w-4 animate-spin" />}
              </div>
            </div>
          })}
          {!busy && migrations.length === 0 && <p className="py-8 text-center text-sm text-muted-foreground">No sparse migrations</p>}
        </div>
      </div>
      <Dialog open={confirming != null} onClose={() => setConfirming(null)} title="Confirm Cutover" footer={<><Button variant="outline" onClick={() => setConfirming(null)}>Cancel</Button><Button onClick={() => { const value = confirming; setConfirming(null); if (value) void command(value, () => cutoverSparseMigration(kbId, value.id), 'Cutover completed') }}>Confirm Cutover</Button></>}>
        {confirming && <dl className="grid grid-cols-2 gap-3 text-sm"><div><dt className="text-muted-foreground">Coverage</dt><dd className="font-mono">{confirming.indexedChunkCount} / {confirming.sourceChunkCount}</dd></div><div><dt className="text-muted-foreground">P95 ratio</dt><dd className="font-mono">{((confirming.candidateP95Ms ?? 0) / (confirming.baselineP95Ms ?? 1)).toFixed(2)}x</dd></div><div><dt className="text-muted-foreground">Fallback delta</dt><dd className="font-mono">{((confirming.candidateFallbackRate ?? 0) - (confirming.baselineFallbackRate ?? 0)).toFixed(3)}</dd></div><div><dt className="text-muted-foreground">Quality gate</dt><dd>Revalidated by API</dd></div></dl>}
      </Dialog>
    </section>
  )
}
