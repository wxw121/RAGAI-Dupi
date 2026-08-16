import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { KbDetailPage } from './KbDetailPage'

const api = vi.hoisted(() => ({
  getKnowledgeBase: vi.fn(),
  listIngestJobs: vi.fn(),
  listOpsMetadata: vi.fn(),
  listKnowledgeBaseVectorCleanupTasks: vi.fn(),
  reindexKnowledgeBase: vi.fn(),
  retryIngestJob: vi.fn(),
  retryKnowledgeBaseVectorCleanupTask: vi.fn(),
  updateKnowledgeBaseRetrievalProfile: vi.fn(),
}))

const documentApi = vi.hoisted(() => ({
  deleteDocument: vi.fn(),
  getDocumentIndexDetail: vi.fn(),
  getIngestJob: vi.fn(),
  listDocuments: vi.fn(),
  uploadDocuments: vi.fn(),
}))

const chatSessionApi = vi.hoisted(() => ({
  listChatSessions: vi.fn(),
}))

const toast = vi.hoisted(() => ({ showError: vi.fn(), showSuccess: vi.fn() }))

vi.mock('@/api/knowledgeBase', () => api)
vi.mock('@/api/documents', () => documentApi)
vi.mock('@/api/chatSessions', () => chatSessionApi)
vi.mock('@/components/AppLayout', () => ({ AppLayout: ({ children }: { children: React.ReactNode }) => children }))
vi.mock('@/components/ChatPanel', () => ({ ChatPanel: () => null }))
vi.mock('@/components/DocTable', () => ({ DocTable: () => null }))
vi.mock('@/components/DocumentIndexDetailPanel', () => ({ DocumentIndexDetailPanel: () => null }))
vi.mock('@/components/RagEvalPanel', () => ({ RagEvalPanel: () => null }))
vi.mock('@/components/UploadZone', () => ({ UploadZone: () => null }))
vi.mock('@/components/Toast', () => ({ useToast: () => toast }))
vi.mock('react-router-dom', () => ({
  Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
  useParams: () => ({ kbId: 'kb-1' }),
  useSearchParams: () => [new URLSearchParams(), vi.fn()],
}))

describe('KbDetailPage', () => {
  let root: Root | null = null
  let container: HTMLDivElement | null = null

  afterEach(() => {
    if (root) act(() => root?.unmount())
    container?.remove()
    root = null
    container = null
    vi.clearAllMocks()
  })

  it('updates the knowledge base retrieval profile from settings', async () => {
    const knowledgeBase = {
      id: 'kb-1',
      name: 'Quality KB',
      description: null,
      retrievalProfile: 'CLASSIC',
      indexSchemaVersion: 2,
      profileIndexReady: true,
      indexRevision: 3,
      retrievalProfileGateDecisions: {
        PARENT_CHILD: { candidate: 'PARENT_CHILD', status: 'PASSED', reason: 'passed' },
        QA_ASSISTED: { candidate: 'QA_ASSISTED', status: 'BLOCKED', reason: 'hit_rate_regressed' },
      },
      embeddingConfigCurrent: true,
      embeddingConfigWarning: null,
    }
    api.getKnowledgeBase.mockResolvedValue(knowledgeBase)
    api.listIngestJobs.mockResolvedValue([])
    api.listOpsMetadata.mockResolvedValue({ guardrails: null })
    api.listKnowledgeBaseVectorCleanupTasks.mockResolvedValue([])
    documentApi.listDocuments.mockResolvedValue([])
    chatSessionApi.listChatSessions.mockResolvedValue([])
    api.updateKnowledgeBaseRetrievalProfile.mockResolvedValue({
      ...knowledgeBase,
      retrievalProfile: 'PARENT_CHILD',
    })

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<KbDetailPage />)
      await Promise.resolve()
      await Promise.resolve()
    })

    const profileSelect = container.querySelector<HTMLSelectElement>('select[name="retrievalProfile"]')
    expect(profileSelect).not.toBeNull()
    expect(profileSelect?.value).toBe('CLASSIC')
    expect(container.textContent).toContain('Profile index: Ready')
    expect(container.textContent).toContain('Revision 3')
    expect(container.textContent).toContain('Parent-child PASSED')
    expect(container.textContent).toContain('QA-assisted BLOCKED')
    expect(Array.from(profileSelect?.options ?? []).find((option) => option.value === 'QA_ASSISTED')?.disabled).toBe(true)

    await act(async () => {
      setNativeValue(profileSelect!, 'PARENT_CHILD')
      profileSelect?.dispatchEvent(new Event('change', { bubbles: true }))
    })

    await act(async () => {
      const saveButton = Array.from(container?.querySelectorAll('button') ?? [])
        .find((button) => button.textContent?.includes('保存索引模式'))
      saveButton?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
    })

    expect(api.updateKnowledgeBaseRetrievalProfile).toHaveBeenCalledWith('kb-1', 'PARENT_CHILD')
    expect(api.listKnowledgeBaseVectorCleanupTasks).toHaveBeenCalledWith('kb-1')
    expect(profileSelect?.value).toBe('PARENT_CHILD')
    expect(api.listIngestJobs).toHaveBeenCalledTimes(2)
    expect(documentApi.listDocuments).toHaveBeenCalledTimes(2)
  })

  it('shows the number of chat sessions in the chat tab badge', async () => {
    api.getKnowledgeBase.mockResolvedValue({
      id: 'kb-1',
      name: 'Session count KB',
      retrievalProfile: 'CLASSIC',
      embeddingConfigCurrent: true,
    })
    api.listIngestJobs.mockResolvedValue([])
    api.listOpsMetadata.mockResolvedValue({ guardrails: null })
    api.listKnowledgeBaseVectorCleanupTasks.mockResolvedValue([])
    documentApi.listDocuments.mockResolvedValue(Array.from({ length: 5 }, (_, index) => ({
      id: `doc-${index}`,
      status: 'COMPLETED',
    })))
    chatSessionApi.listChatSessions.mockResolvedValue([
      { id: 'session-1' },
      { id: 'session-2' },
      { id: 'session-3' },
    ])

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<KbDetailPage />)
      await Promise.resolve()
      await Promise.resolve()
    })

    const tabBadges = Array.from(container.querySelectorAll('button span.rounded-full'))
      .map((badge) => badge.textContent?.trim())
    expect(tabBadges).toEqual(['5', '3'])
    expect(chatSessionApi.listChatSessions).toHaveBeenCalledWith('kb-1')
  })
})

function setNativeValue(element: HTMLSelectElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(element, value)
}
