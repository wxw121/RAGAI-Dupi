import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RecoveryPanel } from './RecoveryPanel'

const api = vi.hoisted(() => ({
  abandonRestore: vi.fn(), createArchive: vi.fn(), createRestore: vi.fn(), deleteArchive: vi.fn(),
  getArchiveDownloadUrl: vi.fn(), importArchive: vi.fn(), listArchives: vi.fn(), listRestores: vi.fn(),
  retryArchive: vi.fn(), retryRestore: vi.fn(),
}))
const toast = vi.hoisted(() => ({ showError: vi.fn(), showSuccess: vi.fn() }))
vi.mock('@/api/recovery', () => api)
vi.mock('@/components/Toast', () => ({ useToast: () => toast }))
vi.mock('@/components/OperationProgress', () => ({
  OperationProgress: ({ initialJob, onCompleted }: { initialJob: { id: string }; onCompleted: () => void }) => (
    <button data-testid="operation-progress" onClick={() => { void Promise.resolve(onCompleted()).catch(() => undefined) }}>{initialJob.id}</button>
  ),
}))

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
    api.importArchive.mockResolvedValue(operationJob('recovery-import-job'))
    api.retryRestore.mockResolvedValue({ ...failedRestore, status: 'VALIDATING' })
    api.abandonRestore.mockResolvedValue(undefined)
  })
  afterEach(() => { vi.resetAllMocks(); document.body.innerHTML = '' })

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

  it('allows an in-progress restore to be abandoned', async () => {
    api.listRestores.mockResolvedValue([{ ...failedRestore, status: 'VALIDATING' }])
    const { container, root } = await renderPanel()

    expect(container.querySelector('button[aria-label="Retry restore"]')).toBeNull()
    expect(container.querySelector('button[aria-label="Abandon restore"]')).not.toBeNull()
    act(() => root.unmount())
  })

  it('tracks a Recovery ZIP operation and refreshes archives only after completion', async () => {
    const { container, root } = await renderPanel()
    const input = container.querySelector('input[aria-label="Recovery ZIP file"]') as HTMLInputElement
    const file = new File(['zip-content'], 'recovery.zip', { type: 'application/zip' })
    const listCallsBeforeImport = api.listArchives.mock.calls.length

    await act(async () => {
      Object.defineProperty(input, 'files', { configurable: true, value: [file] })
      input.dispatchEvent(new Event('change', { bubbles: true }))
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(api.importArchive).toHaveBeenCalledWith('kb-1', file)
    expect(container.textContent).toContain('recovery-import-job')
    expect(api.listArchives).toHaveBeenCalledTimes(listCallsBeforeImport)
    await act(async () => {
      container.querySelector<HTMLButtonElement>('[data-testid="operation-progress"]')?.click()
      await Promise.resolve()
    })
    expect(api.listArchives.mock.calls.length).toBeGreaterThan(listCallsBeforeImport)
    act(() => root.unmount())
  })

  it('does not accept a second Recovery ZIP while the first operation is retained', async () => {
    api.importArchive
      .mockResolvedValueOnce(operationJob('first-import-job'))
      .mockResolvedValueOnce(operationJob('second-import-job'))
    const { container, root } = await renderPanel()
    const input = container.querySelector('input[aria-label="Recovery ZIP file"]') as HTMLInputElement

    for (const name of ['first.zip', 'second.zip']) {
      await act(async () => {
        Object.defineProperty(input, 'files', { configurable: true, value: [new File(['zip'], name)] })
        input.dispatchEvent(new Event('change', { bubbles: true }))
        await Promise.resolve()
      })
    }

    expect(api.importArchive).toHaveBeenCalledTimes(1)
    expect(container.textContent).toContain('first-import-job')
    expect(container.textContent).not.toContain('second-import-job')
    act(() => root.unmount())
  })

  it('retains completed import evidence when archive refresh fails', async () => {
    const { container, root } = await renderPanel()
    const input = container.querySelector('input[aria-label="Recovery ZIP file"]') as HTMLInputElement
    await act(async () => {
      Object.defineProperty(input, 'files', { configurable: true, value: [new File(['zip'], 'first.zip')] })
      input.dispatchEvent(new Event('change', { bubbles: true }))
      await Promise.resolve()
    })
    toast.showSuccess.mockClear()
    api.listArchives.mockRejectedValueOnce(new Error('refresh unavailable'))
    await act(async () => {
      container.querySelector<HTMLButtonElement>('[data-testid="operation-progress"]')?.click()
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(container.textContent).toContain('recovery-import-job')
    expect(toast.showSuccess).not.toHaveBeenCalledWith('Recovery ZIP imported and verified')
    act(() => root.unmount())
  })
})

function operationJob(id: string) {
  return {
    id, operationType: 'RECOVERY_ARCHIVE_IMPORT', aggregateType: 'KNOWLEDGE_BASE', aggregateId: 'kb-1',
    status: 'RUNNING', attemptCount: 0, nextAttemptAt: null, errorCode: null, errorMessage: null,
    createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z', completedAt: null, steps: [],
  }
}
