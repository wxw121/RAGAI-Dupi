import { useMemo, type ReactNode } from 'react'
import type { PermissionMetadata } from '@/types'

interface PermissionTokenListProps {
  values: string[]
  emptyText: string
  permissionDetails?: PermissionMetadata[]
}

interface PermissionTokenProps {
  value: string
  detail?: PermissionMetadata
}

export function PermissionTokenList({ values, emptyText, permissionDetails = [] }: PermissionTokenListProps) {
  const detailMap = usePermissionDetailMap(permissionDetails)
  const visible = values.length > 0 ? values : [emptyText]

  return (
    <div className="flex flex-wrap gap-1.5">
      {visible.map((value) => (
        <PermissionToken key={value} value={value} detail={detailMap.get(value)} />
      ))}
    </div>
  )
}

export function PermissionCheckboxLabel({
  permission,
  permissionDetails = [],
  children,
}: {
  permission: string
  permissionDetails?: PermissionMetadata[]
  children: ReactNode
}) {
  const detailMap = usePermissionDetailMap(permissionDetails)

  return (
    <span className="relative inline-flex min-w-0 items-center gap-2">
      {children}
      <span className="font-mono text-xs">{permission}</span>
      <PermissionPopover detail={detailMap.get(permission)} />
    </span>
  )
}

function PermissionToken({ value, detail }: PermissionTokenProps) {
  return (
    <span
      className="group relative rounded-lg bg-muted px-2 py-1 font-mono text-[11px] text-muted-foreground"
      tabIndex={detail ? 0 : undefined}
    >
      {value}
      <PermissionPopover detail={detail} />
    </span>
  )
}

function PermissionPopover({ detail }: { detail?: PermissionMetadata }) {
  if (!detail) {
    return null
  }

  return (
    <span className="pointer-events-none absolute left-0 top-full z-50 mt-2 hidden w-72 whitespace-normal rounded-xl border border-border bg-background p-3 text-left font-sans text-xs text-foreground shadow-lg group-hover:block group-focus:block group-focus-within:block">
      <span className="block font-semibold">{detail.name}</span>
      <span className="mt-1 block text-muted-foreground">{detail.description}</span>
      <span className="mt-2 block font-medium">允许</span>
      <span className="mt-1 block text-muted-foreground">{detail.allows.join('；')}</span>
      <span className="mt-2 block font-medium">不允许</span>
      <span className="mt-1 block text-muted-foreground">{detail.denies.join('；')}</span>
    </span>
  )
}

function usePermissionDetailMap(permissionDetails: PermissionMetadata[]) {
  return useMemo(() => {
    return new Map(permissionDetails.map((detail) => [detail.code, detail]))
  }, [permissionDetails])
}
