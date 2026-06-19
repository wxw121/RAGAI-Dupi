import { useEffect, useState } from 'react'
import { checkHealth } from '@/api/client'
import { cn } from '@/lib/utils'
import { Database } from 'lucide-react'
import { Link } from 'react-router-dom'

export function AppLayout({ children }: { children: React.ReactNode }) {
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
    <div className="min-h-screen">
      <header className="sticky top-0 z-40 border-b bg-card/80 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-4">
          <Link to="/" className="flex items-center gap-2 font-semibold">
            <Database className="h-5 w-5 text-primary" />
            dupi-RAG
          </Link>
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <span
              className={cn(
                'h-2 w-2 rounded-full',
                healthy === null && 'bg-muted-foreground',
                healthy === true && 'bg-emerald-500',
                healthy === false && 'bg-red-500',
              )}
            />
            {healthy === null ? '检查中…' : healthy ? '服务正常' : '服务异常'}
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8">{children}</main>
    </div>
  )
}
