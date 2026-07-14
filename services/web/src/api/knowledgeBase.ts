import { apiDelete, apiGet, apiGetText, apiPatch, apiPost } from './client'
import type {
  Account,
  AccountUpsertRequest,
  AuditAlert,
  AuditLog,
  AuditLogQuery,
  CreateKnowledgeBaseRequest,
  IngestJob,
  KnowledgeBaseExport,
  KnowledgeBaseImport,
  KnowledgeBase,
  OpsNotification,
  OpsMetadata,
  PasswordHashResponse,
  PasswordResetRequest,
  RagEvalCase,
  RagEvalCaseRequest,
  RagEvalRun,
  RagEvalRunRequest,
  RetrieveRequest,
  RetrieveResponse,
  Role,
  RoleRequest,
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

export function deleteE2eAccount(username: string): Promise<void> {
  return apiDelete(`${OPS_BASE}/accounts/${username}`)
}

export function exportKnowledgeBase(kbId: string): Promise<KnowledgeBaseExport> {
  return apiGet<KnowledgeBaseExport>(`${BASE}/${kbId}/export`)
}

export function importKnowledgeBase(request: KnowledgeBaseImport): Promise<KnowledgeBase> {
  return apiPost<KnowledgeBase>(`${BASE}/import`, request)
}

export function retrieveKnowledgeBase(kbId: string, request: RetrieveRequest): Promise<RetrieveResponse> {
  return apiPost<RetrieveResponse>(`${BASE}/${kbId}/retrieve`, request)
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

export function listRagEvalCases(kbId: string): Promise<RagEvalCase[]> {
  return apiGet<RagEvalCase[]>(`${BASE}/${kbId}/rag-eval/cases`)
}

export function createRagEvalCase(kbId: string, request: RagEvalCaseRequest): Promise<RagEvalCase> {
  return apiPost<RagEvalCase>(`${BASE}/${kbId}/rag-eval/cases`, request)
}

export function updateRagEvalCase(kbId: string, caseId: string, request: RagEvalCaseRequest): Promise<RagEvalCase> {
  return apiPatch<RagEvalCase>(`${BASE}/${kbId}/rag-eval/cases/${caseId}`, request)
}

export function deleteRagEvalCase(kbId: string, caseId: string): Promise<void> {
  return apiDelete(`${BASE}/${kbId}/rag-eval/cases/${caseId}`)
}

export function listRagEvalRuns(kbId: string): Promise<RagEvalRun[]> {
  return apiGet<RagEvalRun[]>(`${BASE}/${kbId}/rag-eval/runs`)
}

export function runRagEval(kbId: string, request: RagEvalRunRequest = {}): Promise<RagEvalRun> {
  return apiPost<RagEvalRun>(`${BASE}/${kbId}/rag-eval/runs`, request)
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

export function notifyAuditAlerts(): Promise<OpsNotification> {
  return apiPost<OpsNotification>(`${OPS_BASE}/audit-alerts/notify`)
}

export function listAccounts(): Promise<Account[]> {
  return apiGet<Account[]>(`${OPS_BASE}/accounts`)
}

export function listOpsMetadata(): Promise<OpsMetadata> {
  return apiGet<OpsMetadata>(`${OPS_BASE}/metadata`)
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

export function resetAccountPassword(username: string, request: PasswordResetRequest): Promise<Account> {
  return apiPost<Account>(`${OPS_BASE}/accounts/${username}/reset-password`, request)
}

export function generatePasswordHash(password: string): Promise<PasswordHashResponse> {
  return apiPost<PasswordHashResponse>(`${OPS_BASE}/accounts/password-hash`, { password })
}

export function listRoles(): Promise<Role[]> {
  return apiGet<Role[]>(`${OPS_BASE}/roles`)
}

export function createRole(request: RoleRequest): Promise<Role> {
  return apiPost<Role>(`${OPS_BASE}/roles`, request)
}

export function updateRole(roleId: string, request: RoleRequest): Promise<Role> {
  return apiPatch<Role>(`${OPS_BASE}/roles/${roleId}`, request)
}

export function disableRole(roleId: string): Promise<Role> {
  return apiPost<Role>(`${OPS_BASE}/roles/${roleId}/disable`)
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
