import { useCallback, useEffect, useMemo, useState } from 'react'
import { exportAuditLogs, listAuditAlerts, listAuditLogs } from '@/api/knowledgeBase'
import type { AuditAlert, AuditLog, AuditLogQuery } from '@/types'
import { AppLayout } from '@/components/AppLayout'
import { useToast } from '@/components/Toast'
import { Badge, statusBadgeVariant } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { formatDate } from '@/lib/utils'
import { AlertTriangle, Download, Loader2, RefreshCw, Search } from 'lucide-react'

export function OpsAuditPage({ onLogout }: { onLogout?: () => void }) {
  const [logs, setLogs] = useState<AuditLog[]>([])
  const [alerts, setAlerts] = useState<AuditAlert[]>([])
  const [tenantId, setTenantId] = useState('')
  const [action, setAction] = useState('')
  const [targetType, setTargetType] = useState('')
  const [status, setStatus] = useState('')
  const [limit, setLimit] = useState(50)
  const [loading, setLoading] = useState(true)
  const [exporting, setExporting] = useState(false)
  const { showError, showSuccess } = useToast()

  const query = useMemo<AuditLogQuery>(
    () => ({ tenantId, action, targetType, status: status as 'SUCCESS' | 'FAILED' | '', limit }),
    [action, limit, status, targetType, tenantId],
  )

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [nextLogs, nextAlerts] = await Promise.all([listAuditLogs(query), listAuditAlerts()])
      setLogs(nextLogs)
      setAlerts(nextAlerts)
    } catch (e) {
      showError(e instanceof Error ? e.message : '加载审计日志失败')
    } finally {
      setLoading(false)
    }
  }, [query, showError])

  useEffect(() => {
    load()
  }, [load])

  const handleExport = async () => {
    setExporting(true)
    try {
      const csv = await exportAuditLogs({ tenantId, action, targetType, status: status as 'SUCCESS' | 'FAILED' | '' })
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `audit-logs-${new Date().toISOString().slice(0, 10)}.csv`
      link.click()
      URL.revokeObjectURL(url)
      showSuccess('审计日志已导出')
    } catch (e) {
      showError(e instanceof Error ? e.message : '导出审计日志失败')
    } finally {
      setExporting(false)
    }
  }

  return (
    <AppLayout onLogout={onLogout}>
      <div className="mx-auto max-w-6xl space-y-6 px-4 py-6 md:px-8">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">审计日志</h1>
          <p className="mt-1 text-sm text-muted-foreground">按租户、动作、目标和状态查询运维审计记录。</p>
        </div>

        {alerts.length > 0 && (
          <div className="space-y-2">
            {alerts.map((alert) => (
              <div key={alert.code} className="flex items-start gap-3 rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
                <div>
                  <p className="font-medium">
                    {alert.severity} · {alert.code}
                  </p>
                  <p className="mt-1">{alert.message}</p>
                  <p className="mt-1 text-xs text-amber-800">
                    当前 {alert.count} 次，阈值 {alert.threshold}，窗口 {formatDate(alert.windowStart)} - {formatDate(alert.windowEnd)}
                  </p>
                </div>
              </div>
            ))}
          </div>
        )}

        <div className="rounded-3xl border border-border bg-background p-4">
          <div className="grid gap-3 md:grid-cols-5">
            <Input value={tenantId} onChange={(e) => setTenantId(e.target.value)} placeholder="租户 ID" />
            <Input value={action} onChange={(e) => setAction(e.target.value)} placeholder="动作，例如 DOCUMENT_DELETE" />
            <Input value={targetType} onChange={(e) => setTargetType(e.target.value)} placeholder="目标类型，例如 DOCUMENT" />
            <select
              value={status}
              onChange={(e) => setStatus(e.target.value)}
              className="h-10 rounded-xl border border-input bg-background px-3 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <option value="">全部状态</option>
              <option value="SUCCESS">SUCCESS</option>
              <option value="FAILED">FAILED</option>
            </select>
            <Input type="number" value={limit} min={1} max={200} onChange={(e) => setLimit(Number(e.target.value))} />
          </div>
          <div className="mt-3 flex flex-wrap justify-end gap-2">
            <Button variant="outline" onClick={load} disabled={loading}>
              {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Search className="h-4 w-4" />}
              查询
            </Button>
            <Button variant="ghost" onClick={load} disabled={loading}>
              <RefreshCw className="h-4 w-4" />
              刷新
            </Button>
            <Button variant="secondary" onClick={handleExport} disabled={exporting}>
              {exporting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />}
              导出 CSV
            </Button>
          </div>
        </div>

        <div className="overflow-x-auto rounded-3xl border border-border bg-background">
          <table className="w-full text-sm">
            <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
              <tr>
                <th className="px-4 py-3 text-left font-medium">时间</th>
                <th className="px-4 py-3 text-left font-medium">租户</th>
                <th className="px-4 py-3 text-left font-medium">动作</th>
                <th className="px-4 py-3 text-left font-medium">目标</th>
                <th className="px-4 py-3 text-left font-medium">状态</th>
                <th className="px-4 py-3 text-left font-medium">消息</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={6} className="px-4 py-10 text-center text-muted-foreground">
                    <Loader2 className="mx-auto h-6 w-6 animate-spin" />
                  </td>
                </tr>
              ) : logs.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-10 text-center text-muted-foreground">暂无审计记录</td>
                </tr>
              ) : (
                logs.map((log) => (
                  <tr key={log.id} className="border-b last:border-0">
                    <td className="whitespace-nowrap px-4 py-3 text-muted-foreground">{formatDate(log.createdAt)}</td>
                    <td className="px-4 py-3">{log.tenantId}</td>
                    <td className="px-4 py-3 font-mono text-xs">{log.action}</td>
                    <td className="px-4 py-3">
                      <p className="font-mono text-xs">{log.targetType}</p>
                      <p className="font-mono text-xs text-muted-foreground">{log.targetId ?? '-'}</p>
                    </td>
                    <td className="px-4 py-3">
                      <Badge variant={statusBadgeVariant(log.status)}>{log.status}</Badge>
                    </td>
                    <td className="max-w-sm px-4 py-3 text-muted-foreground">
                      <p className="truncate">{log.errorMessage ?? log.message ?? '-'}</p>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </AppLayout>
  )
}
