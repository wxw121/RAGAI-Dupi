import type { Document, IngestDiagnosis, IngestJob } from '@/types'
import { formatBytes, formatDate } from '@/lib/utils'
import { Badge, statusBadgeVariant } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Eye, Loader2, Trash2 } from 'lucide-react'

interface DocTableProps {
  documents: Document[]
  jobStages: Record<string, string | null>
  ingestJobs?: IngestJob[]
  onInspect?: (doc: Document) => void
  onDelete?: (doc: Document) => void
  deletingId?: string | null
}

export function DocTable({
  documents,
  jobStages,
  ingestJobs = [],
  onInspect,
  onDelete,
  deletingId,
}: DocTableProps) {
  const jobsByDocId = new Map<string, IngestJob>()
  ingestJobs.forEach((job) => {
    if (!jobsByDocId.has(job.docId)) jobsByDocId.set(job.docId, job)
  })

  if (documents.length === 0) {
    return (
      <p className="rounded-3xl border border-border bg-muted/30 py-10 text-center text-sm text-muted-foreground">
        暂无文档，请上传文件开始构建知识库
      </p>
    )
  }

  return (
    <div className="chatgpt-scrollbar overflow-x-auto rounded-3xl border border-border bg-background">
      <table className="w-full text-sm">
        <thead className="border-b bg-muted/50">
          <tr>
            <th className="px-5 py-3 text-left font-medium">文件名</th>
            <th className="px-5 py-3 text-left font-medium">大小</th>
            <th className="px-5 py-3 text-left font-medium">状态</th>
            <th className="px-5 py-3 text-left font-medium">阶段</th>
            <th className="px-5 py-3 text-left font-medium">上传时间</th>
            {(onInspect || onDelete) && <th className="px-5 py-3 text-right font-medium">操作</th>}
          </tr>
        </thead>
        <tbody>
          {documents.map((doc) => {
            const job = doc.currentJob ?? jobsByDocId.get(doc.id)
            const diagnosis = job?.diagnosis
            return (
              <tr key={doc.id} className="border-b transition-colors last:border-0 hover:bg-muted/40">
                <td className="px-5 py-3">
                  <p className="font-medium">{doc.fileName}</p>
                  {diagnosis && <DiagnosisBlock diagnosis={diagnosis} />}
                </td>
                <td className="px-5 py-3 text-muted-foreground">{formatBytes(doc.fileSize)}</td>
                <td className="px-5 py-3">
                  <Badge variant={statusBadgeVariant(doc.status)}>{doc.status}</Badge>
                </td>
                <td className="px-5 py-3 text-muted-foreground">
                  {job?.stage ?? jobStages[doc.id] ?? '-'}
                </td>
                <td className="px-5 py-3 text-muted-foreground">{formatDate(doc.createdAt)}</td>
                {(onInspect || onDelete) && (
                  <td className="px-5 py-3 text-right">
                    <div className="inline-flex items-center justify-end gap-1">
                      {onInspect && (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => onInspect(doc)}
                          aria-label={`Inspect ${doc.fileName}`}
                          title="Inspect index detail"
                        >
                          <Eye className="h-4 w-4 text-muted-foreground" />
                        </Button>
                      )}
                      {onDelete && (
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={deletingId === doc.id}
                          onClick={() => onDelete(doc)}
                          aria-label={`删除 ${doc.fileName}`}
                        >
                          {deletingId === doc.id ? (
                            <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
                          ) : (
                            <Trash2 className="h-4 w-4 text-destructive" />
                          )}
                        </Button>
                      )}
                    </div>
                  </td>
                )}
              </tr>
            )
          })}
        </tbody>
      </table>
      {documents.some((d) => d.errorMessage) && (
        <div className="border-t bg-red-50 p-4 text-sm text-red-700">
          {documents
            .filter((d) => d.errorMessage)
            .map((d) => (
              <p key={d.id}>
                {d.fileName}: {d.errorMessage}
              </p>
            ))}
        </div>
      )}
    </div>
  )
}

function DiagnosisBlock({ diagnosis }: { diagnosis: IngestDiagnosis }) {
  const className = diagnosisClassName(diagnosis)
  return (
    <div className={className}>
      <p className="font-medium">{diagnosis.summary}</p>
      <p className="mt-0.5">
        {diagnosis.nextAction}
        {diagnosis.retryable && <span className="ml-2 font-medium">可重试</span>}
        {diagnosis.stalled && <span className="ml-2 font-medium">已停滞</span>}
      </p>
    </div>
  )
}

function diagnosisClassName(diagnosis: IngestDiagnosis): string {
  const base = 'mt-2 max-w-xl rounded-lg border px-2.5 py-2 text-xs leading-5'
  if (diagnosis.severity === 'error') {
    return `${base} border-red-200 bg-red-50 text-red-700`
  }
  if (diagnosis.severity === 'warning') {
    return `${base} border-amber-200 bg-amber-50 text-amber-800`
  }
  return `${base} border-sky-200 bg-sky-50 text-sky-800`
}
