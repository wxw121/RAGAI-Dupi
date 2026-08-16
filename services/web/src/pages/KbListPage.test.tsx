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

vi.mock('@/api/knowledgeBase', () => api)
vi.mock('@/components/AppLayout', () => ({ AppLayout: ({ children }: { children: React.ReactNode }) => children }))
vi.mock('@/components/Toast', () => ({
  useToast: () => toast,
}))
vi.mock('react-router-dom', () => ({ useNavigate: () => vi.fn() }))

describe('KbListPage', () => {
  let root: Root | null = null
  let container: HTMLDivElement | null = null

  afterEach(() => {
    if (root) act(() => root?.unmount())
    container?.remove()
    root = null
    container = null
    vi.clearAllMocks()
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
      .mockResolvedValueOnce([])
    api.deleteKnowledgeBase.mockResolvedValue(undefined)
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
    expect(toast.showSuccess).toHaveBeenCalledWith('已删除 2 个知识库')
  })
})

function setNativeValue(element: HTMLInputElement | HTMLSelectElement, value: string) {
  const prototype = element instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype
  Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(element, value)
}
