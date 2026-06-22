import type { Document } from '@/types'
import { formatBytes, formatDate } from '@/lib/utils'
import { Badge, statusBadgeVariant } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Loader2, Trash2 } from 'lucide-react'

interface DocTableProps {
  documents: Document[]
  jobStages: Record<string, string | null>
  onDelete?: (doc: Document) => void
  deletingId?: string | null
}

export function DocTable({ documents, jobStages, onDelete, deletingId }: DocTableProps) {
  if (documents.length === 0) {
    return (
      <p className="py-8 text-center text-sm text-muted-foreground">暂无文档，请上传文件开始构建知识库</p>
    )
  }

  return (
    <div className="overflow-x-auto rounded-lg border">
      <table className="w-full text-sm">
        <thead className="border-b bg-muted/50">
          <tr>
            <th className="px-4 py-3 text-left font-medium">文件名</th>
            <th className="px-4 py-3 text-left font-medium">大小</th>
            <th className="px-4 py-3 text-left font-medium">状态</th>
            <th className="px-4 py-3 text-left font-medium">阶段</th>
            <th className="px-4 py-3 text-left font-medium">上传时间</th>
            {onDelete && <th className="px-4 py-3 text-right font-medium">操作</th>}
          </tr>
        </thead>
        <tbody>
          {documents.map((doc) => (
            <tr key={doc.id} className="border-b last:border-0">
              <td className="px-4 py-3 font-medium">{doc.fileName}</td>
              <td className="px-4 py-3 text-muted-foreground">{formatBytes(doc.fileSize)}</td>
              <td className="px-4 py-3">
                <Badge variant={statusBadgeVariant(doc.status)}>{doc.status}</Badge>
              </td>
              <td className="px-4 py-3 text-muted-foreground">
                {jobStages[doc.id] ?? '—'}
              </td>
              <td className="px-4 py-3 text-muted-foreground">{formatDate(doc.createdAt)}</td>
              {onDelete && (
                <td className="px-4 py-3 text-right">
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
                </td>
              )}
            </tr>
          ))}
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
