import type { DocumentIndexDetail } from '@/types'
import type { ReactNode } from 'react'
import { formatBytes, formatDate } from '@/lib/utils'
import { Badge, statusBadgeVariant } from '@/components/ui/dialog'
import { CheckCircle2, Database, FileText, HardDrive, XCircle } from 'lucide-react'

interface DocumentIndexDetailPanelProps {
  detail: DocumentIndexDetail
}

export function DocumentIndexDetailPanel({ detail }: DocumentIndexDetailPanelProps) {
  const { document, latestJob } = detail

  return (
    <section className="rounded-lg border border-border bg-background p-4" aria-label="Document index detail">
      <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <FileText className="h-4 w-4 shrink-0 text-muted-foreground" />
            <h3 className="truncate text-sm font-semibold">{document.fileName}</h3>
          </div>
          <p className="mt-1 text-xs text-muted-foreground">
            {formatBytes(document.fileSize)} · {formatDate(document.createdAt)}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Badge variant={statusBadgeVariant(document.status)}>{document.status}</Badge>
          <Badge variant={detail.indexReady ? 'success' : 'warning'}>
            {detail.indexReady ? 'index ready' : 'index not ready'}
          </Badge>
          <Badge variant={detail.objectAvailable ? 'success' : 'error'}>
            {detail.objectAvailable ? 'object available' : 'object missing'}
          </Badge>
        </div>
      </div>

      <div className="mt-4 grid gap-3 md:grid-cols-3">
        <InfoBlock
          icon={<HardDrive className="h-4 w-4" />}
          label="Object key"
          value={detail.objectKey || '-'}
        />
        <InfoBlock
          icon={<Database className="h-4 w-4" />}
          label="Chunk count"
          value={String(detail.chunkCount)}
        />
        <InfoBlock
          icon={detail.indexReady ? <CheckCircle2 className="h-4 w-4" /> : <XCircle className="h-4 w-4" />}
          label="Latest job"
          value={latestJob ? latestJob.stage ?? latestJob.status : 'no ingest job'}
        />
      </div>

      {latestJob?.diagnosis && (
        <div className="mt-4 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          <p className="font-medium">{latestJob.diagnosis.summary}</p>
          <p className="mt-1 text-xs leading-5">{latestJob.diagnosis.nextAction}</p>
          <div className="mt-2 flex flex-wrap gap-2 text-xs">
            <span>retryable: {latestJob.diagnosis.retryable ? 'yes' : 'no'}</span>
            <span>stalled: {latestJob.diagnosis.stalled ? 'yes' : 'no'}</span>
          </div>
        </div>
      )}

      {document.errorMessage && (
        <p className="mt-4 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {document.errorMessage}
        </p>
      )}

      <div className="mt-4 space-y-3">
        <h4 className="text-xs font-semibold text-muted-foreground">Chunk samples</h4>
        {detail.chunks.length === 0 ? (
          <p className="rounded-lg border border-dashed border-border p-3 text-sm text-muted-foreground">
            No chunks have been indexed for this document.
          </p>
        ) : (
          detail.chunks.map((chunk) => (
            <article key={chunk.id} className="rounded-lg border border-border p-3">
              <div className="mb-2 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                <span>#{chunk.chunkIndex}</span>
                <span>{chunk.tokenCount} tokens</span>
                {chunk.milvusId && <span>milvus: {chunk.milvusId}</span>}
              </div>
              <p className="text-sm leading-6">{chunk.contentPreview}</p>
              {Object.keys(chunk.metadata ?? {}).length > 0 && (
                <pre className="mt-2 max-h-32 overflow-auto rounded-lg border border-border bg-muted/40 p-2 text-xs text-foreground">
                  {JSON.stringify(chunk.metadata, null, 2)}
                </pre>
              )}
            </article>
          ))
        )}
      </div>
    </section>
  )
}

function InfoBlock({
  icon,
  label,
  value,
}: {
  icon: ReactNode
  label: string
  value: string
}) {
  return (
    <div className="rounded-lg border border-border bg-muted/20 p-3">
      <div className="flex items-center gap-2 text-xs font-medium text-muted-foreground">
        {icon}
        {label}
      </div>
      <p className="mt-2 break-words font-mono text-xs text-foreground">{value}</p>
    </div>
  )
}
