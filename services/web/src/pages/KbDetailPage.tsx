import { useCallback, useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  getKnowledgeBase,
  listIngestJobs,
  listOpsMetadata,
  listVectorCleanupTasks,
  reindexKnowledgeBase,
  retryIngestJob,
  retryVectorCleanupTask,
} from '@/api/knowledgeBase'
import { deleteDocument, getDocumentIndexDetail, getIngestJob, listDocuments, uploadDocuments } from '@/api/documents'
import type {
  Document,
  DocumentIndexDetail,
  IngestJob,
  KnowledgeBase,
  OpsGuardrails,
  VectorCleanupTask,
} from '@/types'
import { AppLayout } from '@/components/AppLayout'
import { ChatPanel } from '@/components/ChatPanel'
import { DocTable } from '@/components/DocTable'
import { DocumentIndexDetailPanel } from '@/components/DocumentIndexDetailPanel'
import { RagEvalPanel } from '@/components/RagEvalPanel'
import { UploadZone } from '@/components/UploadZone'
import { useToast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Badge, statusBadgeVariant } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'
import {
  AlertTriangle,
  ArrowLeft,
  BarChart3,
  FileText,
  Loader2,
  MessageSquare,
  RefreshCw,
  RotateCcw,
} from 'lucide-react'

type Tab = 'documents' | 'chat' | 'eval'

export function KbDetailPage({ onLogout }: { onLogout?: () => void }) {
  const { kbId } = useParams<{ kbId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const [kb, setKb] = useState<KnowledgeBase | null>(null)
  const [documents, setDocuments] = useState<Document[]>([])
  const [ingestJobs, setIngestJobs] = useState<IngestJob[]>([])
  const [vectorCleanupTasks, setVectorCleanupTasks] = useState<VectorCleanupTask[]>([])
  const [guardrails, setGuardrails] = useState<OpsGuardrails | null>(null)
  const [jobStages, setJobStages] = useState<Record<string, string | null>>({})
  const [tab, setTab] = useState<Tab>(() => {
    const initialTab = searchParams.get('tab')
    return initialTab === 'chat' || initialTab === 'eval' ? initialTab : 'documents'
  })
  const [loading, setLoading] = useState(true)
  const [deletingId, setDeletingId] = useState<string | null>(null)
  const [reindexing, setReindexing] = useState(false)
  const [retryingJobId, setRetryingJobId] = useState<string | null>(null)
  const [retryingCleanupTaskId, setRetryingCleanupTaskId] = useState<string | null>(null)
  const [indexDetail, setIndexDetail] = useState<DocumentIndexDetail | null>(null)
  const [indexDetailLoadingId, setIndexDetailLoadingId] = useState<string | null>(null)
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

  const loadJobs = useCallback(async () => {
    if (!kbId) return
    try {
      const jobs = await listIngestJobs(kbId)
      setIngestJobs(jobs)
      setJobStages((current) => ({
        ...current,
        ...Object.fromEntries(jobs.map((job) => [job.docId, job.stage])),
      }))
    } catch (e) {
      showError(e instanceof Error ? e.message : '加载摄入任务失败')
    }
  }, [kbId, showError])

  const loadVectorCleanupTasks = useCallback(async () => {
    try {
      setVectorCleanupTasks(await listVectorCleanupTasks())
    } catch (e) {
      showError(e instanceof Error ? e.message : '加载向量清理任务失败')
    }
  }, [showError])

  const loadGuardrails = useCallback(async () => {
    try {
      const metadata = await listOpsMetadata()
      setGuardrails(metadata.guardrails ?? null)
    } catch {
      setGuardrails(null)
    }
  }, [])

  useEffect(() => {
    const init = async () => {
      setLoading(true)
      await loadKb()
      await loadDocs()
      await loadJobs()
      await loadVectorCleanupTasks()
      await loadGuardrails()
      setLoading(false)
    }
    init()
  }, [loadGuardrails, loadKb, loadDocs, loadJobs, loadVectorCleanupTasks])

  useEffect(() => {
    const urlTab = searchParams.get('tab')
    if (urlTab === 'chat' || urlTab === 'eval') {
      setTab(urlTab)
    }
  }, [searchParams])

  useEffect(() => {
    const hasPending = documents.some(
      (d) => d.status === 'PENDING' || d.status === 'PROCESSING',
    )
    const hasRunningJob = ingestJobs.some((job) => job.status === 'PENDING' || job.status === 'PROCESSING')
    if ((!hasPending && !hasRunningJob) || !kbId) return

    const id = setInterval(() => {
      loadDocs()
      loadJobs()
      loadVectorCleanupTasks()
    }, 3000)
    return () => clearInterval(id)
  }, [documents, ingestJobs, kbId, loadDocs, loadJobs, loadVectorCleanupTasks])

  const switchTab = (next: Tab) => {
    setTab(next)
    if (next === 'chat' || next === 'eval') {
      setSearchParams({ tab: next }, { replace: true })
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

    try {
      onProgress?.(
        1,
        files.length,
        files.length === 1 ? files[0].name : `${files.length} 个文件`,
      )
      const batch = await uploadDocuments(kbId, files)
      failed = batch.failed
      succeeded = batch.succeeded
      batch.results
        .filter((result) => !result.success)
        .forEach((result) => showError(result.errorMessage ?? `上传失败：${result.fileName}`))
    } catch (e) {
      failed = files.length
      showError(e instanceof Error ? e.message : '批量上传失败')
    }

    await loadDocs()
    await loadJobs()

    if (failed === 0) {
      showSuccess(
        files.length === 1
          ? `${files[0].name} 上传成功，正在处理...`
          : `成功上传 ${succeeded} 个文件，正在处理...`,
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
      await loadJobs()
    } catch (e) {
      showError(e instanceof Error ? e.message : '删除失败')
    } finally {
      setDeletingId(null)
    }
  }

  const handleInspectDocument = async (doc: Document) => {
    if (!kbId) return
    setIndexDetailLoadingId(doc.id)
    try {
      setIndexDetail(await getDocumentIndexDetail(kbId, doc.id))
    } catch (e) {
      showError(e instanceof Error ? e.message : '加载索引详情失败')
    } finally {
      setIndexDetailLoadingId(null)
    }
  }

  const handleReindex = async () => {
    if (!kbId) return
    if (!confirm('确定重建索引？系统会清理当前向量并重新摄入所有文档。')) return
    setReindexing(true)
    try {
      const jobs = await reindexKnowledgeBase(kbId)
      showSuccess(`已创建 ${jobs.length} 个重建任务`)
      await loadKb()
      await loadDocs()
      await loadJobs()
    } catch (e) {
      showError(e instanceof Error ? e.message : '重建索引失败')
    } finally {
      setReindexing(false)
    }
  }

  const handleRetryJob = async (job: IngestJob) => {
    if (!kbId) return
    setRetryingJobId(job.id)
    try {
      await retryIngestJob(kbId, job.id)
      showSuccess('已重新入队')
      await loadDocs()
      await loadJobs()
    } catch (e) {
      showError(e instanceof Error ? e.message : '重试任务失败')
    } finally {
      setRetryingJobId(null)
    }
  }

  const handleRetryCleanupTask = async (task: VectorCleanupTask) => {
    setRetryingCleanupTaskId(task.id)
    try {
      await retryVectorCleanupTask(task.id)
      showSuccess('已重试向量清理任务')
      await loadVectorCleanupTasks()
    } catch (e) {
      showError(e instanceof Error ? e.message : '重试向量清理任务失败')
    } finally {
      setRetryingCleanupTaskId(null)
    }
  }

  if (loading || !kb) {
    return (
      <AppLayout onLogout={onLogout}>
        <div className="flex justify-center py-16">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
        </div>
      </AppLayout>
    )
  }

  return (
    <AppLayout onLogout={onLogout}>
      <div className="border-b border-border px-4 py-3 md:px-6">
        <Link
          to="/"
          className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground md:hidden"
        >
          <ArrowLeft className="h-4 w-4" />
          返回列表
        </Link>
        <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
          <div className="min-w-0">
            <h1 className="truncate text-base font-semibold">{kb.name}</h1>
            {kb.description && (
              <p className="mt-0.5 truncate text-sm text-muted-foreground">{kb.description}</p>
            )}
            {!kb.embeddingConfigCurrent && kb.embeddingConfigWarning && (
              <p className="mt-2 flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800">
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
                <span>{kb.embeddingConfigWarning}</span>
              </p>
            )}
          </div>
          <div className="flex w-full gap-1 rounded-xl bg-muted p-1 md:w-auto">
            <Button
              variant="ghost"
              size="sm"
              className={cn(
                'flex-1 rounded-lg px-3 md:flex-none',
                tab === 'documents' && 'bg-background shadow-sm',
              )}
              onClick={() => switchTab('documents')}
            >
              <FileText className="h-4 w-4" />
              文档
              <span className="ml-1 rounded-full bg-secondary px-1.5 py-0.5 text-[11px] font-normal text-muted-foreground">
                {documents.length}
              </span>
            </Button>
            <Button
              variant="ghost"
              size="sm"
              className={cn(
                'flex-1 rounded-lg px-3 md:flex-none',
                tab === 'chat' && 'bg-background shadow-sm',
              )}
              onClick={() => switchTab('chat')}
            >
              <MessageSquare className="h-4 w-4" />
              问答
              <span className="ml-1 rounded-full bg-secondary px-1.5 py-0.5 text-[11px] font-normal text-muted-foreground">
                {completedCount}
              </span>
            </Button>
            <Button
              variant="ghost"
              size="sm"
              className={cn(
                'flex-1 rounded-lg px-3 md:flex-none',
                tab === 'eval' && 'bg-background shadow-sm',
              )}
              onClick={() => switchTab('eval')}
            >
              <BarChart3 className="h-4 w-4" />
              RAG 评估
            </Button>
          </div>
        </div>
      </div>

      {tab === 'documents' ? (
        <div className="mx-auto max-w-5xl space-y-6 px-4 py-6 md:px-8">
          <div className="rounded-3xl border border-border bg-background p-4">
            <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
              <div>
                <h2 className="text-sm font-semibold">索引维护</h2>
                <p className="mt-1 text-xs text-muted-foreground">
                  切换 Embedding 配置后，可重建索引并在这里处理失败或死信任务。
                </p>
              </div>
              <Button
                variant={!kb.embeddingConfigCurrent ? 'default' : 'outline'}
                size="sm"
                disabled={reindexing || documents.length === 0}
                onClick={handleReindex}
              >
                {reindexing ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <RefreshCw className="h-4 w-4" />
                )}
                重建索引
              </Button>
            </div>

            {ingestJobs.length > 0 && (
              <div className="mt-4 overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="border-b text-xs text-muted-foreground">
                    <tr>
                      <th className="py-2 pr-4 text-left font-medium">任务</th>
                      <th className="py-2 pr-4 text-left font-medium">状态</th>
                      <th className="py-2 pr-4 text-left font-medium">阶段</th>
                      <th className="py-2 pr-4 text-left font-medium">重试</th>
                      <th className="py-2 text-right font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {ingestJobs.slice(0, 8).map((job) => (
                      <tr key={job.id} className="border-b last:border-0">
                        <td className="py-2 pr-4 font-mono text-xs text-muted-foreground">
                          {job.id.slice(0, 8)}
                          {job.errorMessage && (
                            <p className="mt-1 max-w-md truncate font-sans text-xs text-destructive">
                              {job.errorMessage}
                            </p>
                          )}
                        </td>
                        <td className="py-2 pr-4">
                          <Badge variant={statusBadgeVariant(job.status)}>{job.status}</Badge>
                        </td>
                        <td className="py-2 pr-4 text-muted-foreground">{job.stage ?? '—'}</td>
                        <td className="py-2 pr-4 text-muted-foreground">{job.retryCount}</td>
                        <td className="py-2 text-right">
                          {(job.status === 'FAILED' || job.status === 'DEAD_LETTER') && (
                            <Button
                              variant="ghost"
                              size="sm"
                              disabled={retryingJobId === job.id}
                              onClick={() => handleRetryJob(job)}
                            >
                              {retryingJobId === job.id ? (
                                <Loader2 className="h-4 w-4 animate-spin" />
                              ) : (
                                <RotateCcw className="h-4 w-4" />
                              )}
                              重试
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {vectorCleanupTasks.length > 0 && (
              <div className="mt-6 overflow-x-auto border-t pt-4">
                <div className="mb-2 flex items-center justify-between gap-3">
                  <h3 className="text-xs font-semibold text-muted-foreground">向量清理任务</h3>
                  <Button variant="ghost" size="sm" onClick={loadVectorCleanupTasks}>
                    <RefreshCw className="h-4 w-4" />
                    刷新
                  </Button>
                </div>
                <table className="w-full text-sm">
                  <thead className="border-b text-xs text-muted-foreground">
                    <tr>
                      <th className="py-2 pr-4 text-left font-medium">目标</th>
                      <th className="py-2 pr-4 text-left font-medium">状态</th>
                      <th className="py-2 pr-4 text-left font-medium">尝试</th>
                      <th className="py-2 pr-4 text-left font-medium">错误</th>
                      <th className="py-2 text-right font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {vectorCleanupTasks.slice(0, 8).map((task) => (
                      <tr key={task.id} className="border-b last:border-0">
                        <td className="py-2 pr-4">
                          <p className="text-xs font-medium">{task.targetType}</p>
                          <p className="font-mono text-xs text-muted-foreground">{task.targetId.slice(0, 8)}</p>
                        </td>
                        <td className="py-2 pr-4">
                          <Badge variant={statusBadgeVariant(task.status)}>{task.status}</Badge>
                        </td>
                        <td className="py-2 pr-4 text-muted-foreground">{task.attemptCount}</td>
                        <td className="py-2 pr-4">
                          <p className="max-w-md truncate text-xs text-destructive">
                            {task.lastError ?? '-'}
                          </p>
                        </td>
                        <td className="py-2 text-right">
                          {(task.status === 'PENDING' || task.status === 'FAILED') && (
                            <Button
                              variant="ghost"
                              size="sm"
                              disabled={retryingCleanupTaskId === task.id}
                              onClick={() => handleRetryCleanupTask(task)}
                            >
                              {retryingCleanupTaskId === task.id ? (
                                <Loader2 className="h-4 w-4 animate-spin" />
                              ) : (
                                <RotateCcw className="h-4 w-4" />
                              )}
                              重试
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
          <UploadZone onUpload={handleUpload} guardrails={guardrails} />
          <DocTable
            documents={documents}
            jobStages={jobStages}
            ingestJobs={ingestJobs}
            onInspect={handleInspectDocument}
            onDelete={handleDelete}
            deletingId={deletingId}
          />
          {indexDetailLoadingId && (
            <div className="flex items-center gap-2 rounded-lg border border-border bg-background p-4 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" />
              Loading index detail...
            </div>
          )}
          {indexDetail && <DocumentIndexDetailPanel detail={indexDetail} />}
        </div>
      ) : tab === 'chat' ? (
        <ChatPanel kbId={kbId!} completedDocCount={completedCount} />
      ) : (
        <RagEvalPanel kbId={kbId!} />
      )}
    </AppLayout>
  )
}
