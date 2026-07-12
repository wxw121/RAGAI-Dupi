import { useCallback, useState } from 'react'
import { useDropzone } from '@/hooks/useDropzone'
import { Upload } from 'lucide-react'
import { cn } from '@/lib/utils'

export type UploadProgressFn = (current: number, total: number, fileName: string) => void

interface UploadZoneProps {
  onUpload: (files: File[], onProgress?: UploadProgressFn) => Promise<void>
  disabled?: boolean
}

export function UploadZone({ onUpload, disabled }: UploadZoneProps) {
  const [uploading, setUploading] = useState(false)
  const [progress, setProgress] = useState<{
    current: number
    total: number
    fileName: string
  } | null>(null)

  const onDrop = useCallback(
    async (files: File[]) => {
      if (!files.length || disabled || uploading) return
      setUploading(true)
      try {
        await onUpload(files, (current, total, fileName) => {
          setProgress({ current, total, fileName })
        })
      } finally {
        setUploading(false)
        setProgress(null)
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
    </div>
  )
}
