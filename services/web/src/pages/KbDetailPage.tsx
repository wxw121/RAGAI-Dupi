import { useCallback, useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { getKnowledgeBase } from '@/api/knowledgeBase'
import { getIngestJob, listDocuments, uploadDocument, deleteDocument } from '@/api/documents'
import type { Document, KnowledgeBase } from '@/types'
import { AppLayout } from '@/components/AppLayout'
import { ChatPanel } from '@/components/ChatPanel'
import { DocTable } from '@/components/DocTable'
import { UploadZone } from '@/components/UploadZone'
import { useToast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ArrowLeft, FileText, Loader2, MessageSquare } from 'lucide-react'

type Tab = 'documents' | 'chat'

export function KbDetailPage() {
  const { kbId } = useParams<{ kbId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const [kb, setKb] = useState<KnowledgeBase | null>(null)
  const [documents, setDocuments] = useState<Document[]>([])
  const [jobStages, setJobStages] = useState<Record<string, string | null>>({})
  const [tab, setTab] = useState<Tab>(() =>
    searchParams.get('tab') === 'chat' ? 'chat' : 'documents',
  )
  const [loading, setLoading] = useState(true)
  const [deletingId, setDeletingId] = useState<string | null>(null)
  const { showError, showSuccess } = useToast()

  const completedCount = documents.filter((d) => d.status === 'COMPLETED').length

  const loadKb = useCallback(async () => {
    if (!kbId) return
    try {
      setKb(await getKnowledgeBase(kbId))
    } catch (e) {
      showError(e instanceof Error ? e.message : '加载知识库失败')
    }
  }, [kbId, showError])

  const loadDocs = useCallback(async () => {
    if (!kbId) return
    try {
      const docs = await listDocuments(kbId)
      setDocuments(docs)

      const stages: Record<string, string | null> = {}
      await Promise.all(
        docs
          .filter((d) => d.status !== 'COMPLETED' && d.status !== 'FAILED')
          .map(async (d) => {
            try {
              const job = await getIngestJob(kbId, d.id)
              stages[d.id] = job.stage
            } catch {
              stages[d.id] = null
            }
          }),
      )
      setJobStages(stages)
    } catch (e) {
      showError(e instanceof Error ? e.message : '加载文档失败')
    }
  }, [kbId, showError])

  useEffect(() => {
    const init = async () => {
      setLoading(true)
      await loadKb()
      await loadDocs()
      setLoading(false)
    }
    init()
  }, [loadKb, loadDocs])

  useEffect(() => {
    const urlTab = searchParams.get('tab')
    if (urlTab === 'chat') {
      setTab('chat')
    }
  }, [searchParams])

  useEffect(() => {
    const hasPending = documents.some(
      (d) => d.status === 'PENDING' || d.status === 'PROCESSING',
    )
    if (!hasPending || !kbId) return

    const id = setInterval(loadDocs, 3000)
    return () => clearInterval(id)
  }, [documents, kbId, loadDocs])

  const switchTab = (next: Tab) => {
    setTab(next)
    if (next === 'chat') {
      setSearchParams({ tab: 'chat' }, { replace: true })
    } else {
      setSearchParams({}, { replace: true })
    }
  }

  const handleUpload = async (
    files: File[],
    onProgress?: (current: number, total: number, fileName: string) => void,
  ) => {
    if (!kbId || files.length === 0) return
    let succeeded = 0
    let failed = 0

    for (let i = 0; i < files.length; i++) {
      const file = files[i]
      onProgress?.(i + 1, files.length, file.name)
      try {
        const doc = await uploadDocument(kbId, file)
        if (doc.status === 'FAILED') {
          failed++
          showError(doc.errorMessage ?? `上传失败：${file.name}`)
          continue
        }
        succeeded++
      } catch (e) {
        failed++
        showError(
          e instanceof Error ? e.message : `上传失败：${file.name}`,
        )
      }
    }

    await loadDocs()

    if (failed === 0) {
      showSuccess(
        files.length === 1
          ? `${files[0].name} 上传成功，正在处理…`
          : `成功上传 ${succeeded} 个文件，正在处理…`,
      )
    } else if (succeeded > 0) {
      showSuccess(`成功 ${succeeded} 个，失败 ${failed} 个`)
    }
  }

  const handleDelete = async (doc: Document) => {
    if (!kbId) return
    if (!confirm(`确定删除文档「${doc.fileName}」？此操作不可恢复。`)) return
    setDeletingId(doc.id)
    try {
      await deleteDocument(kbId, doc.id)
      showSuccess('已删除')
      await loadDocs()
    } catch (e) {
      showError(e instanceof Error ? e.message : '删除失败')
    } finally {
      setDeletingId(null)
    }
  }

  if (loading || !kb) {
    return (
      <AppLayout>
        <div className="flex justify-center py-16">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
        </div>
      </AppLayout>
    )
  }

  return (
    <AppLayout>
      <div className="mb-6">
        <Link
          to="/"
          className="mb-4 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="h-4 w-4" />
          返回列表
        </Link>
        <h1 className="text-2xl font-bold">{kb.name}</h1>
        {kb.description && (
          <p className="mt-1 text-sm text-muted-foreground">{kb.description}</p>
        )}
      </div>

      <div className="mb-6 flex gap-2 border-b">
        <Button
          variant="ghost"
          className={cn('rounded-none border-b-2', tab === 'documents' && 'border-primary')}
          onClick={() => switchTab('documents')}
        >
          <FileText className="h-4 w-4" />
          文档管理
          <span className="ml-1.5 rounded-full bg-muted px-2 py-0.5 text-xs font-normal text-muted-foreground">
            {documents.length}
          </span>
        </Button>
        <Button
          variant="ghost"
          className={cn('rounded-none border-b-2', tab === 'chat' && 'border-primary')}
          onClick={() => switchTab('chat')}
        >
          <MessageSquare className="h-4 w-4" />
          智能问答
          <span
            className={cn(
              'ml-1.5 rounded-full px-2 py-0.5 text-xs font-normal',
              completedCount > 0
                ? 'bg-primary/10 text-primary'
                : 'bg-muted text-muted-foreground',
            )}
          >
            {completedCount} 可用
          </span>
        </Button>
      </div>

      {tab === 'documents' ? (
        <div className="space-y-6">
          <UploadZone onUpload={handleUpload} />
          <DocTable
            documents={documents}
            jobStages={jobStages}
            onDelete={handleDelete}
            deletingId={deletingId}
          />
        </div>
      ) : (
        <ChatPanel kbId={kbId!} completedDocCount={completedCount} />
      )}
    </AppLayout>
  )
}
