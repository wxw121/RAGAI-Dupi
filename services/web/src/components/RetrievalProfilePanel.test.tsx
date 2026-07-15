import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RetrievalProfilePanel } from './RetrievalProfilePanel'

const api = vi.hoisted(() => ({
  listRetrievalProfiles: vi.fn(), createRetrievalProfile: vi.fn(),
  activateRetrievalProfile: vi.fn(), rollbackRetrievalProfile: vi.fn(),
}))
vi.mock('@/api/knowledgeBase', () => api)
vi.mock('@/components/Toast', () => ({ useToast: () => ({ showError: vi.fn(), showSuccess: vi.fn() }) }))

describe('RetrievalProfilePanel', () => {
  afterEach(() => { vi.clearAllMocks() })
  it('renders active and rollback profile actions', async () => {
    api.listRetrievalProfiles.mockResolvedValue([
      { id: 'p2', version: 2, name: 'current', active: true, vectorCandidateCount: 30, sparseCandidateCount: 30, rrfConstant: 60, rerankEnabled: true, rerankCandidateLimit: 20, finalTopK: 5 },
      { id: 'p1', version: 1, name: 'stable', active: false, vectorCandidateCount: 20, sparseCandidateCount: 20, rrfConstant: 60, rerankEnabled: false, rerankCandidateLimit: 10, finalTopK: 5 },
    ])
    const container = document.createElement('div')
    document.body.appendChild(container)
    const root = createRoot(container)
    await act(async () => { root.render(<RetrievalProfilePanel kbId="kb-1" />); await Promise.resolve() })
    expect(container.textContent).toContain('v2 · current')
    expect(container.querySelector('[aria-label="回滚到 v1"]')).not.toBeNull()
    act(() => root.unmount())
    container.remove()
  })
})
