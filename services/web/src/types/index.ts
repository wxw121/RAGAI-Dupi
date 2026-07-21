export type RetrievalIndexMode = 'CLASSIC' | 'PARENT_CHILD' | 'QA_ASSISTED' | 'COMBINED'

export type RagEvalProfileGateStatus =
  | 'PASSED'
  | 'BLOCKED'
  | 'INSUFFICIENT_DATA'
  | 'STALE'
  | 'INDEX_NOT_READY'
  | 'NOT_EVALUATED'

export interface RagEvalProfileMetrics {
  profile: RetrievalIndexMode
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
  candidate: RetrievalIndexMode
  baseline?: RetrievalIndexMode
  status: RagEvalProfileGateStatus
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
  retrievalProfile?: RetrievalIndexMode
  indexSchemaVersion?: number
  profileIndexReady?: boolean
  indexRevision?: number
  retrievalProfileGateDecisions?: Partial<Record<RetrievalIndexMode, RagEvalGateDecision>>
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
  retrievalProfile?: RetrievalIndexMode
}

export interface Document {
  id: string
  kbId: string
  fileName: string
  mimeType: string
  fileSize: number
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  errorMessage: string | null
  currentJob?: IngestJob | null
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
  executionId?: string | null
  kbId: string
  docId: string
  documentFileName?: string | null
  documentStatus?: Document['status'] | null
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'DEAD_LETTER' | 'CANCEL_REQUESTED' | 'CANCELLED'
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
    | 'KNOWLEDGE_BASE'
    | 'DOCUMENT'
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
}

export interface RetrieveResponse {
  query: string
  retrievalMode?: string | null
  hits: RetrievalHit[]
  diagnostics?: RetrievalDiagnostics | null
}

export type RagEvalCaseCategory = 'REAL_QUERY' | 'HARD_NEGATIVE' | 'MULTI_DOCUMENT' | 'AMBIGUOUS'

export interface RagEvalCase {
  id: string
  kbId?: string
  caseKey?: string
  query: string
  minHits: number
  topK?: number
  category?: RagEvalCaseCategory
  expectedFileName?: string
  expectedFileNames?: string[]
  mustContainAny?: string[]
  createdAt?: string
  updatedAt?: string
}

export interface RagEvalCaseRequest {
  caseKey: string
  query: string
  minHits: number
  topK?: number
  category?: RagEvalCaseCategory
  expectedFileName?: string
  expectedFileNames?: string[]
  mustContainAny?: string[]
}

export interface RagEvalResult {
  id: string
  caseId?: string | null
  caseKey?: string
  query: string
  passed: boolean
  failureReasons: string[]
  failureCategories?: string[]
  hitPassed?: boolean
  citationEligible?: boolean
  citationPassed?: boolean
  hitCount: number
  category?: RagEvalCaseCategory
  expectedFileName: string | null
  expectedFileNames?: string[]
  matchedFileName: string | null
  matchedFileNames?: string[]
  matchedToken: string | null
  retrievalMode: string | null
  retrievalProfile?: RetrievalIndexMode | null
  fallbackReason: string | null
  embeddingModel: string | null
  embeddingDimension: number | null
  topK: number | null
}

export interface RagEvalMetrics {
  passRate?: number
  eligibleExpectedFileHitRate?: number
  eligibleKeywordHitRate?: number
  averageHitCount?: number
  fallbackCount?: number
  latencyP50Ms?: number
  latencyP95Ms?: number
  failureCategoryCounts?: Record<string, number>
  categorySummaries?: Record<string, RagEvalSummaryMetrics>
  profileSummaries?: Record<string, RagEvalSummaryMetrics>
  profileComparisons?: Record<string, RagEvalProfileComparison>
  releaseGate?: RagEvalReleaseGate
  releaseReadiness?: RagEvalReleaseReadiness
  realQueryFeedback?: RagEvalRealQueryFeedback
  experimentMatrix?: RagEvalExperimentMatrix
  answerQuality?: RagEvalAnswerQuality
  onlineObservability?: RagEvalOnlineObservability
  dataIndexGovernance?: RagEvalDataIndexGovernance
  [key: string]: unknown
}

export interface RagEvalSummaryMetrics {
  total?: number
  passed?: number
  passRate?: number
  hitPassRate?: number
  hitRate?: number
  citationPassRate?: number
  citationRate?: number
  avgHitCount?: number
  latencyP50Ms?: number
  latencyP95Ms?: number
  fallbackCount?: number
  failureCategoryCounts?: Record<string, number>
}

export interface RagEvalProfileComparison {
  baseline?: string
  candidate?: string
  passRateDelta?: number
  hitRateDelta?: number
  citationRateDelta?: number
  latencyP95MsDelta?: number
  fallbackCountDelta?: number
}

export interface RagEvalReleaseGate {
  status?: string
  passRate?: number
  passed?: number
  total?: number
  failureCategoryCounts?: Record<string, number>
  categoryBlockers?: string[]
  profileGateBlockers?: string[]
}

export interface RagEvalReleaseReadiness {
  version?: string
  status?: string
  readinessScore?: number
  blockerCount?: number
  requiredEvidence?: string[]
}

export interface RagEvalRealQueryFeedback {
  version?: string
  source?: string
  candidateCount?: number
  candidates?: Array<Record<string, unknown>>
}

export interface RagEvalExperimentMatrix {
  version?: string
  topKValues?: number[]
  profiles?: string[]
  retrievalModes?: string[]
  evaluationCount?: number
}

export interface RagEvalAnswerQuality {
  version?: string
  citationEligibleCount?: number
  citationPassedCount?: number
  groundedPassRate?: number
  hallucinationRiskCount?: number
}

export interface RagEvalOnlineObservability {
  version?: string
  fallbackCount?: number
  fallbackRate?: number
  noAnswerCorrectnessRate?: number
  latencyP95Ms?: number
}

export interface RagEvalDataIndexGovernance {
  version?: string
  expectedSourceCount?: number
  matchedExpectedSourceCount?: number
  expectedSourceCoverageRate?: number
  missingSourceCount?: number
}

export interface RagEvalRunProfileSnapshot {
  experimentLabel?: string
  topKOverride?: number
  useRerank?: boolean
  profiles?: string[]
  [key: string]: unknown
}

export interface RagEvalRun {
  id: string
  kbId: string
  useRerank: boolean
  profileSet?: RetrievalIndexMode[]
  indexRevision?: number | null
  gateSummary?: Partial<Record<RetrievalIndexMode, RagEvalGateDecision>>
  passedCount: number
  totalCount: number
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  failureMessage: string | null
  createdAt: string
  results: RagEvalResult[]
  gateStatus?: 'PASS' | 'WARN' | 'BLOCKED' | 'UNBASELINED'
  metrics?: RagEvalMetrics
  profileSnapshot?: RagEvalRunProfileSnapshot
  baselineRunId?: string | null
}

export interface UploadQuota {
  tenantId: string
  userId: string
  retainedBytesUsed: number
  retainedBytesLimit: number
  retainedDocumentsUsed: number
  retainedDocumentsLimit: number
  windowBytesUsed: number
  windowBytesLimit: number
  windowSeconds: number
  retryAfter: string | null
}

export interface RagEvalRunRequest {
  useRerank?: boolean
  profileId?: string
  profiles?: RetrievalIndexMode[]
  topKOverride?: number
  experimentLabel?: string
}

export interface RagQualityPolicy {
  id: string
  kbId: string
  minimumPassRate: number
  maximumPassRateDrop: number
  maximumNewFailures: number
  blockWhenUnbaselined: boolean
  baselineRunId: string | null
}

export type RagQualityPolicyRequest = Pick<RagQualityPolicy,
  'minimumPassRate' | 'maximumPassRateDrop' | 'maximumNewFailures' | 'blockWhenUnbaselined'>

export interface RetrievalProfile {
  id: string
  kbId: string
  name: string
  version: number
  vectorCandidateCount: number
  sparseCandidateCount: number
  rrfConstant: number
  sparseIndexParams: Record<string, unknown>
  sparseSearchParams: Record<string, unknown>
  rerankEnabled: boolean
  rerankCandidateLimit: number
  finalTopK: number
  active: boolean
  createdAt: string
}

export type RetrievalProfileRequest = Omit<RetrievalProfile, 'id' | 'kbId' | 'version' | 'active' | 'createdAt'>

export type SparseMigrationState = 'PREPARING' | 'BACKFILLING' | 'DUAL_WRITING' |
  'SHADOW_VALIDATING' | 'CUTOVER' | 'COMPLETED' | 'FAILED'

export interface SparseMigration {
  id: string
  kbId: string
  profileId: string
  state: SparseMigrationState
  sourceChunkCount: number
  indexedChunkCount: number
  expectedDimension: number | null
  actualDimension: number | null
  baselineP95Ms: number | null
  candidateP95Ms: number | null
  baselineFallbackRate: number | null
  candidateFallbackRate: number | null
  legacyBm25Enabled: boolean
  errorMessage: string | null
  createdAt: string
  updatedAt: string
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
  category?: RagEvalCaseCategory
  expectedFileName: string | null
  expectedFileNames?: string[]
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

export type RecoveryArchiveStatus = 'PREPARING' | 'CAPTURING' | 'VERIFYING' | 'COMPLETED' | 'FAILED'
export type RecoveryRestoreStatus = 'VALIDATING' | 'RESTORING_OBJECTS' | 'RESTORING_RECORDS' |
  'RESTORING_VECTORS' | 'VERIFYING' | 'COMPLETED' | 'FAILED'
export interface RecoveryArchive {
  id: string; sourceKnowledgeBaseId: string; status: RecoveryArchiveStatus; schemaVersion: number
  itemCount: number; totalBytes: number; manifestChecksum: string | null; errorCode: string | null
  errorMessage: string | null; createdBy: string; createdAt: string; updatedAt: string
}
export interface RecoveryRestore {
  id: string; archiveId: string; targetKnowledgeBaseId: string | null; status: RecoveryRestoreStatus
  completedItems: number; totalItems: number; errorCode: string | null; errorMessage: string | null
  createdBy: string; createdAt: string; updatedAt: string
}
