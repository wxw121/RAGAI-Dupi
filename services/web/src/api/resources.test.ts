import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createKnowledgeBase, deleteKnowledgeBase, getKnowledgeBase, listKnowledgeBases } from './knowledgeBase'
import { deleteDocument, getIngestJob, listDocuments, uploadDocument } from './documents'
import {
  batchDeleteChatSessions,
  createChatSession,
  deleteChatSession,
  getChatSession,
  listChatSessions,
  renameChatSession,
} from './chatSessions'

const apiClient = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
  apiUpload: vi.fn(),
}))

vi.mock('./client', () => apiClient)

describe('resource API wrappers', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('builds knowledge base API paths', async () => {
    apiClient.apiGet.mockResolvedValueOnce([{ id: 'kb1' }]).mockResolvedValueOnce({ id: 'kb1' })
    apiClient.apiPost.mockResolvedValue({ id: 'kb2' })
    apiClient.apiDelete.mockResolvedValue(undefined)

    await expect(listKnowledgeBases()).resolves.toEqual([{ id: 'kb1' }])
    await expect(getKnowledgeBase('kb1')).resolves.toEqual({ id: 'kb1' })
    await expect(createKnowledgeBase({ name: 'KB' })).resolves.toEqual({ id: 'kb2' })
    await expect(deleteKnowledgeBase('kb1')).resolves.toBeUndefined()

    expect(apiClient.apiGet).toHaveBeenNthCalledWith(1, '/api/v1/knowledge-bases')
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(2, '/api/v1/knowledge-bases/kb1')
    expect(apiClient.apiPost).toHaveBeenCalledWith('/api/v1/knowledge-bases', { name: 'KB' })
    expect(apiClient.apiDelete).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb1')
  })

  it('builds document API paths', async () => {
    const file = new File(['abc'], 'a.txt')
    apiClient.apiGet.mockResolvedValueOnce([{ id: 'd1' }]).mockResolvedValueOnce({ id: 'j1' })
    apiClient.apiUpload.mockResolvedValue({ id: 'd2' })
    apiClient.apiDelete.mockResolvedValue(undefined)

    await expect(listDocuments('kb')).resolves.toEqual([{ id: 'd1' }])
    await expect(uploadDocument('kb', file)).resolves.toEqual({ id: 'd2' })
    await expect(deleteDocument('kb', 'doc')).resolves.toBeUndefined()
    await expect(getIngestJob('kb', 'doc')).resolves.toEqual({ id: 'j1' })

    expect(apiClient.apiGet).toHaveBeenNthCalledWith(1, '/api/v1/knowledge-bases/kb/documents')
    expect(apiClient.apiUpload).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/documents', file)
    expect(apiClient.apiDelete).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/documents/doc')
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(2, '/api/v1/knowledge-bases/kb/documents/doc/ingest-job')
  })

  it('builds chat session API paths', async () => {
    apiClient.apiGet.mockResolvedValueOnce([{ id: 's1' }]).mockResolvedValueOnce({ session: { id: 's1' }, messages: [] })
    apiClient.apiPost.mockResolvedValueOnce({ id: 's2' }).mockResolvedValueOnce(undefined)
    apiClient.apiPatch.mockResolvedValue({ id: 's1', title: 'New' })
    apiClient.apiDelete.mockResolvedValue(undefined)

    await expect(listChatSessions('kb')).resolves.toEqual([{ id: 's1' }])
    await expect(getChatSession('kb', 's1')).resolves.toEqual({ session: { id: 's1' }, messages: [] })
    await expect(createChatSession('kb', 'Title')).resolves.toEqual({ id: 's2' })
    await expect(renameChatSession('kb', 's1', 'New')).resolves.toEqual({ id: 's1', title: 'New' })
    await expect(deleteChatSession('kb', 's1')).resolves.toBeUndefined()
    await expect(batchDeleteChatSessions('kb', ['s1', 's2'])).resolves.toBeUndefined()

    expect(apiClient.apiGet).toHaveBeenNthCalledWith(1, '/api/v1/knowledge-bases/kb/chat-sessions')
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(2, '/api/v1/knowledge-bases/kb/chat-sessions/s1')
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(1, '/api/v1/knowledge-bases/kb/chat-sessions', { title: 'Title' })
    expect(apiClient.apiPatch).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/chat-sessions/s1', { title: 'New' })
    expect(apiClient.apiDelete).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/chat-sessions/s1')
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(2, '/api/v1/knowledge-bases/kb/chat-sessions/batch-delete', {
      sessionIds: ['s1', 's2'],
    })
  })
})
