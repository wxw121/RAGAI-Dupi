import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { abandonRestore, createArchive, createRestore, deleteArchive, getArchiveDownloadUrl, listArchives, listRestores, retryArchive, retryRestore } from '@/api/recovery'
import { useToast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Badge, Dialog, statusBadgeVariant } from '@/components/ui/dialog'
import type { RecoveryArchive, RecoveryRestore } from '@/types'
import { Archive, Download, Loader2, RefreshCw, RotateCcw, Trash2, Undo2 } from 'lucide-react'

type Confirmation = { kind: 'archive' } | { kind: 'restore'; archive: RecoveryArchive } | { kind: 'delete'; archive: RecoveryArchive } | { kind: 'abandon'; restore: RecoveryRestore } | null
const activeArchives = new Set(['PREPARING', 'CAPTURING', 'VERIFYING'])
const activeRestores = new Set(['VALIDATING', 'RESTORING_OBJECTS', 'RESTORING_RECORDS', 'RESTORING_VECTORS', 'VERIFYING'])
const shortId = (value: string) => value.slice(0, 8)
const formatBytes = (value: number) => value < 1024 ? `${value} B` : `${(value / 1024).toFixed(1)} KiB`

export function RecoveryPanel({ kbId }: { kbId: string }) {
  const [archives, setArchives] = useState<RecoveryArchive[]>([])
  const [restores, setRestores] = useState<RecoveryRestore[]>([])
  const [busy, setBusy] = useState<string | null>('load')
  const [confirmation, setConfirmation] = useState<Confirmation>(null)
  const { showError, showSuccess } = useToast()
  const reload = useCallback(async () => {
    try { const [a, r] = await Promise.all([listArchives(kbId), listRestores(kbId)]); setArchives(a); setRestores(r) }
    catch (error) { showError(error instanceof Error ? error.message : 'Recovery status failed to load') }
    finally { setBusy(null) }
  }, [kbId, showError])
  useEffect(() => { void reload() }, [reload])
  useEffect(() => {
    if (!archives.some((item) => activeArchives.has(item.status)) && !restores.some((item) => activeRestores.has(item.status))) return
    const timer = window.setInterval(() => void reload(), 3000)
    return () => window.clearInterval(timer)
  }, [archives, reload, restores])
  const command = async (key: string, action: () => Promise<unknown>, success: string) => {
    setBusy(key)
    try { await action(); await reload(); showSuccess(success) }
    catch (error) { showError(error instanceof Error ? error.message : 'Recovery command failed'); setBusy(null) }
  }
  const confirm = () => {
    const value = confirmation; setConfirmation(null); if (!value) return
    if (value.kind === 'archive') void command('create', () => createArchive(kbId), 'Archive queued')
    if (value.kind === 'restore') void command(value.archive.id, () => createRestore(kbId, value.archive.id), 'Restore queued')
    if (value.kind === 'delete') void command(value.archive.id, () => deleteArchive(kbId, value.archive.id), 'Archive deleted')
    if (value.kind === 'abandon') void command(value.restore.id, () => abandonRestore(kbId, value.restore.id), 'Restore abandoned')
  }
  const dialog = confirmation && ({ archive: ['Confirm archive', 'Capture records, objects, and vectors from the current knowledge base.', 'Confirm archive'], restore: ['Confirm restore', 'Restore this archive into a new hidden knowledge base.', 'Confirm restore'], delete: ['Delete archive', 'Permanently delete this archive and its recovery objects.', 'Confirm delete'], abandon: ['Abandon restore', 'Remove the hidden target and all restored data.', 'Confirm abandon'] } as const)[confirmation.kind]

  return <section className="mx-auto max-w-6xl px-4 py-6 md:px-8">
    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border pb-4"><h2 className="flex items-center gap-2 text-base font-semibold"><Archive className="h-4 w-4" />Recovery</h2><div className="flex gap-2"><Button variant="outline" size="sm" aria-label="Refresh recovery" title="Refresh" onClick={() => void reload()}><RefreshCw className="h-4 w-4" /></Button><Button size="sm" aria-label="Create archive" onClick={() => setConfirmation({ kind: 'archive' })}>Create archive</Button></div></div>
    <div className="mt-6 overflow-x-auto"><h3 className="text-sm font-semibold">Archives</h3><table className="mt-2 w-full min-w-[720px] text-left text-sm"><thead className="border-y border-border text-xs text-muted-foreground"><tr><th className="py-2 pr-4 font-medium">Archive</th><th className="w-32 pr-4 font-medium">Status</th><th className="pr-4 font-medium">Evidence</th><th className="w-40 pr-4 font-medium">Updated</th><th className="w-40 text-right font-medium">Actions</th></tr></thead><tbody>{archives.map((item) => <tr key={item.id} className="border-b border-border align-top"><td className="py-3 pr-4 font-mono text-xs" title={item.id}>{shortId(item.id)}</td><td className="pr-4"><Badge variant={statusBadgeVariant(item.status)}>{item.status}</Badge></td><td className="pr-4 text-xs"><span className="font-mono tabular-nums">{item.itemCount} items · {formatBytes(item.totalBytes)}</span>{item.manifestChecksum && <span className="ml-3 font-mono text-muted-foreground">{item.manifestChecksum.slice(0, 12)}</span>}{item.errorMessage && <p className="mt-1 text-destructive">{item.errorMessage}</p>}</td><td className="pr-4 font-mono text-xs tabular-nums">{new Date(item.updatedAt).toLocaleString()}</td><td><div className="flex justify-end gap-1">{item.status === 'COMPLETED' && <a aria-label="Download archive" title="Download archive" href={getArchiveDownloadUrl(kbId, item.id)} className="inline-flex h-8 w-8 items-center justify-center border border-input hover:bg-muted"><Download className="h-4 w-4" /></a>}{item.status === 'COMPLETED' && <Button variant="outline" size="sm" aria-label={`Restore archive ${item.id}`} title="Restore archive" onClick={() => setConfirmation({ kind: 'restore', archive: item })}><Undo2 className="h-4 w-4" /></Button>}{item.status === 'FAILED' && <Button variant="outline" size="sm" aria-label="Retry archive" title="Retry archive" onClick={() => void command(item.id, () => retryArchive(kbId, item.id), 'Archive retry queued')}><RotateCcw className="h-4 w-4" /></Button>}{(item.status === 'FAILED' || item.status === 'COMPLETED') && <Button variant="ghost" size="sm" aria-label="Delete archive" title="Delete archive" onClick={() => setConfirmation({ kind: 'delete', archive: item })}><Trash2 className="h-4 w-4" /></Button>}{busy === item.id && <Loader2 className="h-4 w-4 animate-spin" />}</div></td></tr>)}</tbody></table>{!busy && archives.length === 0 && <p className="border-b border-border py-8 text-center text-sm text-muted-foreground">No recovery archives</p>}</div>
    <div className="mt-8 overflow-x-auto"><h3 className="text-sm font-semibold">Restores</h3><table className="mt-2 w-full min-w-[720px] text-left text-sm"><thead className="border-y border-border text-xs text-muted-foreground"><tr><th className="py-2 pr-4 font-medium">Restore</th><th className="w-40 pr-4 font-medium">Status</th><th className="pr-4 font-medium">Progress</th><th className="w-40 pr-4 font-medium">Target</th><th className="w-32 text-right font-medium">Actions</th></tr></thead><tbody>{restores.map((item) => <tr key={item.id} className="border-b border-border align-top"><td className="py-3 pr-4 font-mono text-xs" title={item.id}>{shortId(item.id)}</td><td className="pr-4"><Badge variant={statusBadgeVariant(item.status)}>{item.status}</Badge></td><td className="pr-4 text-xs"><span className="font-mono tabular-nums">{item.completedItems} / {item.totalItems}</span>{item.errorMessage && <p className="mt-1 text-destructive">{item.errorMessage}</p>}</td><td className="pr-4 font-mono text-xs">{item.status === 'COMPLETED' && item.targetKnowledgeBaseId ? <Link className="text-primary underline" to={`/knowledge-bases/${item.targetKnowledgeBaseId}`}>{shortId(item.targetKnowledgeBaseId)}</Link> : '-'}</td><td><div className="flex justify-end gap-1">{item.status === 'FAILED' && <Button variant="outline" size="sm" aria-label="Retry restore" title="Retry restore" onClick={() => void command(item.id, () => retryRestore(kbId, item.id), 'Restore retry queued')}><RotateCcw className="h-4 w-4" /></Button>}{item.status === 'FAILED' && <Button variant="ghost" size="sm" aria-label="Abandon restore" title="Abandon restore" onClick={() => setConfirmation({ kind: 'abandon', restore: item })}><Trash2 className="h-4 w-4" /></Button>}{busy === item.id && <Loader2 className="h-4 w-4 animate-spin" />}</div></td></tr>)}</tbody></table>{!busy && restores.length === 0 && <p className="border-b border-border py-8 text-center text-sm text-muted-foreground">No restore jobs</p>}</div>
    <Dialog open={confirmation != null} onClose={() => setConfirmation(null)} title={dialog?.[0] ?? ''} footer={<><Button variant="outline" onClick={() => setConfirmation(null)}>Cancel</Button><Button variant={confirmation?.kind === 'delete' || confirmation?.kind === 'abandon' ? 'destructive' : 'default'} aria-label={dialog?.[2]} onClick={confirm}>{dialog?.[2]}</Button></>}><p className="text-sm text-muted-foreground">{dialog?.[1]}</p></Dialog>
  </section>
}
