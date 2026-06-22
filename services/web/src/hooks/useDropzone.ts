import { useCallback, useRef, useState, type DragEvent } from 'react'

interface DropzoneOptions {
  onDrop: (files: File[]) => void
  disabled?: boolean
  accept?: Record<string, string[]>
  multiple?: boolean
}

export function useDropzone({ onDrop, disabled, accept, multiple }: DropzoneOptions) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [isDragActive, setIsDragActive] = useState(false)

  const handleFiles = useCallback(
    (fileList: FileList | null) => {
      if (!fileList || disabled) return
      const files = Array.from(fileList)
      if (accept) {
        const exts = Object.values(accept).flat()
        const filtered = files.filter((f) =>
          exts.some((ext) => f.name.toLowerCase().endsWith(ext)),
        )
        if (filtered.length) onDrop(filtered)
      } else {
        onDrop(files)
      }
    },
    [accept, disabled, onDrop],
  )

  const onDragOver = (e: DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
    if (!disabled) setIsDragActive(true)
  }

  const onDragLeave = (e: DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
    setIsDragActive(false)
  }

  const onDropEvent = (e: DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
    setIsDragActive(false)
    handleFiles(e.dataTransfer.files)
  }

  const open = () => {
    if (!disabled) inputRef.current?.click()
  }

  const onInputChange = () => handleFiles(inputRef.current?.files ?? null)

  return {
    inputRef,
    isDragActive,
    multiple: multiple ?? false,
    open,
    onInputChange,
    onDragOver,
    onDragLeave,
    onDropEvent,
  }
}
