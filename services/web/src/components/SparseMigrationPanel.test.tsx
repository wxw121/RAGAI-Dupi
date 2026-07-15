import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SparseMigrationPanel } from './SparseMigrationPanel'

const api = vi.hoisted(() => ({
  listRetrievalProfiles: vi.fn(),
  listSparseMigrations: vi.fn(), startSparseMigration: vi.fn(), backfillSparseMigration: vi.fn(),
  beginSparseShadowValidation: vi.fn(), cutoverSparseMigration: vi.fn(), completeSparseMigration: vi.fn(),
  setLegacySparseFallback: vi.fn(),
}))
vi.mock('@/api/knowledgeBase', () => api)
vi.mock('@/components/Toast', () => ({ useToast: () => ({ showError: vi.fn(), showSuccess: vi.fn() }) }))

const profiles = [{ id: 'p1', name: 'candidate', version: 3, active: false }] as never

async function renderPanel(migration: Record<string, unknown>) {
  api.listSparseMigrations.mockResolvedValue([migration])
  const container = document.createElement('div')
  document.body.appendChild(container)
  const root = createRoot(container)
  await act(async () => { root.render(<SparseMigrationPanel kbId="kb-1" profiles={profiles} />); await Promise.resolve() })
  return { container, root }
}

describe('SparseMigrationPanel', () => {
  afterEach(() => { vi.clearAllMocks(); document.body.innerHTML = '' })

  it('shows state evidence and guarded cutover confirmation', async () => {
    const { container, root } = await renderPanel({
      id: 'm1', profileId: 'p1', state: 'SHADOW_VALIDATING', sourceChunkCount: 100,
      indexedChunkCount: 100, expectedDimension: 8, actualDimension: 8,
      baselineP95Ms: 100, candidateP95Ms: 110, baselineFallbackRate: 0.01,
      candidateFallbackRate: 0, legacyBm25Enabled: true, updatedAt: '2026-07-15T00:00:00Z',
    })
    expect(container.textContent).toContain('SHADOW_VALIDATING')
    expect(container.textContent).toContain('100 / 100')
    expect(container.querySelector('[role="switch"][aria-label="Legacy BM25 fallback"]')).not.toBeNull()
    const cutover = container.querySelector('button[aria-label="Cutover sparse migration"]') as HTMLButtonElement
    expect(cutover.disabled).toBe(false)
    await act(async () => { cutover.click() })
    expect(document.body.textContent).toContain('1.10x')
    expect(document.body.textContent).toContain('Confirm Cutover')
    act(() => root.unmount())
  })

  it('disables cutover when coverage is incomplete', async () => {
    const { container, root } = await renderPanel({
      id: 'm1', profileId: 'p1', state: 'SHADOW_VALIDATING', sourceChunkCount: 100,
      indexedChunkCount: 99, expectedDimension: 8, actualDimension: 8,
      baselineP95Ms: 100, candidateP95Ms: 110, baselineFallbackRate: 0,
      candidateFallbackRate: 0, legacyBm25Enabled: false,
    })
    expect((container.querySelector('button[aria-label="Cutover sparse migration"]') as HTMLButtonElement).disabled).toBe(true)
    act(() => root.unmount())
  })

  it('loads retrieval profiles when the page does not provide them', async () => {
    api.listSparseMigrations.mockResolvedValue([])
    api.listRetrievalProfiles.mockResolvedValue([{ id: 'p1', name: 'candidate', version: 3 }])
    const container = document.createElement('div')
    document.body.appendChild(container)
    const root = createRoot(container)
    await act(async () => { root.render(<SparseMigrationPanel kbId="kb-1" />); await Promise.resolve() })
    expect(container.textContent).toContain('v3 candidate')
    act(() => root.unmount())
  })
})
