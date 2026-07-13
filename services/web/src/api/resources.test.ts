import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  getKnowledgeBase,
  listKnowledgeBases,
  listIngestJobs,
  listAuditLogs,
  listAuditAlerts,
  listAccounts,
  exportAuditLogs,
  createAccount,
  retrieveKnowledgeBase,
  listVectorCleanupTasks,
  reindexKnowledgeBase,
  rotateAccountToken,
  retryIngestJob,
  retryVectorCleanupTask,
  updateAccount,
  disableAccount,
  enableAccount,
  generatePasswordHash,
  listRagEvalCases,
  createRagEvalCase,
  updateRagEvalCase,
  deleteRagEvalCase,
  listRagEvalRuns,
  runRagEval,
  exportKnowledgeBase,
  importKnowledgeBase,
  notifyAuditAlerts,
} from './knowledgeBase'
import { deleteDocument, getDocumentIndexDetail, getIngestJob, listDocuments, uploadDocument, uploadDocuments } from './documents'
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
  apiGetText: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
  apiUpload: vi.fn(),
  apiUploadMany: vi.fn(),
}))

vi.mock('./client', () => apiClient)

describe('resource API wrappers', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiClient.apiGet.mockReset()
    apiClient.apiGetText.mockReset()
    apiClient.apiPost.mockReset()
    apiClient.apiPatch.mockReset()
    apiClient.apiDelete.mockReset()
    apiClient.apiUpload.mockReset()
    apiClient.apiUploadMany.mockReset()
  })

  it('builds knowledge base API paths', async () => {
    apiClient.apiGet.mockResolvedValueOnce([{ id: 'kb1' }]).mockResolvedValueOnce({ id: 'kb1' })
    apiClient.apiGet.mockResolvedValueOnce([{ id: 'job1' }])
    apiClient.apiGet.mockResolvedValueOnce([{ id: 'cleanup1' }])
    apiClient.apiGet.mockResolvedValueOnce([{ id: 'audit1' }])
    apiClient.apiGet.mockResolvedValueOnce([{ code: 'AUDIT_FAILED_SPIKE' }])
    apiClient.apiGet.mockResolvedValueOnce([{ username: 'admin' }])
    apiClient.apiGet.mockResolvedValueOnce([{ id: 'case1' }])
    apiClient.apiGet.mockResolvedValueOnce([{ id: 'run1' }])
    apiClient.apiGet.mockResolvedValueOnce({ knowledgeBase: { name: 'KB' } })
    apiClient.apiGetText.mockResolvedValueOnce('csv-body')
    apiClient.apiPost
      .mockResolvedValueOnce({ id: 'kb2' })
      .mockResolvedValueOnce({ query: 'q', hits: [] })
      .mockResolvedValueOnce([{ id: 'job2' }])
      .mockResolvedValueOnce({ id: 'job3' })
      .mockResolvedValueOnce({ id: 'cleanup1' })
      .mockResolvedValueOnce({ username: 'analyst' })
      .mockResolvedValueOnce({ username: 'analyst', disabled: true })
      .mockResolvedValueOnce({ username: 'analyst', disabled: false })
      .mockResolvedValueOnce({ username: 'analyst', tokenVersion: '2' })
      .mockResolvedValueOnce({ passwordHash: 'pbkdf2$hash' })
      .mockResolvedValueOnce({ id: 'case1' })
      .mockResolvedValueOnce({ id: 'run1' })
      .mockResolvedValueOnce({ id: 'imported' })
      .mockResolvedValueOnce({ delivered: true })
    apiClient.apiPatch.mockResolvedValueOnce({ username: 'analyst' })
    apiClient.apiPatch.mockResolvedValueOnce({ id: 'case1', query: 'new' })
    apiClient.apiDelete.mockResolvedValue(undefined)

    await expect(listKnowledgeBases()).resolves.toEqual([{ id: 'kb1' }])
    await expect(getKnowledgeBase('kb1')).resolves.toEqual({ id: 'kb1' })
    await expect(createKnowledgeBase({ name: 'KB' })).resolves.toEqual({ id: 'kb2' })
    await expect(retrieveKnowledgeBase('kb1', { query: 'q', topK: 3 })).resolves.toEqual({ query: 'q', hits: [] })
    await expect(listIngestJobs('kb1')).resolves.toEqual([{ id: 'job1' }])
    await expect(listVectorCleanupTasks()).resolves.toEqual([{ id: 'cleanup1' }])
    await expect(listAuditLogs({ tenantId: 'tenant-a', action: 'DOCUMENT_DELETE', status: 'SUCCESS', limit: 25 }))
      .resolves.toEqual([{ id: 'audit1' }])
    await expect(listAuditAlerts()).resolves.toEqual([{ code: 'AUDIT_FAILED_SPIKE' }])
    await expect(listAccounts()).resolves.toEqual([{ username: 'admin' }])
    await expect(listRagEvalCases('kb1')).resolves.toEqual([{ id: 'case1' }])
    await expect(listRagEvalRuns('kb1')).resolves.toEqual([{ id: 'run1' }])
    await expect(exportKnowledgeBase('kb1')).resolves.toEqual({ knowledgeBase: { name: 'KB' } })
    await expect(exportAuditLogs({ tenantId: 'tenant-a', targetType: 'DOCUMENT', status: 'FAILED' }))
      .resolves.toEqual('csv-body')
    await expect(reindexKnowledgeBase('kb1')).resolves.toEqual([{ id: 'job2' }])
    await expect(retryIngestJob('kb1', 'job3')).resolves.toEqual({ id: 'job3' })
    await expect(retryVectorCleanupTask('cleanup1')).resolves.toEqual({ id: 'cleanup1' })
    await expect(createAccount({ username: 'analyst', password: 'secret' })).resolves.toEqual({ username: 'analyst' })
    await expect(updateAccount('analyst', { role: 'USER', permissions: ['KB_READ'] })).resolves.toEqual({ username: 'analyst' })
    await expect(disableAccount('analyst')).resolves.toEqual({ username: 'analyst', disabled: true })
    await expect(enableAccount('analyst')).resolves.toEqual({ username: 'analyst', disabled: false })
    await expect(rotateAccountToken('analyst')).resolves.toEqual({ username: 'analyst', tokenVersion: '2' })
    await expect(generatePasswordHash('secret')).resolves.toEqual({ passwordHash: 'pbkdf2$hash' })
    await expect(createRagEvalCase('kb1', { caseKey: 'case', query: 'q', minHits: 1 })).resolves.toEqual({ id: 'case1' })
    await expect(updateRagEvalCase('kb1', 'case1', { caseKey: 'case', query: 'new', minHits: 1 })).resolves.toEqual({ id: 'case1', query: 'new' })
    await expect(runRagEval('kb1', { useRerank: true })).resolves.toEqual({ id: 'run1' })
    await expect(importKnowledgeBase({ knowledgeBase: { name: 'KB' }, evalCases: [] })).resolves.toEqual({ id: 'imported' })
    await expect(notifyAuditAlerts()).resolves.toEqual({ delivered: true })
    await expect(deleteRagEvalCase('kb1', 'case1')).resolves.toBeUndefined()
    await expect(deleteKnowledgeBase('kb1')).resolves.toBeUndefined()

    expect(apiClient.apiGet).toHaveBeenNthCalledWith(1, '/api/v1/knowledge-bases')
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(2, '/api/v1/knowledge-bases/kb1')
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(3, '/api/v1/knowledge-bases/kb1/ingest-jobs')
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(4, '/api/v1/ops/vector-cleanup-tasks')
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(
      5,
      '/api/v1/ops/audit-logs?tenantId=tenant-a&action=DOCUMENT_DELETE&status=SUCCESS&limit=25',
    )
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(6, '/api/v1/ops/audit-alerts')
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(7, '/api/v1/ops/accounts')
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(8, '/api/v1/knowledge-bases/kb1/rag-eval/cases')
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(9, '/api/v1/knowledge-bases/kb1/rag-eval/runs')
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(10, '/api/v1/knowledge-bases/kb1/export')
    expect(apiClient.apiGetText).toHaveBeenCalledWith(
      '/api/v1/ops/audit-logs/export?tenantId=tenant-a&targetType=DOCUMENT&status=FAILED',
    )
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(1, '/api/v1/knowledge-bases', { name: 'KB' })
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(2, '/api/v1/knowledge-bases/kb1/retrieve', { query: 'q', topK: 3 })
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(3, '/api/v1/knowledge-bases/kb1/reindex')
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(4, '/api/v1/knowledge-bases/kb1/ingest-jobs/job3/retry')
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(5, '/api/v1/ops/vector-cleanup-tasks/cleanup1/retry')
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(6, '/api/v1/ops/accounts', { username: 'analyst', password: 'secret' })
    expect(apiClient.apiPatch).toHaveBeenCalledWith('/api/v1/ops/accounts/analyst', {
      role: 'USER',
      permissions: ['KB_READ'],
    })
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(7, '/api/v1/ops/accounts/analyst/disable')
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(8, '/api/v1/ops/accounts/analyst/enable')
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(9, '/api/v1/ops/accounts/analyst/rotate-token')
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(10, '/api/v1/ops/accounts/password-hash', { password: 'secret' })
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(11, '/api/v1/knowledge-bases/kb1/rag-eval/cases', { caseKey: 'case', query: 'q', minHits: 1 })
    expect(apiClient.apiPatch).toHaveBeenNthCalledWith(2, '/api/v1/knowledge-bases/kb1/rag-eval/cases/case1', { caseKey: 'case', query: 'new', minHits: 1 })
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(12, '/api/v1/knowledge-bases/kb1/rag-eval/runs', { useRerank: true })
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(13, '/api/v1/knowledge-bases/import', { knowledgeBase: { name: 'KB' }, evalCases: [] })
    expect(apiClient.apiPost).toHaveBeenNthCalledWith(14, '/api/v1/ops/audit-alerts/notify')
    expect(apiClient.apiDelete).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb1/rag-eval/cases/case1')
    expect(apiClient.apiDelete).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb1')
  })

  it('builds document API paths', async () => {
    const file = new File(['abc'], 'a.txt')
    const secondFile = new File(['def'], 'b.txt')
    apiClient.apiGet.mockResolvedValueOnce([{ id: 'd1' }]).mockResolvedValueOnce({ id: 'j1' }).mockResolvedValueOnce({ chunkCount: 2 })
    apiClient.apiUpload.mockResolvedValue({ id: 'd2' })
    const batchUpload = {
      total: 2,
      succeeded: 1,
      failed: 1,
      results: [{ fileName: 'b.txt', success: true, errorMessage: null, document: { id: 'd3' } }],
    }
    apiClient.apiUploadMany.mockResolvedValue(batchUpload)
    apiClient.apiDelete.mockResolvedValue(undefined)

    await expect(listDocuments('kb')).resolves.toEqual([{ id: 'd1' }])
    await expect(uploadDocument('kb', file)).resolves.toEqual({ id: 'd2' })
    await expect(uploadDocuments('kb', [file, secondFile])).resolves.toEqual(batchUpload)
    await expect(deleteDocument('kb', 'doc')).resolves.toBeUndefined()
    await expect(getIngestJob('kb', 'doc')).resolves.toEqual({ id: 'j1' })
    await expect(getDocumentIndexDetail('kb', 'doc')).resolves.toEqual({ chunkCount: 2 })

    expect(apiClient.apiGet).toHaveBeenNthCalledWith(1, '/api/v1/knowledge-bases/kb/documents')
    expect(apiClient.apiUpload).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/documents', file)
    expect(apiClient.apiUploadMany).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/documents/batch', [file, secondFile])
    expect(apiClient.apiDelete).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/documents/doc')
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(2, '/api/v1/knowledge-bases/kb/documents/doc/ingest-job')
    expect(apiClient.apiGet).toHaveBeenNthCalledWith(3, '/api/v1/knowledge-bases/kb/documents/doc/index-detail')
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
