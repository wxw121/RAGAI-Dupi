import { useEffect, useState } from 'react'
import {
  activateRetrievalProfile, createRetrievalProfile, listRetrievalProfiles, rollbackRetrievalProfile,
} from '@/api/knowledgeBase'
import { useToast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/dialog'
import type { RetrievalProfile, RetrievalProfileRequest } from '@/types'
import { Loader2, RotateCcw, Save, Zap } from 'lucide-react'

const DEFAULT_PROFILE: RetrievalProfileRequest = {
  name: '', vectorCandidateCount: 30, sparseCandidateCount: 30, rrfConstant: 60,
  sparseIndexParams: {}, sparseSearchParams: {}, rerankEnabled: true,
  rerankCandidateLimit: 20, finalTopK: 5,
}

export function RetrievalProfilePanel({ kbId }: { kbId: string }) {
  const [profiles, setProfiles] = useState<RetrievalProfile[]>([])
  const [form, setForm] = useState(DEFAULT_PROFILE)
  const [busy, setBusy] = useState<string | null>('load')
  const { showError, showSuccess } = useToast()

  const reload = async () => {
    try { setProfiles(await listRetrievalProfiles(kbId)) }
    catch (error) { showError(error instanceof Error ? error.message : 'Profile 加载失败') }
    finally { setBusy(null) }
  }
  useEffect(() => { void reload() }, [kbId])

  const create = async () => {
    setBusy('create')
    try {
      await createRetrievalProfile(kbId, form)
      setForm(DEFAULT_PROFILE)
      await reload()
      showSuccess('Retrieval Profile 已创建')
    } catch (error) { showError(error instanceof Error ? error.message : 'Profile 创建失败'); setBusy(null) }
  }

  const changeActive = async (profile: RetrievalProfile, rollback: boolean) => {
    setBusy(profile.id)
    try {
      if (rollback) await rollbackRetrievalProfile(kbId, profile.id)
      else await activateRetrievalProfile(kbId, profile.id)
      await reload()
      showSuccess(rollback ? 'Profile 已回滚' : 'Profile 已激活')
    } catch (error) { showError(error instanceof Error ? error.message : 'Profile 切换失败'); setBusy(null) }
  }

  const active = profiles.find((profile) => profile.active)
  return (
    <section className="border-t border-border px-4 py-6 md:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="flex items-center justify-between gap-4">
          <div><h2 className="text-base font-semibold">Retrieval Profile</h2><p className="mt-1 text-sm text-muted-foreground">版本化管理候选规模、融合、稀疏检索与重排参数。</p></div>
          {active && <Badge variant="success">当前 v{active.version}</Badge>}
        </div>
        <div className="mt-5 grid gap-3 border-y border-border py-4 md:grid-cols-4 xl:grid-cols-8">
          <Input aria-label="Profile 名称" placeholder="Profile 名称" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className="md:col-span-2" />
          <Input aria-label="向量候选数" title="向量候选数" type="number" min={1} value={form.vectorCandidateCount} onChange={(e) => setForm({ ...form, vectorCandidateCount: Number(e.target.value) })} />
          <Input aria-label="稀疏候选数" title="稀疏候选数" type="number" min={1} value={form.sparseCandidateCount} onChange={(e) => setForm({ ...form, sparseCandidateCount: Number(e.target.value) })} />
          <Input aria-label="RRF 常数" title="RRF 常数" type="number" min={1} value={form.rrfConstant} onChange={(e) => setForm({ ...form, rrfConstant: Number(e.target.value) })} />
          <Input aria-label="重排候选数" title="重排候选数" type="number" min={1} value={form.rerankCandidateLimit} onChange={(e) => setForm({ ...form, rerankCandidateLimit: Number(e.target.value) })} />
          <Input aria-label="最终 TopK" title="最终 TopK" type="number" min={1} value={form.finalTopK} onChange={(e) => setForm({ ...form, finalTopK: Number(e.target.value) })} />
          <Button onClick={create} disabled={!form.name.trim() || busy === 'create'}>{busy === 'create' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}创建</Button>
          <label className="flex items-center gap-2 text-sm md:col-span-2"><input type="checkbox" checked={form.rerankEnabled} onChange={(e) => setForm({ ...form, rerankEnabled: e.target.checked })} />启用 Rerank</label>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm"><thead className="border-b text-xs text-muted-foreground"><tr><th className="px-3 py-3 text-left">版本</th><th className="px-3 py-3 text-left">候选</th><th className="px-3 py-3 text-left">融合 / 重排</th><th className="px-3 py-3 text-left">TopK</th><th className="px-3 py-3 text-right">操作</th></tr></thead>
            <tbody>{profiles.map((profile) => <tr key={profile.id} className="border-b last:border-0"><td className="px-3 py-3"><span className="font-semibold">v{profile.version} · {profile.name}</span>{profile.active && <Badge variant="success" className="ml-2">ACTIVE</Badge>}</td><td className="px-3 py-3 font-mono text-xs">V {profile.vectorCandidateCount} / S {profile.sparseCandidateCount}</td><td className="px-3 py-3 font-mono text-xs">RRF {profile.rrfConstant} / {profile.rerankEnabled ? `Rerank ${profile.rerankCandidateLimit}` : 'No rerank'}</td><td className="px-3 py-3">{profile.finalTopK}</td><td className="px-3 py-3"><div className="flex justify-end gap-1">{!profile.active && <Button size="sm" variant="ghost" title="激活" aria-label={`激活 v${profile.version}`} onClick={() => void changeActive(profile, false)}><Zap className="h-4 w-4" /></Button>}{active && profile.version < active.version && <Button size="sm" variant="ghost" title="回滚" aria-label={`回滚到 v${profile.version}`} onClick={() => void changeActive(profile, true)}><RotateCcw className="h-4 w-4" /></Button>}{busy === profile.id && <Loader2 className="h-4 w-4 animate-spin" />}</div></td></tr>)}</tbody>
          </table>
          {!busy && profiles.length === 0 && <p className="py-8 text-center text-sm text-muted-foreground">尚未创建 Retrieval Profile</p>}
        </div>
      </div>
    </section>
  )
}
