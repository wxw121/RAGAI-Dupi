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
  createdAt: string
  updatedAt: string
}

export interface CreateKnowledgeBaseRequest {
  name: string
  description?: string
  chunkSize?: number
  chunkOverlap?: number
  topK?: number
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
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'DEAD_LETTER'
  stage: string | null
  retryCount: number
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface VectorCleanupTask {
  id: string
  targetType: 'KNOWLEDGE_BASE' | 'DOCUMENT'
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
  windowStart: string
  windowEnd: string
}

export interface Account {
  username: string
  tenantId: string
  role: string
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
  permissions?: string[]
  knowledgeBaseIds?: string[]
  tokenVersion?: string
  disabled?: boolean
}

export interface PasswordHashResponse {
  passwordHash: string
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
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  citations?: Citation[]
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
  timestamp?: string
}
