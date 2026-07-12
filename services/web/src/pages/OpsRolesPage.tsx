import { useCallback, useEffect, useState } from 'react'
import { createRole, disableRole, listOpsMetadata, listRoles, updateRole } from '@/api/knowledgeBase'
import type { OpsMetadata, Role, RoleRequest } from '@/types'
import { AppLayout } from '@/components/AppLayout'
import { useToast } from '@/components/Toast'
import { Badge, Dialog } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Loader2, Plus, RefreshCw, Shield } from 'lucide-react'
import { PermissionCheckboxLabel, PermissionTokenList } from './PermissionTokenList'

interface RoleFormState {
  code: string
  name: string
  description: string
  permissions: string[]
}

const emptyForm: RoleFormState = {
  code: '',
  name: '',
  description: '',
  permissions: ['KB_READ'],
}

export function OpsRolesPage({ onLogout }: { onLogout?: () => void }) {
  const [roles, setRoles] = useState<Role[]>([])
  const [metadata, setMetadata] = useState<OpsMetadata | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editing, setEditing] = useState<Role | null>(null)
  const [form, setForm] = useState<RoleFormState>(emptyForm)
  const { showError, showSuccess } = useToast()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [nextRoles, nextMetadata] = await Promise.all([listRoles(), listOpsMetadata()])
      setRoles(nextRoles)
      setMetadata(nextMetadata)
    } catch (e) {
      showError(e instanceof Error ? e.message : '加载角色失败')
    } finally {
      setLoading(false)
    }
  }, [showError])

  useEffect(() => {
    load()
  }, [load])

  const openCreate = () => {
    setEditing(null)
    setForm(emptyForm)
    setDialogOpen(true)
  }

  const openEdit = (role: Role) => {
    setEditing(role)
    setForm({
      code: role.code,
      name: role.name,
      description: role.description ?? '',
      permissions: role.permissions,
    })
    setDialogOpen(true)
  }

  const submit = async () => {
    setSaving(true)
    try {
      const payload: RoleRequest = {
        code: editing ? undefined : form.code.trim(),
        name: form.name.trim(),
        description: form.description.trim() || undefined,
        permissions: form.permissions,
      }
      if (editing) {
        await updateRole(editing.id, payload)
        showSuccess('角色已更新')
      } else {
        await createRole(payload)
        showSuccess('角色已创建')
      }
      setDialogOpen(false)
      await load()
    } catch (e) {
      showError(e instanceof Error ? e.message : '保存角色失败')
    } finally {
      setSaving(false)
    }
  }

  const disable = async (role: Role) => {
    setSaving(true)
    try {
      await disableRole(role.id)
      showSuccess('角色已禁用')
      await load()
    } catch (e) {
      showError(e instanceof Error ? e.message : '禁用角色失败')
    } finally {
      setSaving(false)
    }
  }

  const togglePermission = (permission: string) => {
    setForm((prev) => ({
      ...prev,
      permissions: prev.permissions.includes(permission)
        ? prev.permissions.filter((item) => item !== permission)
        : [...prev.permissions, permission],
    }))
  }

  return (
    <AppLayout onLogout={onLogout}>
      <div className="mx-auto max-w-6xl space-y-6 px-4 py-6 md:px-8">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="flex items-center gap-2 text-2xl font-semibold tracking-tight">
              <Shield className="h-6 w-6" />
              角色管理
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">角色绑定权限，账号只分配角色和知识库范围。</p>
          </div>
          <div className="flex gap-2">
            <Button variant="outline" onClick={load} disabled={loading || saving}>
              {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
              刷新
            </Button>
            <Button onClick={openCreate} disabled={saving}>
              <Plus className="h-4 w-4" />
              新建角色
            </Button>
          </div>
        </div>

        <div className="overflow-x-auto rounded-3xl border border-border bg-background">
          <table className="w-full text-sm">
            <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
              <tr>
                <th className="px-4 py-3 text-left font-medium">角色</th>
                <th className="px-4 py-3 text-left font-medium">说明</th>
                <th className="px-4 py-3 text-left font-medium">权限</th>
                <th className="px-4 py-3 text-left font-medium">用户数</th>
                <th className="px-4 py-3 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={5} className="px-4 py-10 text-center text-muted-foreground">
                    <Loader2 className="mx-auto h-6 w-6 animate-spin" />
                  </td>
                </tr>
              ) : roles.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-4 py-10 text-center text-muted-foreground">暂无角色</td>
                </tr>
              ) : (
                roles.map((role) => (
                  <tr key={role.id} className="border-b last:border-0">
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{role.name}</span>
                        <span className="font-mono text-xs text-muted-foreground">{role.code}</span>
                        {role.systemBuiltin && <Badge>内置</Badge>}
                        {role.disabled && <Badge variant="error">已禁用</Badge>}
                      </div>
                    </td>
                    <td className="max-w-xs px-4 py-3 text-muted-foreground">{role.description ?? '-'}</td>
                    <td className="max-w-md px-4 py-3">
                      <PermissionTokenList
                        values={role.permissions}
                        emptyText="未配置"
                        permissionDetails={metadata?.permissionDetails}
                      />
                    </td>
                    <td className="px-4 py-3">{role.userCount}</td>
                    <td className="px-4 py-3">
                      <div className="flex justify-end gap-2">
                        <Button variant="outline" size="sm" onClick={() => openEdit(role)} disabled={saving}>
                          编辑
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => disable(role)}
                          disabled={saving || role.disabled || role.code === 'ADMIN'}
                        >
                          禁用
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
        title={editing ? `编辑角色 ${editing.code}` : '新建角色'}
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
          <div className="grid gap-3 md:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="font-medium">角色编码</span>
              <Input
                value={form.code}
                onChange={(e) => setForm((prev) => ({ ...prev, code: e.target.value }))}
                disabled={Boolean(editing)}
                placeholder="ANALYST"
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="font-medium">角色名称</span>
              <Input value={form.name} onChange={(e) => setForm((prev) => ({ ...prev, name: e.target.value }))} />
            </label>
          </div>
          <label className="space-y-1 text-sm">
            <span className="font-medium">说明</span>
            <Textarea value={form.description} onChange={(e) => setForm((prev) => ({ ...prev, description: e.target.value }))} />
          </label>
          <div className="space-y-2 text-sm">
            <span className="font-medium">权限</span>
            <div className="grid gap-2 md:grid-cols-2">
              {(metadata?.permissions ?? []).map((permission) => (
                <label key={permission} className="group flex items-center gap-2 rounded-xl border border-border px-3 py-2">
                  <PermissionCheckboxLabel permission={permission} permissionDetails={metadata?.permissionDetails}>
                    <input
                      type="checkbox"
                      checked={form.permissions.includes(permission)}
                      onChange={() => togglePermission(permission)}
                      disabled={form.code.toUpperCase() === 'ADMIN' || permission === '*'}
                    />
                  </PermissionCheckboxLabel>
                </label>
              ))}
            </div>
          </div>
        </div>
      </Dialog>
    </AppLayout>
  )
}
