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
  getUploadQuota: vi.fn(),
  listDocuments: vi.fn(),
  uploadMarkdownPackage: vi.fn(),
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
vi.mock('@/components/UploadZone', () => ({
  UploadZone: ({ onPackageUpload, disabled }: { onPackageUpload?: (file: File) => Promise<void>; disabled?: boolean }) => (
    <button data-testid="markdown-package" disabled={disabled} onClick={() => void onPackageUpload?.(new File(['zip'], 'docs.zip'))}>package</button>
  ),
}))
vi.mock('@/components/OperationProgress', () => ({
  OperationProgress: ({ initialJob, onCompleted }: { initialJob: { id: string }; onCompleted: () => void }) => (
    <button data-testid="operation-progress" onClick={() => { void Promise.resolve(onCompleted()).catch(() => undefined) }}>{initialJob.id}</button>
  ),
}))
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
    vi.useRealTimers()
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

  it('polls an initial upload intent until it becomes a failed durable job', async () => {
    vi.useFakeTimers()
    api.getKnowledgeBase.mockResolvedValue({
      id: 'kb-1', name: 'Slow upload KB', retrievalProfile: 'CLASSIC', embeddingConfigCurrent: true,
    })
    api.listOpsMetadata.mockResolvedValue({ guardrails: null })
    api.listKnowledgeBaseVectorCleanupTasks.mockResolvedValue([])
    chatSessionApi.listChatSessions.mockResolvedValue([])
    documentApi.getUploadQuota.mockResolvedValue(null)
    documentApi.listDocuments
      .mockResolvedValueOnce([{ id: 'doc-1', status: 'UPLOADING' }])
      .mockResolvedValueOnce([{ id: 'doc-1', status: 'FAILED' }])
    api.listIngestJobs
      .mockResolvedValueOnce([{ id: 'job-1', docId: 'doc-1', status: 'UPLOAD_INTENT', stage: 'UPLOAD_PENDING' }])
      .mockResolvedValueOnce([{ id: 'job-1', docId: 'doc-1', status: 'FAILED', stage: 'FAILED' }])

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<KbDetailPage />)
      await Promise.resolve()
      await Promise.resolve()
    })

    const reindexButton = Array.from(container.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.includes('重建索引'))
    expect(reindexButton?.disabled).toBe(true)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000)
    })

    expect(documentApi.listDocuments).toHaveBeenCalledTimes(2)
    expect(api.listIngestJobs).toHaveBeenCalledTimes(2)
    expect(reindexButton?.disabled).toBe(false)
  })

  it('disables reindex while durable document deletion is in progress', async () => {
    api.getKnowledgeBase.mockResolvedValue({
      id: 'kb-1', name: 'Deleting document KB', retrievalProfile: 'CLASSIC', embeddingConfigCurrent: true,
    })
    api.listOpsMetadata.mockResolvedValue({ guardrails: null })
    api.listKnowledgeBaseVectorCleanupTasks.mockResolvedValue([])
    api.listIngestJobs.mockResolvedValue([])
    chatSessionApi.listChatSessions.mockResolvedValue([])
    documentApi.getUploadQuota.mockResolvedValue(null)
    documentApi.listDocuments.mockResolvedValue([{ id: 'doc-1', status: 'DELETING' }])

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<KbDetailPage />)
      await Promise.resolve()
      await Promise.resolve()
    })

    const reindexButton = Array.from(container.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.includes('重建索引'))
    expect(reindexButton?.disabled).toBe(true)
  })

  it('tracks Markdown package import and refreshes documents after completion', async () => {
    api.getKnowledgeBase.mockResolvedValue({
      id: 'kb-1', name: 'Markdown KB', retrievalProfile: 'CLASSIC', embeddingConfigCurrent: true,
    })
    api.listOpsMetadata.mockResolvedValue({ guardrails: null })
    api.listKnowledgeBaseVectorCleanupTasks.mockResolvedValue([])
    api.listIngestJobs.mockResolvedValue([])
    chatSessionApi.listChatSessions.mockResolvedValue([])
    documentApi.getUploadQuota.mockResolvedValue(null)
    documentApi.listDocuments.mockResolvedValue([])
    documentApi.uploadMarkdownPackage.mockResolvedValue(operationJob('markdown-job'))

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => { root?.render(<KbDetailPage />); await Promise.resolve(); await Promise.resolve() })
    const documentCallsBeforeUpload = documentApi.listDocuments.mock.calls.length

    await act(async () => {
      container?.querySelector<HTMLButtonElement>('[data-testid="markdown-package"]')?.click()
      await Promise.resolve()
    })
    expect(container?.textContent).toContain('markdown-job')
    expect(documentApi.listDocuments).toHaveBeenCalledTimes(documentCallsBeforeUpload)

    await act(async () => {
      container?.querySelector<HTMLButtonElement>('[data-testid="operation-progress"]')?.click()
      await Promise.resolve()
    })
    expect(documentApi.listDocuments.mock.calls.length).toBeGreaterThan(documentCallsBeforeUpload)
  })

  it('blocks a second Markdown package while the first operation is retained', async () => {
    api.getKnowledgeBase.mockResolvedValue({ id: 'kb-1', name: 'Markdown KB', retrievalProfile: 'CLASSIC', embeddingConfigCurrent: true })
    api.listOpsMetadata.mockResolvedValue({ guardrails: null })
    api.listKnowledgeBaseVectorCleanupTasks.mockResolvedValue([])
    api.listIngestJobs.mockResolvedValue([])
    chatSessionApi.listChatSessions.mockResolvedValue([])
    documentApi.getUploadQuota.mockResolvedValue(null)
    documentApi.listDocuments.mockResolvedValue([])
    documentApi.uploadMarkdownPackage.mockResolvedValue(operationJob('markdown-job'))
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => { root?.render(<KbDetailPage />); await Promise.resolve(); await Promise.resolve() })

    const upload = container.querySelector<HTMLButtonElement>('[data-testid="markdown-package"]')
    await act(async () => { upload?.click(); await Promise.resolve() })
    expect(upload?.disabled).toBe(true)
    await act(async () => { upload?.click(); await Promise.resolve() })
    expect(documentApi.uploadMarkdownPackage).toHaveBeenCalledTimes(1)
  })

  it('retains completed Markdown import evidence when document refresh fails', async () => {
    api.getKnowledgeBase.mockResolvedValue({ id: 'kb-1', name: 'Markdown KB', retrievalProfile: 'CLASSIC', embeddingConfigCurrent: true })
    api.listOpsMetadata.mockResolvedValue({ guardrails: null })
    api.listKnowledgeBaseVectorCleanupTasks.mockResolvedValue([])
    api.listIngestJobs.mockResolvedValue([])
    chatSessionApi.listChatSessions.mockResolvedValue([])
    documentApi.getUploadQuota.mockResolvedValue(null)
    documentApi.listDocuments.mockResolvedValue([])
    documentApi.uploadMarkdownPackage.mockResolvedValue(operationJob('markdown-job'))
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => { root?.render(<KbDetailPage />); await Promise.resolve(); await Promise.resolve() })
    await act(async () => { container?.querySelector<HTMLButtonElement>('[data-testid="markdown-package"]')?.click(); await Promise.resolve() })
    toast.showSuccess.mockClear()
    documentApi.listDocuments.mockRejectedValueOnce(new Error('refresh unavailable'))
    await act(async () => {
      container?.querySelector<HTMLButtonElement>('[data-testid="operation-progress"]')?.click()
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(container?.textContent).toContain('markdown-job')
    expect(toast.showSuccess).not.toHaveBeenCalledWith('Markdown 资源包导入完成')
  })
})

function operationJob(id: string) {
  return {
    id, operationType: 'MARKDOWN_PACKAGE_IMPORT', aggregateType: 'KNOWLEDGE_BASE', aggregateId: 'kb-1',
    status: 'RUNNING', attemptCount: 0, nextAttemptAt: null, errorCode: null, errorMessage: null,
    createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z', completedAt: null, steps: [],
  }
}

function setNativeValue(element: HTMLSelectElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(element, value)
}
