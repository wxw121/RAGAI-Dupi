import { useEffect, useState } from 'react'
import { checkHealth } from '@/api/client'
import { cn } from '@/lib/utils'
import { ClipboardList, Database, Home, LogOut, Users } from 'lucide-react'
import { Link } from 'react-router-dom'

export function AppLayout({ children, onLogout }: { children: React.ReactNode; onLogout?: () => void }) {
  const [healthy, setHealthy] = useState<boolean | null>(null)

  useEffect(() => {
    const poll = async () => {
      setHealthy(await checkHealth())
    }
    poll()
    const id = setInterval(poll, 15000)
    return () => clearInterval(id)
  }, [])

  return (
    <div className="min-h-screen bg-background text-foreground">
      <div className="flex min-h-screen">
        <aside className="hidden w-[260px] shrink-0 border-r border-border bg-[#f9f9f9] px-3 py-3 md:flex md:flex-col">
          <Link
            to="/"
            className="mb-3 flex h-10 items-center gap-2 rounded-xl px-3 text-sm font-semibold hover:bg-muted"
          >
            <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-foreground text-background">
              <Database className="h-4 w-4" />
            </span>
            dupi-RAG
          </Link>
          <nav className="space-y-1">
            <Link
              to="/"
              className="flex h-10 items-center gap-2 rounded-xl px-3 text-sm text-foreground hover:bg-muted"
            >
              <Home className="h-4 w-4" />
              知识库
            </Link>
            <Link
              to="/ops/audit-logs"
              className="flex h-10 items-center gap-2 rounded-xl px-3 text-sm text-foreground hover:bg-muted"
            >
              <ClipboardList className="h-4 w-4" />
              审计日志
            </Link>
            <Link
              to="/ops/accounts"
              className="flex h-10 items-center gap-2 rounded-xl px-3 text-sm text-foreground hover:bg-muted"
            >
              <Users className="h-4 w-4" />
              账号管理
            </Link>
          </nav>
          {onLogout && (
            <button
              type="button"
              onClick={onLogout}
              className="mt-2 flex h-10 items-center gap-2 rounded-xl px-3 text-sm text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              <LogOut className="h-4 w-4" />
              退出登录
            </button>
          )}
          <div className="mt-auto flex items-center gap-2 rounded-xl px-3 py-2 text-xs text-muted-foreground">
            <span
              className={cn(
                'h-2 w-2 rounded-full',
                healthy === null && 'bg-muted-foreground',
                healthy === true && 'bg-emerald-500',
                healthy === false && 'bg-red-500',
              )}
            />
            {healthy === null ? '检查中...' : healthy ? '服务正常' : '服务异常'}
          </div>
        </aside>
        <main className="min-w-0 flex-1">{children}</main>
      </div>
    </div>
  )
}
