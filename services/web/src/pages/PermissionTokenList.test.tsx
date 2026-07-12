import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it } from 'vitest'
import { PermissionTokenList } from './PermissionTokenList'
import type { PermissionMetadata } from '@/types'

const permissionDetails: PermissionMetadata[] = [
  {
    code: 'ACCOUNT_PASSWORD_RESET',
    name: '密码重置',
    description: '用于管理员重置其他账号密码。',
    allows: ['重置其他账号密码'],
    denies: ['创建、禁用或编辑账号基础信息'],
  },
]

describe('PermissionTokenList', () => {
  let root: Root | null = null
  let container: HTMLDivElement | null = null

  afterEach(() => {
    if (root) {
      act(() => root?.unmount())
    }
    root = null
    container = null
  })

  it('renders permission detail popovers for permission tokens', () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    act(() => {
      root?.render(
        <PermissionTokenList
          values={['ACCOUNT_PASSWORD_RESET']}
          emptyText="未配置"
          permissionDetails={permissionDetails}
        />,
      )
    })

    expect(container.textContent).toContain('ACCOUNT_PASSWORD_RESET')
    expect(container.textContent).toContain('密码重置')
    expect(container.textContent).toContain('允许')
    expect(container.textContent).toContain('重置其他账号密码')
    expect(container.textContent).toContain('不允许')
    expect(container.textContent).toContain('创建、禁用或编辑账号基础信息')
  })
})
