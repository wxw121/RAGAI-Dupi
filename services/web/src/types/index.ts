export type RetrievalProfile = 'CLASSIC' | 'PARENT_CHILD' | 'QA_ASSISTED' | 'COMBINED'

export type RagEvalGateStatus =
  | 'PASSED'
  | 'BLOCKED'
  | 'INSUFFICIENT_DATA'
  | 'STALE'
  | 'INDEX_NOT_READY'
  | 'NOT_EVALUATED'

export interface RagEvalProfileMetrics {
  profile: RetrievalProfile
  totalCases: number
  passedCount: number
  hitPassedCount: number
  citationEligibleCount: number
  citationPassedCount: number
  passRate: number
  hitRate: number
  citationPassRate: number
}

export interface RagEvalGateDecision {
  candidate: RetrievalProfile
  baseline?: RetrievalProfile
  status: RagEvalGateStatus
  reason: string
  metrics?: RagEvalProfileMetrics
  classicMetrics?: RagEvalProfileMetrics
  hitRateDelta?: number
  citationPassRateDelta?: number
  runRevision?: number | null
  currentRevision?: number | null
  indexReady?: boolean
}

export interface KnowledgeBase {
  id: string
  tenantId: string
  name: string
  description: string | null
  chunkSize: number
  chunkOverlap: number
  topK: number
  embeddingModel: string
  embeddingDimension: number
  embeddingConfigCurrent: boolean
  embeddingConfigWarning: string | null
  chunkStrategy: string
  retrievalMode: string
  retrievalProfile: RetrievalProfile
  indexSchemaVersion?: number
  profileIndexReady?: boolean
  indexRevision?: number
  retrievalProfileGateDecisions?: Partial<Record<RetrievalProfile, RagEvalGateDecision>>
  createdAt: string
  updatedAt: string
}

export interface CreateKnowledgeBaseRequest {
  name: string
  description?: string
  chunkSize?: number
  chunkOverlap?: number
  topK?: number
  retrievalMode?: 'VECTOR' | 'HYBRID'
  retrievalProfile?: RetrievalProfile
}

export interface Document {
  id: string
  kbId: string
  fileName: string
  mimeType: string
  fileSize: number
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface BatchDocumentUploadResult {
  fileName: string
  success: boolean
  errorMessage: string | null
  document: Document | null
}

export interface BatchDocumentUploadResponse {
  total: number
  succeeded: number
  failed: number
  results: BatchDocumentUploadResult[]
}

export interface IngestJob {
  id: string
  kbId: string
  docId: string
  documentFileName?: string | null
  documentStatus?: Document['status'] | null
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'DEAD_LETTER'
  stage: string | null
  retryCount: number
  errorMessage: string | null
  diagnosis?: IngestDiagnosis | null
  createdAt: string
  updatedAt: string
}

export interface IngestDiagnosis {
  severity: 'info' | 'warning' | 'error'
  summary: string
  nextAction: string
  retryable: boolean
  stalled: boolean
  ageSeconds: number
  lastUpdatedSeconds: number
}

export interface VectorCleanupTask {
  id: string
  targetType:
    | 'PROFILE_KNOWLEDGE_BASE'
    | 'PROFILE_DOCUMENT'
    | 'LEGACY_KNOWLEDGE_BASE'
    | 'LEGACY_DOCUMENT'
  targetId: string
  status: 'PENDING' | 'COMPLETED' | 'FAILED'
  attemptCount: number
  lastError: string | null
  nextAttemptAt: string
  createdAt: string
  updatedAt: string
}

export interface AuditLog {
  id: string
  tenantId: string
  action: string
  targetType: string
  targetId: string | null
  status: 'SUCCESS' | 'FAILED'
  message: string | null
  errorMessage: string | null
  createdAt: string
}

export interface AuditLogQuery {
  tenantId?: string
  action?: string
  targetType?: string
  status?: 'SUCCESS' | 'FAILED' | ''
  limit?: number
}

export interface AuditAlert {
  code: string
  severity: string
  message: string
  count: number
  threshold: number
  windowStart?: string | null
  windowEnd?: string | null
}

export interface Account {
  username: string
  tenantId: string
  role: string
  roleCode: string
  roleName: string
  permissions: string[]
  knowledgeBaseIds: string[]
  tokenVersion: string
  passwordConfigured: boolean
  passwordHashConfigured: boolean
  disabled: boolean
}

export interface AccountUpsertRequest {
  username?: string
  password?: string
  passwordHash?: string
  tenantId?: string
  role?: string
  roleCode?: string
  permissions?: string[]
  knowledgeBaseIds?: string[]
  tokenVersion?: string
  disabled?: boolean
}

export interface PasswordHashResponse {
  passwordHash: string
}

export interface PasswordResetRequest {
  password: string
}

export interface Role {
  id: string
  code: string
  name: string
  description: string | null
  permissions: string[]
  systemBuiltin: boolean
  disabled: boolean
  userCount: number
}

export interface RoleRequest {
  code?: string
  name?: string
  description?: string
  permissions?: string[]
  disabled?: boolean
}

export interface PermissionMetadata {
  code: string
  name: string
  description: string
  allows: string[]
  denies: string[]
}

export interface OpsMetadata {
  permissions: string[]
  permissionDetails: PermissionMetadata[]
  auditActions: string[]
  auditTargetTypes: string[]
  auditStatuses: string[]
  guardrails?: OpsGuardrails
}

export interface OpsGuardrails {
  uploadRateLimit: {
    enabled: boolean
    requests: number
    windowSeconds: number
  }
  ingestQueue: {
    maxPendingJobs: number
    maxRecoveryAttempts: number
  }
  audit: {
    alertWindowMinutes: number
    alertFailedThreshold: number
  }
  multipart: {
    maxFileSizeBytes: number
  }
}

export interface Citation {
  chunkId: string
  docId: string
  fileName: string
  snippet: string
  score: number
}

export interface RetrievalDiagnostics {
  retrievalMode?: string
  topK?: number
  hitCount?: number
  embeddingModel?: string
  embeddingDimension?: number
  fallbackReason?: string
  [key: string]: unknown
}

export interface RetrievalHit {
  chunkId: string
  docId: string
  fileName: string
  content: string
  score: number
  metadata?: Record<string, unknown> | null
}

export interface RetrieveRequest {
  query: string
  topK?: number
  useRerank?: boolean
  retrievalProfile?: RetrievalProfile
}

export interface RetrieveResponse {
  query: string
  retrievalMode?: string | null
  hits: RetrievalHit[]
  diagnostics?: RetrievalDiagnostics | null
}

export interface RagEvalCase {
  id: string
  kbId?: string
  caseKey?: string
  query: string
  minHits: number
  topK?: number
  expectedFileName?: string
  mustContainAny?: string[]
  createdAt?: string
  updatedAt?: string
}

export interface RagEvalCaseRequest {
  caseKey: string
  query: string
  minHits: number
  topK?: number
  expectedFileName?: string
  mustContainAny?: string[]
}

export interface RagEvalResult {
  id: string
  caseId?: string | null
  caseKey?: string
  query: string
  passed: boolean
  failureReasons: string[]
  hitPassed?: boolean
  citationEligible?: boolean
  citationPassed?: boolean
  hitCount: number
  expectedFileName: string | null
  matchedFileName: string | null
  matchedToken: string | null
  retrievalMode: string | null
  retrievalProfile?: RetrievalProfile | null
  fallbackReason: string | null
  embeddingModel: string | null
  embeddingDimension: number | null
  topK: number | null
}

export interface RagEvalRun {
  id: string
  kbId: string
  useRerank: boolean
  profileSet?: RetrievalProfile[]
  indexRevision?: number | null
  gateSummary?: Partial<Record<RetrievalProfile, RagEvalGateDecision>>
  passedCount: number
  totalCount: number
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  failureMessage: string | null
  createdAt: string
  results: RagEvalResult[]
}

export interface RagEvalRunRequest {
  useRerank?: boolean
  profiles?: RetrievalProfile[]
}

export interface DocumentIndexDetail {
  document: Document
  latestJob: IngestJob | null
  objectKey: string
  objectAvailable: boolean
  indexReady: boolean
  chunkCount: number
  chunks: Array<{
    id: string
    chunkIndex: number
    contentPreview: string
    tokenCount: number
    metadata: Record<string, unknown>
    milvusId: string | null
  }>
}

export interface KnowledgeBaseSnapshot {
  originalId: string
  tenantId: string
  name: string
  description: string | null
  chunkSize: number
  chunkOverlap: number
  topK: number
  embeddingModel: string
  embeddingDimension: number
  chunkStrategy: 'RECURSIVE' | 'SEMANTIC' | 'MARKDOWN'
  retrievalMode: 'VECTOR' | 'HYBRID'
  retrievalProfile?: RetrievalProfile
}

export interface DocumentSnapshot {
  originalId: string
  fileName: string
  objectKey: string
  mimeType: string
  fileSize: number
  status: Document['status']
  errorMessage: string | null
}

export interface ChunkSnapshot {
  originalId: string
  originalDocId: string
  chunkIndex: number
  content: string
  tokenCount: number
  metadata: Record<string, unknown>
  milvusId: string | null
}

export interface RagEvalCaseSnapshot {
  id: string
  kbId: string
  caseKey: string
  query: string
  minHits: number
  topK: number
  expectedFileName: string | null
  mustContainAny: string[]
  createdAt: string
  updatedAt: string
}

export interface KnowledgeBaseExport {
  schemaVersion: 1
  knowledgeBase: KnowledgeBaseSnapshot
  documents: DocumentSnapshot[]
  chunks: ChunkSnapshot[]
  evalCases: RagEvalCaseSnapshot[]
  exportedAt?: string
}

export interface KnowledgeBaseImport {
  schemaVersion?: 1
  knowledgeBase: {
    name: string
    description?: string | null
    chunkSize?: number
    chunkOverlap?: number
    topK?: number
    embeddingModel?: string
    embeddingDimension?: number
    chunkStrategy?: KnowledgeBaseSnapshot['chunkStrategy']
    retrievalMode?: KnowledgeBaseSnapshot['retrievalMode']
    retrievalProfile?: RetrievalProfile
  }
  evalCases?: RagEvalCaseRequest[]
}

export interface OpsNotification {
  configured: boolean
  delivered: boolean
  alertCount: number
  statusCode?: number | null
  message?: string | null
}

export interface StructuredChatError {
  error: string
  message: string
  stage?: string
  suggestion?: string
  requestId?: string
  timestamp?: string
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  citations?: Citation[]
  error?: StructuredChatError
  streaming?: boolean
}

export interface ChatSession {
  id: string
  kbId: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface PersistedChatMessage {
  id: string
  sessionId: string
  sequenceNumber: number
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string
  citations: Citation[]
  createdAt: string
}

export interface ChatSessionDetail {
  session: ChatSession
  messages: PersistedChatMessage[]
}

export interface ApiError {
  error: string
  message: string
  stage?: string
  suggestion?: string
  requestId?: string
  timestamp?: string
}
