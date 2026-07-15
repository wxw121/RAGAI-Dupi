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
  RagQualityPolicy,
  RagQualityPolicyRequest,
  RetrievalProfile,
  RetrievalProfileRequest,
  SparseMigration,
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

export function getRagQualityPolicy(kbId: string): Promise<RagQualityPolicy> {
  return apiGet<RagQualityPolicy>(`${BASE}/${kbId}/rag-eval/policy`)
}

export function updateRagQualityPolicy(kbId: string, request: RagQualityPolicyRequest): Promise<RagQualityPolicy> {
  return apiPatch<RagQualityPolicy>(`${BASE}/${kbId}/rag-eval/policy`, request)
}

export function promoteRagEvalBaseline(kbId: string, runId: string): Promise<RagQualityPolicy> {
  return apiPost<RagQualityPolicy>(`${BASE}/${kbId}/rag-eval/runs/${runId}/baseline`)
}

export function listRetrievalProfiles(kbId: string): Promise<RetrievalProfile[]> {
  return apiGet<RetrievalProfile[]>(`${BASE}/${kbId}/retrieval-profiles`)
}

export function createRetrievalProfile(kbId: string, request: RetrievalProfileRequest): Promise<RetrievalProfile> {
  return apiPost<RetrievalProfile>(`${BASE}/${kbId}/retrieval-profiles`, request)
}

export function activateRetrievalProfile(kbId: string, profileId: string): Promise<RetrievalProfile> {
  return apiPost<RetrievalProfile>(`${BASE}/${kbId}/retrieval-profiles/${profileId}/activate`)
}

export function rollbackRetrievalProfile(kbId: string, profileId: string): Promise<RetrievalProfile> {
  return apiPost<RetrievalProfile>(`${BASE}/${kbId}/retrieval-profiles/${profileId}/rollback`)
}

export function listSparseMigrations(kbId: string): Promise<SparseMigration[]> {
  return apiGet<SparseMigration[]>(`${BASE}/${kbId}/sparse-migrations`)
}

export function startSparseMigration(kbId: string, profileId: string): Promise<SparseMigration> {
  return apiPost<SparseMigration>(`${BASE}/${kbId}/sparse-migrations?profileId=${encodeURIComponent(profileId)}`)
}

export function backfillSparseMigration(kbId: string, migrationId: string): Promise<SparseMigration> {
  return apiPost<SparseMigration>(`${BASE}/${kbId}/sparse-migrations/${migrationId}/backfill`)
}

export function beginSparseShadowValidation(kbId: string, migrationId: string): Promise<SparseMigration> {
  return apiPost<SparseMigration>(`${BASE}/${kbId}/sparse-migrations/${migrationId}/shadow`)
}

export function cutoverSparseMigration(kbId: string, migrationId: string): Promise<SparseMigration> {
  return apiPost<SparseMigration>(`${BASE}/${kbId}/sparse-migrations/${migrationId}/cutover`)
}

export function completeSparseMigration(kbId: string, migrationId: string): Promise<SparseMigration> {
  return apiPost<SparseMigration>(`${BASE}/${kbId}/sparse-migrations/${migrationId}/complete`)
}

export function setLegacySparseFallback(kbId: string, migrationId: string, enabled: boolean): Promise<SparseMigration> {
  return apiPost<SparseMigration>(`${BASE}/${kbId}/sparse-migrations/${migrationId}/legacy-fallback?enabled=${enabled}`)
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
