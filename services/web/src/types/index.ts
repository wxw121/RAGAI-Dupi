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

export interface IngestJob {
  id: string
  kbId: string
  docId: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  stage: string | null
  retryCount: number
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface Citation {
  chunkId: string
  docId: string
  fileName: string
  snippet: string
  score: number
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  citations?: Citation[]
  streaming?: boolean
}

export interface ApiError {
  error: string
  message: string
  timestamp?: string
}
