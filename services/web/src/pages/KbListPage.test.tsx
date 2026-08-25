import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { KbListPage } from './KbListPage'

const api = vi.hoisted(() => ({
  listKnowledgeBases: vi.fn(),
  createKnowledgeBase: vi.fn(),
  deleteKnowledgeBase: vi.fn(),
}))
const toast = vi.hoisted(() => ({ showError: vi.fn(), showSuccess: vi.fn() }))
const navigation = vi.hoisted(() => ({ navigate: vi.fn() }))

vi.mock('@/api/knowledgeBase', () => api)
vi.mock('@/components/AppLayout', () => ({ AppLayout: ({ children }: { children: React.ReactNode }) => children }))
vi.mock('@/components/Toast', () => ({
  useToast: () => toast,
}))
vi.mock('@/components/OperationProgress', () => ({
  OperationProgress: ({ initialJob, onCompleted }: { initialJob: { id: string }; onCompleted: () => void }) => (
    <button data-testid="operation-progress" onClick={() => { void Promise.resolve(onCompleted()).catch(() => undefined) }}>{initialJob.id}</button>
  ),
}))
vi.mock('react-router-dom', () => ({ useNavigate: () => navigation.navigate }))

describe('KbListPage', () => {
  let root: Root | null = null
  let container: HTMLDivElement | null = null

  afterEach(() => {
    if (root) act(() => root?.unmount())
    container?.remove()
    root = null
    container = null
    vi.clearAllMocks()
    vi.unstubAllGlobals()
  })

  it('sends the selected retrieval mode when creating a knowledge base', async () => {
    api.listKnowledgeBases.mockResolvedValue([])
    api.createKnowledgeBase.mockResolvedValue({ id: 'kb-1' })
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<KbListPage />)
      await Promise.resolve()
    })

    await act(async () => {
      container?.querySelector<HTMLButtonElement>('button')?.click()
    })

    const nameInput = container.querySelector<HTMLInputElement>('input[name="knowledgeBaseName"]')
    const modeSelect = container.querySelector<HTMLSelectElement>('select[name="retrievalMode"]')
    expect(nameInput).not.toBeNull()
    expect(modeSelect).not.toBeNull()

    await act(async () => {
      setNativeValue(nameInput!, 'Hybrid KB')
      nameInput?.dispatchEvent(new Event('input', { bubbles: true }))
      setNativeValue(modeSelect!, 'HYBRID')
      modeSelect?.dispatchEvent(new Event('change', { bubbles: true }))
    })

    await act(async () => {
      const buttons = container?.querySelectorAll<HTMLButtonElement>('.fixed.inset-0.z-50 button') ?? []
      buttons[buttons.length - 1]?.click()
      await Promise.resolve()
    })

    expect(api.createKnowledgeBase).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Hybrid KB',
      retrievalMode: 'HYBRID',
    }))
  })

  it('filters knowledge bases by name and shows an empty search result', async () => {
    api.listKnowledgeBases.mockResolvedValue([
      { id: 'kb-1', name: '产品手册', createdAt: '2026-01-01', chunkSize: 512, chunkOverlap: 64, topK: 5 },
      { id: 'kb-2', name: 'REST API 指南', createdAt: '2026-01-01', chunkSize: 512, chunkOverlap: 64, topK: 5 },
    ])
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<KbListPage />)
      await Promise.resolve()
    })

    const searchInput = container.querySelector<HTMLInputElement>('input[name="knowledgeBaseSearch"]')
    expect(searchInput).not.toBeNull()

    await act(async () => {
      setNativeValue(searchInput!, 'rest')
      searchInput?.dispatchEvent(new Event('input', { bubbles: true }))
    })

    expect(container.textContent).toContain('REST API 指南')
    expect(container.textContent).not.toContain('产品手册')

    await act(async () => {
      setNativeValue(searchInput!, '不存在')
      searchInput?.dispatchEvent(new Event('input', { bubbles: true }))
    })

    expect(container.textContent).toContain('未找到匹配的知识库')
  })

  it('selects all knowledge bases and deletes them in one batch action', async () => {
    api.listKnowledgeBases
      .mockResolvedValueOnce([
        { id: 'kb-1', name: '测试一', createdAt: '2026-01-01', chunkSize: 512, chunkOverlap: 64, topK: 5 },
        { id: 'kb-2', name: '测试二', createdAt: '2026-01-01', chunkSize: 512, chunkOverlap: 64, topK: 5 },
      ])
      .mockRejectedValueOnce(new Error('refresh unavailable'))
    api.deleteKnowledgeBase
      .mockResolvedValueOnce(operationJob('delete-job-1', 'kb-1'))
      .mockResolvedValueOnce(operationJob('delete-job-2', 'kb-2'))
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<KbListPage />)
      await Promise.resolve()
    })

    const selectAll = Array.from(container.querySelectorAll<HTMLInputElement>('input[type="checkbox"]'))
      .find((input) => !input.getAttribute('aria-label'))
    await act(async () => {
      selectAll?.click()
    })

    const batchButton = Array.from(container.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.includes('批量删除'))
    expect(batchButton?.textContent).toContain('(2)')

    await act(async () => {
      batchButton?.click()
    })

    const confirmButton = Array.from(container.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.includes('确认删除'))
    await act(async () => {
      confirmButton?.click()
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(api.deleteKnowledgeBase).toHaveBeenCalledTimes(2)
    expect(api.deleteKnowledgeBase).toHaveBeenCalledWith('kb-1')
    expect(api.deleteKnowledgeBase).toHaveBeenCalledWith('kb-2')
    expect(container.textContent).toContain('delete-job-1')
    expect(container.textContent).toContain('delete-job-2')
    expect(api.listKnowledgeBases).toHaveBeenCalledTimes(1)
    const deletingCheckboxes = Array.from(container.querySelectorAll<HTMLInputElement>('input[aria-label^="选择知识库"]'))
    expect(deletingCheckboxes.every((checkbox) => checkbox.disabled)).toBe(true)
    await act(async () => {
      selectAll?.click()
      batchButton?.click()
      await Promise.resolve()
    })
    expect(api.deleteKnowledgeBase).toHaveBeenCalledTimes(2)
    await act(async () => {
      Array.from(container!.querySelectorAll<HTMLElement>('.group')).find((card) => card.textContent?.includes('测试一'))?.click()
    })
    expect(navigation.navigate).not.toHaveBeenCalled()

    await act(async () => {
      container!.querySelectorAll<HTMLButtonElement>('[data-testid="operation-progress"]')[0]?.click()
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(api.listKnowledgeBases).toHaveBeenCalledTimes(2)
    expect(container.textContent).toContain('delete-job-1')
    expect(toast.showSuccess).not.toHaveBeenCalledWith('知识库已删除')
  })

  it('does not batch-delete a knowledge base while its single delete request is pending', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true))
    const pendingDelete = deferred<ReturnType<typeof operationJob>>()
    await renderKnowledgeBases()
    api.deleteKnowledgeBase.mockReturnValueOnce(pendingDelete.promise)

    const card = Array.from(container!.querySelectorAll<HTMLElement>('.group'))
      .find((node) => node.textContent?.includes('测试一'))!
    const checkbox = card.querySelector<HTMLInputElement>('input[aria-label="选择知识库 测试一"]')
    act(() => checkbox?.click())
    const singleDelete = card.querySelector<HTMLButtonElement>('button.absolute.right-2.top-2')
    act(() => singleDelete?.click())

    expect(checkbox?.disabled).toBe(true)
    const batchButton = Array.from(container!.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.includes('批量删除'))
    expect(batchButton?.disabled).toBe(true)
    act(() => batchButton?.click())
    const confirmButton = Array.from(container!.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.includes('确认删除'))
    act(() => confirmButton?.click())

    expect(api.deleteKnowledgeBase).toHaveBeenCalledTimes(1)
    await act(async () => { pendingDelete.resolve(operationJob('delete-job-1', 'kb-1')); await pendingDelete.promise })
  })

  it('does not single-delete a knowledge base while its batch delete request is pending', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true))
    const firstDelete = deferred<ReturnType<typeof operationJob>>()
    const secondDelete = deferred<ReturnType<typeof operationJob>>()
    await renderKnowledgeBases()
    api.deleteKnowledgeBase.mockReturnValueOnce(firstDelete.promise).mockReturnValueOnce(secondDelete.promise)

    const selectAll = Array.from(container!.querySelectorAll<HTMLInputElement>('input[type="checkbox"]'))
      .find((input) => !input.getAttribute('aria-label'))
    act(() => selectAll?.click())
    const batchButton = Array.from(container!.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.includes('批量删除'))
    act(() => batchButton?.click())
    const confirmButton = Array.from(container!.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.includes('确认删除'))
    await act(async () => { confirmButton?.click(); await Promise.resolve() })

    const firstCardDelete = Array.from(container!.querySelectorAll<HTMLElement>('.group'))[0]
      ?.querySelector<HTMLButtonElement>('button.absolute.right-2.top-2')
    expect(firstCardDelete?.disabled).toBe(true)
    act(() => firstCardDelete?.click())

    expect(api.deleteKnowledgeBase).toHaveBeenCalledTimes(2)
    await act(async () => {
      firstDelete.resolve(operationJob('delete-job-1', 'kb-1'))
      secondDelete.resolve(operationJob('delete-job-2', 'kb-2'))
      await Promise.all([firstDelete.promise, secondDelete.promise])
    })
  })

  it('releases the pending deletion guard when a delete request fails', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true))
    await renderKnowledgeBases()
    api.deleteKnowledgeBase.mockRejectedValueOnce(new Error('delete unavailable'))

    const card = Array.from(container!.querySelectorAll<HTMLElement>('.group'))
      .find((node) => node.textContent?.includes('测试一'))!
    const singleDelete = card.querySelector<HTMLButtonElement>('button.absolute.right-2.top-2')
    await act(async () => { singleDelete?.click(); await Promise.resolve(); await Promise.resolve() })

    expect(singleDelete?.disabled).toBe(false)
    expect(card.querySelector<HTMLInputElement>('input[aria-label="选择知识库 测试一"]')?.disabled).toBe(false)
    expect(toast.showError).toHaveBeenCalledWith('delete unavailable')
  })

  async function renderKnowledgeBases() {
    api.listKnowledgeBases.mockResolvedValue([
      { id: 'kb-1', name: '测试一', createdAt: '2026-01-01', chunkSize: 512, chunkOverlap: 64, topK: 5 },
      { id: 'kb-2', name: '测试二', createdAt: '2026-01-01', chunkSize: 512, chunkOverlap: 64, topK: 5 },
    ])
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => { root?.render(<KbListPage />); await Promise.resolve() })
  }
})

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function operationJob(id: string, aggregateId: string) {
  return {
    id, operationType: 'KNOWLEDGE_BASE_DELETE', aggregateType: 'KNOWLEDGE_BASE', aggregateId,
    status: 'RUNNING', attemptCount: 0, nextAttemptAt: null, errorCode: null, errorMessage: null,
    createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z', completedAt: null, steps: [],
  }
}

function setNativeValue(element: HTMLInputElement | HTMLSelectElement, value: string) {
  const prototype = element instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype
  Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(element, value)
}
