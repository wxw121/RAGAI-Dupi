import { useCallback, useState } from 'react'
import { useDropzone } from '@/hooks/useDropzone'
import { RotateCcw, Upload, X } from 'lucide-react'
import { cn, formatBytes } from '@/lib/utils'
import type { OpsGuardrails, UploadQuota } from '@/types'

export type UploadProgressFn = (
  current: number,
  total: number,
  file: File,
  status?: 'uploading' | 'uploaded' | 'failed',
  errorMessage?: string,
) => void

interface UploadZoneProps {
  onUpload: (
    files: File[],
    onProgress?: UploadProgressFn,
    signal?: AbortSignal,
    batchId?: string,
  ) => Promise<void>
  disabled?: boolean
  guardrails?: OpsGuardrails | null
  quota?: UploadQuota | null
}

interface UploadItem {
  file: File
  batchId: string
  status: 'queued' | 'uploading' | 'uploaded' | 'failed'
  errorMessage?: string
}

export function UploadZone({ onUpload, disabled, guardrails, quota }: UploadZoneProps) {
  const [uploading, setUploading] = useState(false)
  const [controller, setController] = useState<AbortController | null>(null)
  const [items, setItems] = useState<UploadItem[]>([])
  const [progress, setProgress] = useState<{
    current: number
    total: number
    fileName: string
  } | null>(null)

  const onDrop = useCallback(
    async (files: File[], retryBatchId?: string) => {
      if (!files.length || disabled || uploading) return
      const batchId = retryBatchId ?? createUploadBatchId()
      const uploadController = new AbortController()
      setController(uploadController)
      setItems(files.map((file) => ({ file, batchId, status: 'queued' })))
      setUploading(true)
      try {
        await onUpload(files, (current, total, file, status = 'uploading', errorMessage) => {
          setProgress({ current, total, fileName: file.name })
          setItems((currentItems) => currentItems.map((item) => (
            item.file === file
              ? { ...item, status, errorMessage }
              : item
          )))
        }, uploadController.signal, batchId)
      } finally {
        setUploading(false)
        setProgress(null)
        setController(null)
      }
    },
    [disabled, onUpload, uploading],
  )

  const {
    inputRef,
    isDragActive,
    multiple,
    open,
    onInputChange,
    onDragOver,
    onDragLeave,
    onDropEvent,
  } = useDropzone({
    onDrop,
    disabled: disabled || uploading,
    multiple: true,
    accept: {
      'application/pdf': ['.pdf'],
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document': ['.docx'],
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': ['.xlsx'],
      'text/plain': ['.txt'],
      'text/markdown': ['.md'],
    },
  })

  const statusText = uploading
    ? progress
      ? `上传中 ${progress.current}/${progress.total}：${progress.fileName}`
      : '上传中…'
    : isDragActive
      ? '松开以上传'
      : '拖拽文件到此处，或点击选择'

  return (
    <div
      onClick={open}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={onDropEvent}
      className={cn(
        'flex cursor-pointer flex-col items-center justify-center rounded-3xl border border-dashed border-border bg-muted/30 p-10 text-center transition-colors',
        'hover:bg-muted/60',
        isDragActive && 'border-foreground bg-muted',
        (disabled || uploading) && 'cursor-not-allowed opacity-50',
      )}
    >
      <input
        ref={inputRef}
        type="file"
        multiple={multiple}
        className="hidden"
        disabled={disabled || uploading}
        onChange={onInputChange}
      />
      <span className="mb-3 flex h-11 w-11 items-center justify-center rounded-2xl bg-background shadow-sm">
        <Upload className="h-5 w-5 text-muted-foreground" />
      </span>
      <p className="text-sm font-medium">{statusText}</p>
      <p className="mt-1 text-xs text-muted-foreground">
        支持 PDF、DOCX、TXT、MD、Excel，可多选或拖拽多个文件
      </p>
      {guardrails && (
        <p className="mt-2 text-xs text-muted-foreground">
          rate {guardrails.uploadRateLimit.requests}/{guardrails.uploadRateLimit.windowSeconds}s · queue{' '}
          {guardrails.ingestQueue.maxPendingJobs} · max {formatBytes(guardrails.multipart.maxFileSizeBytes)}
        </p>
      )}
      {quota && (
        <p className="mt-2 text-xs text-muted-foreground">
          retained {formatBytes(quota.retainedBytesUsed)}/{formatBytes(quota.retainedBytesLimit)}
          {' · '}documents {quota.retainedDocumentsUsed}/{quota.retainedDocumentsLimit}
          {' · '}window {formatBytes(quota.windowBytesUsed)}/{formatBytes(quota.windowBytesLimit)}
        </p>
      )}
      {uploading && controller && (
        <button
          type="button"
          className="mt-3 inline-flex h-8 w-8 items-center justify-center rounded-md border border-border bg-background text-muted-foreground hover:text-foreground"
          aria-label="Cancel upload"
          title="Cancel active upload transport"
          onClick={(event) => {
            event.stopPropagation()
            controller.abort()
          }}
        >
          <X className="h-4 w-4" />
        </button>
      )}
      {items.length > 0 && (
        <ul className="mt-3 w-full max-w-xl space-y-1 text-left text-xs">
          {items.slice(0, 8).map((item, index) => (
            <li
              key={`${item.file.name}-${item.file.lastModified}-${index}`}
              className="flex min-h-8 items-center gap-2 border-t border-border/60 pt-1"
            >
              <span className="min-w-0 flex-1 truncate">{item.file.name}</span>
              <span className={cn(
                'shrink-0 text-muted-foreground',
                item.status === 'failed' && 'text-destructive',
              )}>
                {item.errorMessage ?? item.status}
              </span>
              {item.status === 'failed' && !uploading && (
                <button
                  type="button"
                  className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-md hover:bg-muted"
                  aria-label={`Retry ${item.file.name}`}
                  title="Retry upload"
                  onClick={(event) => {
                    event.stopPropagation()
                    void onDrop([item.file], item.batchId)
                  }}
                >
                  <RotateCcw className="h-3.5 w-3.5" />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function createUploadBatchId(): string {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
}
