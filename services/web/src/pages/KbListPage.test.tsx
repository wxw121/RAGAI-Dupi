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

    const nameInput = container.querySelector<HTMLInputElement>('input')
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
})

function setNativeValue(element: HTMLInputElement | HTMLSelectElement, value: string) {
  const prototype = element instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype
  Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(element, value)
}
