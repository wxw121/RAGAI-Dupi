import { useState } from 'react'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { clearAuthToken, getAuthToken, login } from '@/api/client'
import { ToastProvider } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { KbListPage } from '@/pages/KbListPage'
import { KbDetailPage } from '@/pages/KbDetailPage'
import { OpsAccountsPage } from '@/pages/OpsAccountsPage'
import { OpsAuditPage } from '@/pages/OpsAuditPage'
import { OpsRolesPage } from '@/pages/OpsRolesPage'
import { Database, Loader2 } from 'lucide-react'

function LoginPage({ onAuthenticated }: { onAuthenticated: () => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleLogin = async () => {
    if (!username.trim() || !password) return
    setLoading(true)
    setError('')
    try {
      await login(username.trim(), password)
      onAuthenticated()
    } catch (e) {
      setError(e instanceof Error ? e.message : '登录失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="w-full max-w-sm space-y-6">
        <div className="space-y-3 text-center">
          <span className="mx-auto flex h-11 w-11 items-center justify-center rounded-xl bg-foreground text-background">
            <Database className="h-5 w-5" />
          </span>
          <div>
            <h1 className="text-2xl font-semibold">登录 dupi-RAG</h1>
            <p className="mt-1 text-sm text-muted-foreground">使用内置账号访问知识库与运维功能</p>
          </div>
        </div>

        <div className="space-y-3">
          <Input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="用户名" />
          <Input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleLogin()
            }}
            placeholder="密码"
          />
          {error && <p className="text-sm text-destructive">{error}</p>}
          <Button className="w-full" onClick={handleLogin} disabled={loading || !username.trim() || !password}>
            {loading && <Loader2 className="h-4 w-4 animate-spin" />}
            登录
          </Button>
        </div>
      </div>
    </div>
  )
}

export default function App() {
  const [authenticated, setAuthenticated] = useState(() => Boolean(getAuthToken()))

  const handleLogout = () => {
    clearAuthToken()
    setAuthenticated(false)
  }

  return (
    <ToastProvider>
      {authenticated ? (
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<KbListPage onLogout={handleLogout} />} />
            <Route path="/kb/:kbId" element={<KbDetailPage onLogout={handleLogout} />} />
            <Route path="/ops/audit-logs" element={<OpsAuditPage onLogout={handleLogout} />} />
            <Route path="/ops/accounts" element={<OpsAccountsPage onLogout={handleLogout} />} />
            <Route path="/ops/roles" element={<OpsRolesPage onLogout={handleLogout} />} />
          </Routes>
        </BrowserRouter>
      ) : (
        <LoginPage onAuthenticated={() => setAuthenticated(true)} />
      )}
    </ToastProvider>
  )
}
