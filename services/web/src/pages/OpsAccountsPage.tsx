import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  createAccount,
  disableAccount,
  enableAccount,
  generatePasswordHash,
  listAccounts,
  rotateAccountToken,
  updateAccount,
} from '@/api/knowledgeBase'
import type { Account, AccountUpsertRequest } from '@/types'
import { AppLayout } from '@/components/AppLayout'
import { useToast } from '@/components/Toast'
import { Badge, Dialog } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Edit3, KeyRound, Loader2, Plus, RefreshCw, ShieldCheck, Users } from 'lucide-react'

interface AccountFormState {
  username: string
  password: string
  passwordHash: string
  tenantId: string
  role: string
  permissions: string
  knowledgeBaseIds: string
}

const emptyForm: AccountFormState = {
  username: '',
  password: '',
  passwordHash: '',
  tenantId: 'default',
  role: 'USER',
  permissions: 'KB_READ,DOCUMENT_UPLOAD,CHAT_WRITE',
  knowledgeBaseIds: '',
}

export function OpsAccountsPage({ onLogout }: { onLogout?: () => void }) {
  const [accounts, setAccounts] = useState<Account[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editing, setEditing] = useState<Account | null>(null)
  const [form, setForm] = useState<AccountFormState>(emptyForm)
  const [hashPassword, setHashPassword] = useState('')
  const [generatedHash, setGeneratedHash] = useState('')
  const { showError, showSuccess } = useToast()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setAccounts(await listAccounts())
    } catch (e) {
      showError(e instanceof Error ? e.message : '加载账号列表失败')
    } finally {
      setLoading(false)
    }
  }, [showError])

  useEffect(() => {
    load()
  }, [load])

  const title = useMemo(() => (editing ? `编辑账号 ${editing.username}` : '新建账号'), [editing])

  const openCreate = () => {
    setEditing(null)
    setForm(emptyForm)
    setDialogOpen(true)
  }

  const openEdit = (account: Account) => {
    setEditing(account)
    setForm({
      username: account.username,
      password: '',
      passwordHash: '',
      tenantId: account.tenantId,
      role: account.role,
      permissions: account.permissions.join(','),
      knowledgeBaseIds: account.knowledgeBaseIds.join(','),
    })
    setDialogOpen(true)
  }

  const submit = async () => {
    setSaving(true)
    try {
      const payload: AccountUpsertRequest = {
        username: editing ? undefined : form.username.trim(),
        password: form.password.trim() || undefined,
        passwordHash: form.passwordHash.trim() || undefined,
        tenantId: form.tenantId.trim() || 'default',
        role: form.role.trim() || 'USER',
        permissions: splitCsv(form.permissions),
        knowledgeBaseIds: splitCsv(form.knowledgeBaseIds),
      }
      if (editing) {
        await updateAccount(editing.username, payload)
        showSuccess('账号已更新')
      } else {
        await createAccount(payload)
        showSuccess('账号已创建')
      }
      setDialogOpen(false)
      await load()
    } catch (e) {
      showError(e instanceof Error ? e.message : '保存账号失败')
    } finally {
      setSaving(false)
    }
  }

  const toggleDisabled = async (account: Account) => {
    setSaving(true)
    try {
      if (account.disabled) {
        await enableAccount(account.username)
        showSuccess('账号已启用')
      } else {
        await disableAccount(account.username)
        showSuccess('账号已禁用，旧 token 已失效')
      }
      await load()
    } catch (e) {
      showError(e instanceof Error ? e.message : '账号状态更新失败')
    } finally {
      setSaving(false)
    }
  }

  const rotateToken = async (account: Account) => {
    setSaving(true)
    try {
      await rotateAccountToken(account.username)
      showSuccess('tokenVersion 已轮换，旧 token 已失效')
      await load()
    } catch (e) {
      showError(e instanceof Error ? e.message : 'tokenVersion 轮换失败')
    } finally {
      setSaving(false)
    }
  }

  const buildHash = async () => {
    if (!hashPassword.trim()) {
      showError('请输入需要生成哈希的密码')
      return
    }
    setSaving(true)
    try {
      const result = await generatePasswordHash(hashPassword)
      setGeneratedHash(result.passwordHash)
      showSuccess('密码哈希已生成')
    } catch (e) {
      showError(e instanceof Error ? e.message : '生成密码哈希失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <AppLayout onLogout={onLogout}>
      <div className="mx-auto max-w-6xl space-y-6 px-4 py-6 md:px-8">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="flex items-center gap-2 text-2xl font-semibold tracking-tight">
              <Users className="h-6 w-6" />
              账号管理
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">
              管理内置账号的租户、角色、权限、知识库授权、禁用状态和 tokenVersion。
            </p>
          </div>
          <div className="flex gap-2">
            <Button variant="outline" onClick={load} disabled={loading || saving}>
              {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
              刷新
            </Button>
            <Button onClick={openCreate} disabled={saving}>
              <Plus className="h-4 w-4" />
              新建账号
            </Button>
          </div>
        </div>

        <div className="rounded-3xl border border-border bg-background p-4">
          <div className="grid gap-3 md:grid-cols-[1fr_2fr_auto] md:items-end">
            <label className="space-y-1 text-sm">
              <span className="font-medium">密码</span>
              <Input
                type="password"
                value={hashPassword}
                onChange={(e) => setHashPassword(e.target.value)}
                placeholder="输入明文密码"
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="font-medium">生成的 PBKDF2 哈希</span>
              <Input value={generatedHash} readOnly placeholder="生成后可复制到 passwordHash" />
            </label>
            <Button variant="outline" onClick={buildHash} disabled={saving}>
              <KeyRound className="h-4 w-4" />
              生成哈希
            </Button>
          </div>
        </div>

        <div className="overflow-x-auto rounded-3xl border border-border bg-background">
          <table className="w-full text-sm">
            <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
              <tr>
                <th className="px-4 py-3 text-left font-medium">账号</th>
                <th className="px-4 py-3 text-left font-medium">租户</th>
                <th className="px-4 py-3 text-left font-medium">角色</th>
                <th className="px-4 py-3 text-left font-medium">权限</th>
                <th className="px-4 py-3 text-left font-medium">知识库范围</th>
                <th className="px-4 py-3 text-left font-medium">凭证</th>
                <th className="px-4 py-3 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={7} className="px-4 py-10 text-center text-muted-foreground">
                    <Loader2 className="mx-auto h-6 w-6 animate-spin" />
                  </td>
                </tr>
              ) : accounts.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-4 py-10 text-center text-muted-foreground">
                    暂无内置账号配置
                  </td>
                </tr>
              ) : (
                accounts.map((account) => (
                  <tr key={account.username} className="border-b last:border-0">
                    <td className="px-4 py-3 font-medium">
                      <div className="flex items-center gap-2">
                        {account.username}
                        {account.disabled && <Badge variant="error">已禁用</Badge>}
                      </div>
                    </td>
                    <td className="px-4 py-3">{account.tenantId}</td>
                    <td className="px-4 py-3">
                      <Badge variant={account.role === 'ADMIN' ? 'success' : 'default'}>{account.role}</Badge>
                    </td>
                    <td className="max-w-xs px-4 py-3">
                      <TokenList values={account.permissions} emptyText="未配置" />
                    </td>
                    <td className="max-w-xs px-4 py-3">
                      <TokenList values={account.knowledgeBaseIds} emptyText="全部知识库" />
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-col gap-1 text-xs text-muted-foreground">
                        <span className="inline-flex items-center gap-1">
                          <ShieldCheck className="h-3.5 w-3.5" />
                          tokenVersion: {account.tokenVersion}
                        </span>
                        <span>明文密码: {account.passwordConfigured ? '已配置' : '未配置'}</span>
                        <span>哈希: {account.passwordHashConfigured ? '已配置' : '未配置'}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex justify-end gap-2">
                        <Button variant="ghost" size="sm" onClick={() => openEdit(account)} disabled={saving}>
                          <Edit3 className="h-4 w-4" />
                        </Button>
                        <Button variant="outline" size="sm" onClick={() => toggleDisabled(account)} disabled={saving}>
                          {account.disabled ? '启用' : '禁用'}
                        </Button>
                        <Button variant="outline" size="sm" onClick={() => rotateToken(account)} disabled={saving}>
                          轮换
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      <Dialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        title={title}
        footer={
          <>
            <Button variant="outline" onClick={() => setDialogOpen(false)} disabled={saving}>
              取消
            </Button>
            <Button onClick={submit} disabled={saving}>
              {saving && <Loader2 className="h-4 w-4 animate-spin" />}
              保存
            </Button>
          </>
        }
      >
        <div className="grid gap-4">
          <label className="space-y-1 text-sm">
            <span className="font-medium">账号名</span>
            <Input
              value={form.username}
              onChange={(e) => setForm((prev) => ({ ...prev, username: e.target.value }))}
              disabled={Boolean(editing)}
              placeholder="analyst"
            />
          </label>
          <div className="grid gap-3 md:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="font-medium">租户</span>
              <Input value={form.tenantId} onChange={(e) => setForm((prev) => ({ ...prev, tenantId: e.target.value }))} />
            </label>
            <label className="space-y-1 text-sm">
              <span className="font-medium">角色</span>
              <Input value={form.role} onChange={(e) => setForm((prev) => ({ ...prev, role: e.target.value }))} />
            </label>
          </div>
          <label className="space-y-1 text-sm">
            <span className="font-medium">权限，逗号分隔</span>
            <Textarea value={form.permissions} onChange={(e) => setForm((prev) => ({ ...prev, permissions: e.target.value }))} />
          </label>
          <label className="space-y-1 text-sm">
            <span className="font-medium">知识库 ID，逗号分隔</span>
            <Textarea
              value={form.knowledgeBaseIds}
              onChange={(e) => setForm((prev) => ({ ...prev, knowledgeBaseIds: e.target.value }))}
              placeholder="留空代表全部知识库"
            />
          </label>
          <div className="grid gap-3 md:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="font-medium">新密码</span>
              <Input
                type="password"
                value={form.password}
                onChange={(e) => setForm((prev) => ({ ...prev, password: e.target.value }))}
                placeholder={editing ? '不修改则留空' : '可留空改填哈希'}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="font-medium">密码哈希</span>
              <Input
                value={form.passwordHash}
                onChange={(e) => setForm((prev) => ({ ...prev, passwordHash: e.target.value }))}
                placeholder="pbkdf2$..."
              />
            </label>
          </div>
        </div>
      </Dialog>
    </AppLayout>
  )
}

function TokenList({ values, emptyText }: { values: string[]; emptyText: string }) {
  const visible = values.length > 0 ? values : [emptyText]
  return (
    <div className="flex flex-wrap gap-1.5">
      {visible.map((value) => (
        <span key={value} className="rounded-lg bg-muted px-2 py-1 font-mono text-[11px] text-muted-foreground">
          {value}
        </span>
      ))}
    </div>
  )
}

function splitCsv(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}
