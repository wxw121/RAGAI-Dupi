import { useCallback, useState } from 'react'
import { useDropzone } from '@/hooks/useDropzone'
import { Upload } from 'lucide-react'
import { cn } from '@/lib/utils'

interface UploadZoneProps {
  onUpload: (file: File) => Promise<void>
  disabled?: boolean
}

export function UploadZone({ onUpload, disabled }: UploadZoneProps) {
  const [uploading, setUploading] = useState(false)

  const onDrop = useCallback(
    async (files: File[]) => {
      const file = files[0]
      if (!file || disabled || uploading) return
      setUploading(true)
      try {
        await onUpload(file)
      } finally {
        setUploading(false)
      }
    },
    [disabled, onUpload, uploading],
  )

  const {
    inputRef,
    isDragActive,
    open,
    onInputChange,
    onDragOver,
    onDragLeave,
    onDropEvent,
  } = useDropzone({
    onDrop,
    disabled: disabled || uploading,
    accept: {
      'application/pdf': ['.pdf'],
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document': ['.docx'],
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': ['.xlsx'],
      'text/plain': ['.txt'],
      'text/markdown': ['.md'],
    },
  })

  return (
    <div
      onClick={open}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={onDropEvent}
      className={cn(
        'flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed p-8 transition-colors',
        isDragActive && 'border-primary bg-primary/5',
        (disabled || uploading) && 'cursor-not-allowed opacity-50',
      )}
    >
      <input
        ref={inputRef}
        type="file"
        className="hidden"
        disabled={disabled || uploading}
        onChange={onInputChange}
      />
      <Upload className="mb-2 h-8 w-8 text-muted-foreground" />
      <p className="text-sm font-medium">
        {uploading ? '上传中…' : isDragActive ? '松开以上传' : '拖拽文件到此处，或点击选择'}
      </p>
      <p className="mt-1 text-xs text-muted-foreground">支持 PDF、DOCX、TXT、MD、Excel</p>
    </div>
  )
}
