import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createKnowledgeBase, deleteKnowledgeBase, getKnowledgeBase, listKnowledgeBases } from './knowledgeBase'
import { deleteDocument, getIngestJob, listDocuments, uploadDocument } from './documents'

const apiClient = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
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
})
