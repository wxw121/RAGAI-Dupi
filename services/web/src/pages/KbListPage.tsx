import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { createKnowledgeBase, deleteKnowledgeBase, listKnowledgeBases } from '@/api/knowledgeBase'
import type { KnowledgeBase } from '@/types'
import { AppLayout } from '@/components/AppLayout'
import { useToast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { formatDate } from '@/lib/utils'
import { FileText, Loader2, MessageSquare, Plus, Trash2 } from 'lucide-react'

export function KbListPage() {
  const navigate = useNavigate()
  const [kbs, setKbs] = useState<KnowledgeBase[]>([])
  const [loading, setLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [chunkSize, setChunkSize] = useState(512)
  const [chunkOverlap, setChunkOverlap] = useState(64)
  const [topK, setTopK] = useState(5)
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
      })
      showSuccess('知识库创建成功')
      setDialogOpen(false)
      setName('')
      setDescription('')
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
    if (!confirm(`确定删除知识库「${kb.name}」？此操作不可恢复。`)) return
    try {
      await deleteKnowledgeBase(kb.id)
      showSuccess('已删除')
      await load()
    } catch (e) {
      showError(e instanceof Error ? e.message : '删除失败')
    }
  }

  return (
    <AppLayout>
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">知识库</h1>
          <p className="text-sm text-muted-foreground">管理企业文档与 RAG 问答</p>
        </div>
        <Button onClick={() => setDialogOpen(true)}>
          <Plus className="h-4 w-4" />
          新建知识库
        </Button>
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
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {kbs.map((kb) => (
            <Card
              key={kb.id}
              className="group relative cursor-pointer transition-shadow hover:shadow-md"
              onClick={() => navigate(`/kb/${kb.id}`)}
            >
              <CardHeader>
                <CardTitle className="pr-8">{kb.name}</CardTitle>
                <CardDescription className="line-clamp-2">
                  {kb.description || '暂无描述'}
                </CardDescription>
              </CardHeader>
              <CardContent>
                <p className="text-xs text-muted-foreground">创建于 {formatDate(kb.createdAt)}</p>
                <p className="mt-1 text-xs text-muted-foreground">
                  分块 {kb.chunkSize} / 重叠 {kb.chunkOverlap} / TopK {kb.topK}
                </p>
                <div className="mt-4 flex gap-2" onClick={(e) => e.stopPropagation()}>
                  <Button variant="outline" size="sm" onClick={() => navigate(`/kb/${kb.id}`)}>
                    <FileText className="h-3.5 w-3.5" />
                    管理文档
                  </Button>
                  <Button size="sm" onClick={() => navigate(`/kb/${kb.id}?tab=chat`)}>
                    <MessageSquare className="h-3.5 w-3.5" />
                    去问答
                  </Button>
                </div>
              </CardContent>
              <Button
                variant="ghost"
                size="sm"
                className="absolute right-2 top-2"
                onClick={(e) => handleDelete(kb, e)}
              >
                <Trash2 className="h-4 w-4 text-destructive" />
              </Button>
            </Card>
          ))}
        </div>
      )}

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
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="例如：产品手册" />
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
