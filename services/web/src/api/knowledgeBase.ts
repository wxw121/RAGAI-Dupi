import { apiDelete, apiGet, apiGetText, apiPatch, apiPost } from './client'
import type {
  Account,
  AccountUpsertRequest,
  AuditAlert,
  AuditLog,
  AuditLogQuery,
  CreateKnowledgeBaseRequest,
  IngestJob,
  KnowledgeBase,
  PasswordHashResponse,
  VectorCleanupTask,
} from '@/types'

const BASE = '/api/v1/knowledge-bases'
const OPS_BASE = '/api/v1/ops'

export function listKnowledgeBases(): Promise<KnowledgeBase[]> {
  return apiGet<KnowledgeBase[]>(BASE)
}

export function getKnowledgeBase(kbId: string): Promise<KnowledgeBase> {
  return apiGet<KnowledgeBase>(`${BASE}/${kbId}`)
}

export function createKnowledgeBase(req: CreateKnowledgeBaseRequest): Promise<KnowledgeBase> {
  return apiPost<KnowledgeBase>(BASE, req)
}

export function deleteKnowledgeBase(kbId: string): Promise<void> {
  return apiDelete(`${BASE}/${kbId}`)
}

export function listIngestJobs(kbId: string): Promise<IngestJob[]> {
  return apiGet<IngestJob[]>(`${BASE}/${kbId}/ingest-jobs`)
}

export function reindexKnowledgeBase(kbId: string): Promise<IngestJob[]> {
  return apiPost<IngestJob[]>(`${BASE}/${kbId}/reindex`)
}

export function retryIngestJob(kbId: string, jobId: string): Promise<IngestJob> {
  return apiPost<IngestJob>(`${BASE}/${kbId}/ingest-jobs/${jobId}/retry`)
}

export function listVectorCleanupTasks(): Promise<VectorCleanupTask[]> {
  return apiGet<VectorCleanupTask[]>(`${OPS_BASE}/vector-cleanup-tasks`)
}

export function retryVectorCleanupTask(taskId: string): Promise<VectorCleanupTask> {
  return apiPost<VectorCleanupTask>(`${OPS_BASE}/vector-cleanup-tasks/${taskId}/retry`)
}

export function listAuditLogs(query: AuditLogQuery = {}): Promise<AuditLog[]> {
  return apiGet<AuditLog[]>(`${OPS_BASE}/audit-logs${toQueryString(query)}`)
}

export function exportAuditLogs(query: AuditLogQuery = {}): Promise<string> {
  return apiGetText(`${OPS_BASE}/audit-logs/export${toQueryString(query)}`)
}

export function listAuditAlerts(): Promise<AuditAlert[]> {
  return apiGet<AuditAlert[]>(`${OPS_BASE}/audit-alerts`)
}

export function listAccounts(): Promise<Account[]> {
  return apiGet<Account[]>(`${OPS_BASE}/accounts`)
}

export function createAccount(request: AccountUpsertRequest): Promise<Account> {
  return apiPost<Account>(`${OPS_BASE}/accounts`, request)
}

export function updateAccount(username: string, request: AccountUpsertRequest): Promise<Account> {
  return apiPatch<Account>(`${OPS_BASE}/accounts/${username}`, request)
}

export function disableAccount(username: string): Promise<Account> {
  return apiPost<Account>(`${OPS_BASE}/accounts/${username}/disable`)
}

export function enableAccount(username: string): Promise<Account> {
  return apiPost<Account>(`${OPS_BASE}/accounts/${username}/enable`)
}

export function rotateAccountToken(username: string): Promise<Account> {
  return apiPost<Account>(`${OPS_BASE}/accounts/${username}/rotate-token`)
}

export function generatePasswordHash(password: string): Promise<PasswordHashResponse> {
  return apiPost<PasswordHashResponse>(`${OPS_BASE}/accounts/password-hash`, { password })
}

function toQueryString(query: AuditLogQuery): string {
  const params = new URLSearchParams()
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  })
  const queryString = params.toString()
  return queryString ? `?${queryString}` : ''
}
