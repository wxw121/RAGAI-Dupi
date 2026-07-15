import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RecoveryPanel } from './RecoveryPanel'

const api = vi.hoisted(() => ({
  abandonRestore: vi.fn(), createArchive: vi.fn(), createRestore: vi.fn(), deleteArchive: vi.fn(),
  getArchiveDownloadUrl: vi.fn(), listArchives: vi.fn(), listRestores: vi.fn(),
  retryArchive: vi.fn(), retryRestore: vi.fn(),
}))
vi.mock('@/api/recovery', () => api)
vi.mock('@/components/Toast', () => ({ useToast: () => ({ showError: vi.fn(), showSuccess: vi.fn() }) }))

const completedArchive = {
  id: 'archive-1', sourceKnowledgeBaseId: 'kb-1', status: 'COMPLETED', schemaVersion: 1,
  itemCount: 9, totalBytes: 2048, manifestChecksum: 'abcdef1234567890', errorCode: null,
  errorMessage: null, createdBy: 'admin', createdAt: '2026-07-15T00:00:00Z', updatedAt: '2026-07-15T00:01:00Z',
}
const failedRestore = {
  id: 'restore-1', archiveId: 'archive-1', targetKnowledgeBaseId: 'target-1', status: 'FAILED',
  completedItems: 4, totalItems: 9, errorCode: 'RECOVERY_ITEM_FAILED', errorMessage: 'Recovery item restore failed',
  createdBy: 'admin', createdAt: '2026-07-15T00:02:00Z', updatedAt: '2026-07-15T00:03:00Z',
}

async function renderPanel() {
  const container = document.createElement('div')
  document.body.appendChild(container)
  const root = createRoot(container)
  await act(async () => { root.render(<RecoveryPanel kbId="kb-1" />); await Promise.resolve() })
  return { container, root }
}

describe('RecoveryPanel', () => {
  beforeEach(() => {
    api.listArchives.mockResolvedValue([completedArchive])
    api.listRestores.mockResolvedValue([failedRestore])
    api.getArchiveDownloadUrl.mockReturnValue('/download/archive-1')
    api.createArchive.mockResolvedValue({ ...completedArchive, id: 'archive-2', status: 'PREPARING' })
    api.createRestore.mockResolvedValue({ ...failedRestore, id: 'restore-2', status: 'VALIDATING' })
    api.retryRestore.mockResolvedValue({ ...failedRestore, status: 'VALIDATING' })
    api.abandonRestore.mockResolvedValue(undefined)
  })
  afterEach(() => { vi.clearAllMocks(); document.body.innerHTML = '' })

  it('shows archive evidence, download, failed restore reason and actions', async () => {
    const { container, root } = await renderPanel()
    expect(container.textContent).toContain('9 items')
    expect(container.textContent).toContain('abcdef123456')
    expect(container.textContent).toContain('Recovery item restore failed')
    expect((container.querySelector('a[aria-label="Download archive"]') as HTMLAnchorElement).href).toContain('/download/archive-1')
    expect(container.querySelector('button[aria-label="Retry restore"]')).not.toBeNull()
    expect(container.querySelector('button[aria-label="Abandon restore"]')).not.toBeNull()
    act(() => root.unmount())
  })

  it('confirms archive creation and restore before issuing commands', async () => {
    const { container, root } = await renderPanel()
    await act(async () => { (container.querySelector('button[aria-label="Create archive"]') as HTMLButtonElement).click() })
    expect(document.body.textContent).toContain('Confirm archive')
    await act(async () => { (document.body.querySelector('button[aria-label="Confirm archive"]') as HTMLButtonElement).click(); await Promise.resolve() })
    expect(api.createArchive).toHaveBeenCalledWith('kb-1')

    await act(async () => { (container.querySelector('button[aria-label="Restore archive archive-1"]') as HTMLButtonElement).click() })
    expect(document.body.textContent).toContain('Confirm restore')
    await act(async () => { (document.body.querySelector('button[aria-label="Confirm restore"]') as HTMLButtonElement).click(); await Promise.resolve() })
    expect(api.createRestore).toHaveBeenCalledWith('kb-1', 'archive-1')
    act(() => root.unmount())
  })

  it('retries and confirms abandon for a failed restore', async () => {
    const { container, root } = await renderPanel()
    await act(async () => { (container.querySelector('button[aria-label="Retry restore"]') as HTMLButtonElement).click(); await Promise.resolve() })
    expect(api.retryRestore).toHaveBeenCalledWith('kb-1', 'restore-1')
    await act(async () => { (container.querySelector('button[aria-label="Abandon restore"]') as HTMLButtonElement).click() })
    await act(async () => { (document.body.querySelector('button[aria-label="Confirm abandon"]') as HTMLButtonElement).click(); await Promise.resolve() })
    expect(api.abandonRestore).toHaveBeenCalledWith('kb-1', 'restore-1')
    act(() => root.unmount())
  })
})
