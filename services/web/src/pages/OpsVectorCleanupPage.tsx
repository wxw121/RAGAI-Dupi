import { useCallback, useEffect, useState } from 'react'
import { listVectorCleanupTasks, retryVectorCleanupTask } from '@/api/knowledgeBase'
import { AppLayout } from '@/components/AppLayout'
import { useToast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Badge, statusBadgeVariant } from '@/components/ui/dialog'
import type { VectorCleanupTask } from '@/types'
import { Loader2, RefreshCw, RotateCcw } from 'lucide-react'

export function OpsVectorCleanupPage({ onLogout }: { onLogout?: () => void }) {
  const [tasks, setTasks] = useState<VectorCleanupTask[]>([])
  const [loading, setLoading] = useState(true)
  const [retryingId, setRetryingId] = useState<string | null>(null)
  const { showError, showSuccess } = useToast()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setTasks(await listVectorCleanupTasks())
    } catch (error) {
      showError(error instanceof Error ? error.message : '加载向量清理任务失败')
    } finally {
      setLoading(false)
    }
  }, [showError])

  useEffect(() => {
    void load()
  }, [load])

  const retry = async (task: VectorCleanupTask) => {
    setRetryingId(task.id)
    try {
      await retryVectorCleanupTask(task.id)
      showSuccess('向量清理任务已处理')
      await load()
    } catch (error) {
      showError(error instanceof Error ? error.message : '重试向量清理任务失败')
    } finally {
      setRetryingId(null)
    }
  }

  return (
    <AppLayout onLogout={onLogout}>
      <div className="mx-auto max-w-6xl space-y-6 px-4 py-6 md:px-8">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">向量清理任务</h1>
            <p className="mt-1 text-sm text-muted-foreground">集中查看并处理全部知识库的待清理和失败任务。</p>
          </div>
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
            刷新
          </Button>
        </div>

        <div className="overflow-x-auto rounded-3xl border border-border bg-background p-4">
          {loading && tasks.length === 0 ? (
            <div className="flex justify-center py-12"><Loader2 className="h-6 w-6 animate-spin text-muted-foreground" /></div>
          ) : tasks.length === 0 ? (
            <p className="py-12 text-center text-sm text-muted-foreground">当前没有待处理的向量清理任务</p>
          ) : (
            <table className="w-full text-sm">
              <thead className="border-b text-xs text-muted-foreground">
                <tr>
                  <th className="py-2 pr-4 text-left font-medium">目标</th>
                  <th className="py-2 pr-4 text-left font-medium">知识库</th>
                  <th className="py-2 pr-4 text-left font-medium">状态</th>
                  <th className="py-2 pr-4 text-left font-medium">尝试</th>
                  <th className="py-2 pr-4 text-left font-medium">错误</th>
                  <th className="py-2 text-right font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                {tasks.map((task) => (
                  <tr key={task.id} className="border-b last:border-0">
                    <td className="py-3 pr-4">
                      <p className="text-xs font-medium">{task.targetType}</p>
                      <p className="font-mono text-xs text-muted-foreground">{task.targetId.slice(0, 8)}</p>
                    </td>
                    <td className="py-3 pr-4 font-mono text-xs text-muted-foreground">
                      {task.knowledgeBaseId?.slice(0, 8) ?? '-'}
                    </td>
                    <td className="py-3 pr-4"><Badge variant={statusBadgeVariant(task.status)}>{task.status}</Badge></td>
                    <td className="py-3 pr-4 text-muted-foreground">{task.attemptCount}</td>
                    <td className="py-3 pr-4"><p className="max-w-md truncate text-xs text-destructive">{task.lastError ?? '-'}</p></td>
                    <td className="py-3 text-right">
                      <Button variant="ghost" size="sm" disabled={retryingId === task.id} onClick={() => void retry(task)}>
                        {retryingId === task.id ? <Loader2 className="h-4 w-4 animate-spin" /> : <RotateCcw className="h-4 w-4" />}
                        重试
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </AppLayout>
  )
}
