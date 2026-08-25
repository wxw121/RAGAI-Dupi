import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { createKnowledgeBase, deleteKnowledgeBase, listKnowledgeBases } from '@/api/knowledgeBase'
import type { KnowledgeBase, OperationJobResponse } from '@/types'
import { AppLayout } from '@/components/AppLayout'
import { OperationProgress } from '@/components/OperationProgress'
import { useToast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { formatDate } from '@/lib/utils'
import { FileText, Loader2, MessageSquare, Plus, Search, Trash2, X } from 'lucide-react'

export function KbListPage({ onLogout }: { onLogout?: () => void }) {
  const navigate = useNavigate()
  const [kbs, setKbs] = useState<KnowledgeBase[]>([])
  const [loading, setLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [creating, setCreating] = useState(false)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [batchDeleting, setBatchDeleting] = useState(false)
  const [batchConfirmOpen, setBatchConfirmOpen] = useState(false)
  const [deleteOperations, setDeleteOperations] = useState<Record<string, OperationJobResponse>>({})
  const [searchQuery, setSearchQuery] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [chunkSize, setChunkSize] = useState(512)
  const [chunkOverlap, setChunkOverlap] = useState(64)
  const [topK, setTopK] = useState(5)
  const [retrievalMode, setRetrievalMode] = useState<'VECTOR' | 'HYBRID'>('VECTOR')
  const { showError, showSuccess } = useToast()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setKbs(await listKnowledgeBases())
    } catch (e) {
      showError(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [showError])

  useEffect(() => {
    load()
  }, [load])

  const filteredKbs = useMemo(() => {
    const query = searchQuery.trim().toLocaleLowerCase()
    if (!query) return kbs
    return kbs.filter((kb) => kb.name.toLocaleLowerCase().includes(query))
  }, [kbs, searchQuery])

  const handleCreate = async () => {
    if (!name.trim()) return
    setCreating(true)
    try {
      await createKnowledgeBase({
        name: name.trim(),
        description: description.trim() || undefined,
        chunkSize,
        chunkOverlap,
        topK,
        retrievalMode,
      })
      showSuccess('知识库创建成功')
      setDialogOpen(false)
      setName('')
      setDescription('')
      setRetrievalMode('VECTOR')
      await load()
    } catch (e) {
      showError(e instanceof Error ? e.message : '创建失败')
    } finally {
      setCreating(false)
    }
  }

  const handleDelete = async (kb: KnowledgeBase, e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    if (deleteOperations[kb.id]) return
    if (!confirm(`确定删除知识库「${kb.name}」？此操作不可恢复。`)) return
    try {
      const job = await deleteKnowledgeBase(kb.id)
      setDeleteOperations((current) => ({ ...current, [kb.id]: job }))
      showSuccess('删除任务已提交')
    } catch (e) {
      showError(e instanceof Error ? e.message : '删除失败')
    }
  }

  const toggleSelection = (kbId: string) => {
    setSelectedIds((current) => {
      const next = new Set(current)
      if (next.has(kbId)) next.delete(kbId)
      else next.add(kbId)
      return next
    })
  }

  const toggleSelectAll = () => {
    const allFilteredSelected = filteredKbs.every((kb) => selectedIds.has(kb.id))
    setSelectedIds(allFilteredSelected ? new Set() : new Set(filteredKbs.map((kb) => kb.id)))
  }

  const handleBatchDelete = async () => {
    const selected = kbs.filter((kb) => selectedIds.has(kb.id))
    if (selected.length === 0) return

    setBatchConfirmOpen(false)
    setBatchDeleting(true)
    const results = await Promise.allSettled(selected.map((kb) => deleteKnowledgeBase(kb.id)))
    const failedIds = new Set(
      selected.filter((_, index) => results[index].status === 'rejected').map((kb) => kb.id),
    )
    const jobs = selected.reduce<Record<string, OperationJobResponse>>((current, kb, index) => {
      const result = results[index]
      if (result.status === 'fulfilled') current[kb.id] = result.value
      return current
    }, {})
    setDeleteOperations((current) => ({ ...current, ...jobs }))
    const queuedCount = selected.length - failedIds.size
    setSelectedIds(failedIds)
    setBatchDeleting(false)

    if (queuedCount > 0) showSuccess(`已提交 ${queuedCount} 个知识库删除任务`)
    if (failedIds.size > 0) showError(`${failedIds.size} 个知识库删除失败，请重试`)
  }

  const completeDelete = async (kbId: string) => {
    setSelectedIds((current) => {
      const next = new Set(current)
      next.delete(kbId)
      return next
    })
    await load()
    setDeleteOperations((current) => {
      const next = { ...current }
      delete next[kbId]
      return next
    })
    showSuccess('知识库已删除')
  }

  return (
    <AppLayout onLogout={onLogout}>
      <div className="mx-auto max-w-6xl px-4 py-6 md:px-8">
      <div className="mb-6 grid gap-4 md:grid-cols-[1fr_minmax(280px,480px)_1fr] md:items-center">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">知识库</h1>
          <p className="mt-1 text-sm text-muted-foreground">管理企业文档与 RAG 问答</p>
        </div>
        {kbs.length > 0 ? (
          <div className="relative w-full">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              name="knowledgeBaseSearch"
              type="text"
              inputMode="search"
              value={searchQuery}
              onChange={(event) => {
                setSearchQuery(event.target.value)
                setSelectedIds(new Set())
              }}
              placeholder="按名称搜索知识库"
              aria-label="按名称搜索知识库"
              className="h-11 pl-10 pr-10"
            />
            {searchQuery && (
              <button
                type="button"
                onClick={() => {
                  setSearchQuery('')
                  setSelectedIds(new Set())
                }}
                aria-label="清空搜索"
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground transition-colors hover:text-foreground"
              >
                <X className="h-4 w-4" />
              </button>
            )}
          </div>
        ) : <div />}
        <div className="flex w-full justify-end gap-2 md:w-auto">
          {kbs.length > 0 && (
            <Button
              variant="outline"
              onClick={() => setBatchConfirmOpen(true)}
              disabled={selectedIds.size === 0 || batchDeleting}
            >
              {batchDeleting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
              批量删除{selectedIds.size > 0 ? ` (${selectedIds.size})` : ''}
            </Button>
          )}
          <Button onClick={() => setDialogOpen(true)} className="flex-1 md:flex-none">
            <Plus className="h-4 w-4" />
            新建知识库
          </Button>
        </div>
      </div>

      {loading ? (
        <div className="flex justify-center py-16">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
        </div>
      ) : kbs.length === 0 ? (
        <Card>
          <CardContent className="py-12">
            <h2 className="mb-6 text-center text-lg font-semibold">开始使用 dupi-RAG</h2>
            <ol className="mx-auto max-w-md space-y-4">
              <li className="flex items-start gap-3">
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary text-sm font-medium text-primary-foreground">
                  1
                </span>
                <div>
                  <p className="font-medium">新建知识库</p>
                  <p className="text-sm text-muted-foreground">点击「新建知识库」，配置分块参数</p>
                </div>
              </li>
              <li className="flex items-start gap-3">
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary text-sm font-medium text-primary-foreground">
                  2
                </span>
                <div>
                  <p className="font-medium">上传文档</p>
                  <p className="text-sm text-muted-foreground">进入知识库，上传 PDF/DOCX/TXT 并等待 COMPLETED</p>
                </div>
              </li>
              <li className="flex items-start gap-3">
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary text-sm font-medium text-primary-foreground">
                  3
                </span>
                <div>
                  <p className="font-medium">开始问答</p>
                  <p className="text-sm text-muted-foreground">点击「去问答」或切换到智能问答 Tab</p>
                </div>
              </li>
            </ol>
            <div className="mt-8 text-center">
              <Button onClick={() => setDialogOpen(true)}>
                <Plus className="h-4 w-4" />
                新建知识库
              </Button>
            </div>
          </CardContent>
        </Card>
      ) : (
        <div>
          {filteredKbs.length > 0 ? (
            <>
              <label className="mb-3 inline-flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
                <input
                  type="checkbox"
                  checked={filteredKbs.every((kb) => selectedIds.has(kb.id))}
                  onChange={toggleSelectAll}
                  className="h-4 w-4 rounded border-input accent-primary"
                />
                全选
              </label>
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {filteredKbs.map((kb) => (
              <Card
                key={kb.id}
                className="group relative cursor-pointer rounded-2xl border-border bg-background transition-colors hover:bg-muted/40"
                onClick={() => { if (!deleteOperations[kb.id]) navigate(`/kb/${kb.id}`) }}
              >
                <label
                  className="absolute left-3 top-3 z-10 flex cursor-pointer items-center"
                  onClick={(e) => e.stopPropagation()}
                >
                  <input
                    type="checkbox"
                    checked={selectedIds.has(kb.id)}
                    onChange={() => toggleSelection(kb.id)}
                    aria-label={`选择知识库 ${kb.name}`}
                    className="h-4 w-4 rounded border-input accent-primary"
                  />
                </label>
                <CardHeader>
                  <CardTitle className="px-5">{kb.name}</CardTitle>
                  <CardDescription className="line-clamp-2">
                    {kb.description || '暂无描述'}
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <p className="text-xs text-muted-foreground">创建于 {formatDate(kb.createdAt)}</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    分块 {kb.chunkSize} / 重叠 {kb.chunkOverlap} / TopK {kb.topK}
                  </p>
                  {deleteOperations[kb.id] && (
                    <div className="mt-3" onClick={(event) => event.stopPropagation()}>
                      <OperationProgress
                        initialJob={deleteOperations[kb.id]}
                        onCompleted={() => completeDelete(kb.id)}
                      />
                    </div>
                  )}
                  <div className="mt-4 flex flex-wrap gap-2" onClick={(e) => e.stopPropagation()}>
                    <Button variant="outline" size="sm" disabled={Boolean(deleteOperations[kb.id])} onClick={() => navigate(`/kb/${kb.id}`)}>
                      <FileText className="h-3.5 w-3.5" />
                      管理文档
                    </Button>
                    <Button size="sm" disabled={Boolean(deleteOperations[kb.id])} onClick={() => navigate(`/kb/${kb.id}?tab=chat`)}>
                      <MessageSquare className="h-3.5 w-3.5" />
                      去问答
                    </Button>
                  </div>
                </CardContent>
                <Button
                  variant="ghost"
                  size="sm"
                  className="absolute right-2 top-2"
                  disabled={Boolean(deleteOperations[kb.id])}
                  onClick={(e) => handleDelete(kb, e)}
                >
                  <Trash2 className="h-4 w-4 text-destructive" />
                </Button>
              </Card>
                ))}
              </div>
            </>
          ) : (
            <div className="rounded-2xl border border-dashed border-border py-16 text-center">
              <Search className="mx-auto h-8 w-8 text-muted-foreground" />
              <p className="mt-3 font-medium">未找到匹配的知识库</p>
              <p className="mt-1 text-sm text-muted-foreground">请尝试其他名称关键词</p>
              <Button variant="outline" size="sm" className="mt-4" onClick={() => setSearchQuery('')}>
                清空搜索
              </Button>
            </div>
          )}
        </div>
      )}
      </div>

      <Dialog
        open={batchConfirmOpen}
        onClose={() => setBatchConfirmOpen(false)}
        title="批量删除知识库"
        footer={
          <>
            <Button variant="outline" onClick={() => setBatchConfirmOpen(false)}>
              取消
            </Button>
            <Button variant="destructive" onClick={() => void handleBatchDelete()} disabled={batchDeleting}>
              确认删除
            </Button>
          </>
        }
      >
        <p className="text-sm text-muted-foreground">
          确定删除选中的 {selectedIds.size} 个知识库？此操作不可恢复。
        </p>
      </Dialog>

      <Dialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        title="新建知识库"
        footer={
          <>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={handleCreate} disabled={creating || !name.trim()}>
              {creating && <Loader2 className="h-4 w-4 animate-spin" />}
              创建
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-medium">名称 *</label>
            <Input name="knowledgeBaseName" value={name} onChange={(e) => setName(e.target.value)} placeholder="例如：产品手册" />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium">描述</label>
            <Textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="可选"
              rows={2}
            />
          </div>
          <div>
            <label htmlFor="retrieval-mode" className="mb-1 block text-sm font-medium">检索模式</label>
            <select
              id="retrieval-mode"
              name="retrievalMode"
              value={retrievalMode}
              onChange={(event) => setRetrievalMode(event.target.value as 'VECTOR' | 'HYBRID')}
              className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm outline-none focus:ring-2 focus:ring-ring"
            >
              <option value="VECTOR">向量检索</option>
              <option value="HYBRID">混合检索（向量 + 关键词）</option>
            </select>
            <p className="mt-1 text-xs text-muted-foreground">
              混合检索适合同时依赖语义和精确关键词的知识库。
            </p>
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <label className="mb-1 block text-sm font-medium">分块大小</label>
              <Input type="number" value={chunkSize} onChange={(e) => setChunkSize(Number(e.target.value))} />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium">重叠</label>
              <Input type="number" value={chunkOverlap} onChange={(e) => setChunkOverlap(Number(e.target.value))} />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium">TopK</label>
              <Input type="number" value={topK} onChange={(e) => setTopK(Number(e.target.value))} />
            </div>
          </div>
        </div>
      </Dialog>
    </AppLayout>
  )
}
