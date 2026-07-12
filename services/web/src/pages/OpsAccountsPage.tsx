import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  createAccount,
  disableAccount,
  enableAccount,
  listAccounts,
  listOpsMetadata,
  listRoles,
  resetAccountPassword,
  rotateAccountToken,
  updateAccount,
} from '@/api/knowledgeBase'
import type { Account, AccountUpsertRequest, OpsMetadata, Role } from '@/types'
import { AppLayout } from '@/components/AppLayout'
import { useToast } from '@/components/Toast'
import { Badge, Dialog } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Edit3, KeyRound, Loader2, Plus, RefreshCw, ShieldCheck, Users } from 'lucide-react'
import { PermissionTokenList } from './PermissionTokenList'

interface AccountFormState {
  username: string
  password: string
  tenantId: string
  roleCode: string
  knowledgeBaseIds: string
}

const emptyForm: AccountFormState = {
  username: '',
  password: '',
  tenantId: 'default',
  roleCode: 'ANALYST',
  knowledgeBaseIds: '',
}

export function OpsAccountsPage({ onLogout }: { onLogout?: () => void }) {
  const [accounts, setAccounts] = useState<Account[]>([])
  const [roles, setRoles] = useState<Role[]>([])
  const [metadata, setMetadata] = useState<OpsMetadata | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editing, setEditing] = useState<Account | null>(null)
  const [form, setForm] = useState<AccountFormState>(emptyForm)
  const [passwordDialogOpen, setPasswordDialogOpen] = useState(false)
  const [passwordTarget, setPasswordTarget] = useState<Account | null>(null)
  const [newPassword, setNewPassword] = useState('')
  const { showError, showSuccess } = useToast()

  const activeRoles = useMemo(() => roles.filter((role) => !role.disabled), [roles])
  const hasActiveAdmin = useMemo(
    () => accounts.some((account) => (account.roleCode || account.role) === 'ADMIN' && !account.disabled),
    [accounts],
  )

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [nextAccounts, nextRoles, nextMetadata] = await Promise.all([listAccounts(), listRoles(), listOpsMetadata()])
      setAccounts(nextAccounts)
      setRoles(nextRoles)
      setMetadata(nextMetadata)
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
    setForm({
      ...emptyForm,
      roleCode: hasActiveAdmin ? activeRoles.find((role) => role.code !== 'ADMIN')?.code ?? activeRoles[0]?.code ?? 'ANALYST' : 'ADMIN',
    })
    setDialogOpen(true)
  }

  const openEdit = (account: Account) => {
    setEditing(account)
    setForm({
      username: account.username,
      password: '',
      tenantId: account.tenantId,
      roleCode: account.roleCode || account.role,
      knowledgeBaseIds: account.knowledgeBaseIds.join(','),
    })
    setDialogOpen(true)
  }

  const openResetPassword = (account: Account) => {
    setPasswordTarget(account)
    setNewPassword('')
    setPasswordDialogOpen(true)
  }

  const submit = async () => {
    setSaving(true)
    try {
      const payload: AccountUpsertRequest = {
        username: editing ? undefined : form.username.trim(),
        password: editing ? undefined : form.password.trim() || undefined,
        tenantId: form.tenantId.trim() || 'default',
        roleCode: form.roleCode,
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

  const submitPasswordReset = async () => {
    if (!passwordTarget || !newPassword.trim()) {
      showError('请输入新密码')
      return
    }
    setSaving(true)
    try {
      await resetAccountPassword(passwordTarget.username, { password: newPassword })
      showSuccess('密码已重置，旧 token 已失效')
      setPasswordDialogOpen(false)
      await load()
    } catch (e) {
      showError(e instanceof Error ? e.message : '重置密码失败')
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
              账号只分配角色和知识库范围；权限由角色统一绑定，密码通过独立重置动作管理。
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

        <div className="overflow-x-auto rounded-3xl border border-border bg-background">
          <table className="w-full text-sm">
            <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
              <tr>
                <th className="px-4 py-3 text-left font-medium">账号</th>
                <th className="px-4 py-3 text-left font-medium">租户</th>
                <th className="px-4 py-3 text-left font-medium">角色</th>
                <th className="px-4 py-3 text-left font-medium">角色权限</th>
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
                    暂无账号
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
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge variant={account.roleCode === 'ADMIN' ? 'success' : 'default'}>
                          {account.roleName || account.roleCode || account.role}
                        </Badge>
                        <span className="font-mono text-xs text-muted-foreground">{account.roleCode || account.role}</span>
                      </div>
                    </td>
                    <td className="max-w-xs px-4 py-3">
                      <PermissionTokenList
                        values={account.permissions}
                        emptyText="未配置"
                        permissionDetails={metadata?.permissionDetails}
                      />
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
                        <span>密码哈希: {account.passwordHashConfigured ? '已配置' : '未配置'}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap justify-end gap-2">
                        <Button variant="ghost" size="sm" onClick={() => openEdit(account)} disabled={saving}>
                          <Edit3 className="h-4 w-4" />
                        </Button>
                        <Button variant="outline" size="sm" onClick={() => openResetPassword(account)} disabled={saving}>
                          <KeyRound className="h-4 w-4" />
                          重置密码
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
          {!editing && !hasActiveAdmin && (
            <p className="rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
              当前没有可用管理员，首次新建账号会默认创建为 ADMIN，用于恢复账号管理能力。
            </p>
          )}
          <div className="grid gap-3 md:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="font-medium">租户</span>
              <Input value={form.tenantId} onChange={(e) => setForm((prev) => ({ ...prev, tenantId: e.target.value }))} />
            </label>
            <label className="space-y-1 text-sm">
              <span className="font-medium">角色</span>
              <select
                value={form.roleCode}
                onChange={(e) => setForm((prev) => ({ ...prev, roleCode: e.target.value }))}
                className="h-10 w-full rounded-xl border border-input bg-background px-3 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                {activeRoles.map((role) => (
                  <option key={role.id} value={role.code}>
                    {role.name} ({role.code})
                  </option>
                ))}
              </select>
            </label>
          </div>
          <label className="space-y-1 text-sm">
            <span className="font-medium">知识库 ID，逗号分隔</span>
            <Textarea
              value={form.knowledgeBaseIds}
              onChange={(e) => setForm((prev) => ({ ...prev, knowledgeBaseIds: e.target.value }))}
              placeholder="留空代表全部知识库"
            />
          </label>
          {!editing && (
            <label className="space-y-1 text-sm">
              <span className="font-medium">初始密码</span>
              <Input
                type="password"
                value={form.password}
                onChange={(e) => setForm((prev) => ({ ...prev, password: e.target.value }))}
                placeholder="请输入初始密码"
              />
            </label>
          )}
        </div>
      </Dialog>

      <Dialog
        open={passwordDialogOpen}
        onClose={() => setPasswordDialogOpen(false)}
        title={passwordTarget ? `重置密码 ${passwordTarget.username}` : '重置密码'}
        footer={
          <>
            <Button variant="outline" onClick={() => setPasswordDialogOpen(false)} disabled={saving}>
              取消
            </Button>
            <Button onClick={submitPasswordReset} disabled={saving}>
              {saving && <Loader2 className="h-4 w-4 animate-spin" />}
              重置密码
            </Button>
          </>
        }
      >
        <label className="space-y-1 text-sm">
          <span className="font-medium">新密码</span>
          <Input type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} />
        </label>
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
