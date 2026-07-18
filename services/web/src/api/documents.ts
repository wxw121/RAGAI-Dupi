import { apiDelete, apiGet, apiUpload, apiUploadMany } from './client'
import type {
  BatchDocumentUploadResponse,
  BatchDocumentUploadResult,
  Document,
  DocumentIndexDetail,
  IngestJob,
  UploadQuota,
} from '@/types'

export function listDocuments(kbId: string, signal?: AbortSignal): Promise<Document[]> {
  const path = `/api/v1/knowledge-bases/${kbId}/documents`
  return signal ? apiGet<Document[]>(path, { signal }) : apiGet<Document[]>(path)
}

export interface UploadDocumentOptions {
  idempotencyKey?: string
  signal?: AbortSignal
}

export function uploadDocument(
  kbId: string,
  file: File,
  options: UploadDocumentOptions = {},
): Promise<Document> {
  const path = `/api/v1/knowledge-bases/${kbId}/documents`
  const headers = options.idempotencyKey
    ? { 'Idempotency-Key': options.idempotencyKey }
    : undefined
  if (!headers && !options.signal) {
    return apiUpload<Document>(path, file)
  }
  return apiUpload<Document>(path, file, {
    headers,
    signal: options.signal,
  })
}

export function uploadDocuments(kbId: string, files: File[]): Promise<BatchDocumentUploadResponse> {
  return apiUploadMany<BatchDocumentUploadResponse>(
    `/api/v1/knowledge-bases/${kbId}/documents/batch`,
    files,
  )
}

export type GovernedUploadProgress = (
  current: number,
  total: number,
  file: File,
  status: 'uploading' | 'uploaded' | 'failed',
  errorMessage?: string,
) => void

const uploadFileIds = new WeakMap<File, string>()
let fallbackUploadFileId = 0

export interface GovernedUploadOptions {
  concurrency?: number
  batchId?: string
  signal?: AbortSignal
  onProgress?: GovernedUploadProgress
}

export async function uploadDocumentsGoverned(
  kbId: string,
  files: File[],
  options: GovernedUploadOptions = {},
): Promise<BatchDocumentUploadResponse> {
  if (files.length === 0) {
    return { total: 0, succeeded: 0, failed: 0, results: [] }
  }
  const batchId = options.batchId ?? createBatchId()
  const concurrency = Math.max(1, Math.min(options.concurrency ?? 3, files.length))
  const results = new Array<BatchDocumentUploadResult>(files.length)
  let nextIndex = 0
  let completed = 0

  const worker = async () => {
    while (nextIndex < files.length) {
      const index = nextIndex
      nextIndex += 1
      const file = files[index]
      options.onProgress?.(completed + 1, files.length, file, 'uploading')
      try {
        const document = await uploadDocument(kbId, file, {
          idempotencyKey: uploadIdempotencyKey(file, batchId),
          signal: options.signal,
        })
        results[index] = {
          fileName: file.name,
          success: true,
          errorMessage: null,
          document,
        }
        completed += 1
        options.onProgress?.(completed, files.length, file, 'uploaded')
      } catch (error) {
        const errorMessage = governedUploadError(error)
        results[index] = {
          fileName: file.name,
          success: false,
          errorMessage,
          document: null,
        }
        completed += 1
        options.onProgress?.(completed, files.length, file, 'failed', errorMessage)
      }
    }
  }

  await Promise.all(Array.from({ length: concurrency }, () => worker()))
  const succeeded = results.filter((result) => result.success).length
  return {
    total: results.length,
    succeeded,
    failed: results.length - succeeded,
    results,
  }
}

export function uploadIdempotencyKey(file: File, batchId: string): string {
  const safeBatch = batchId.replace(/[^a-zA-Z0-9-]/g, '').slice(0, 40) || 'batch'
  let fileId = uploadFileIds.get(file)
  if (!fileId) {
    fallbackUploadFileId += 1
    fileId = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${fallbackUploadFileId.toString(36)}`
    uploadFileIds.set(file, fileId)
  }
  return `web-${safeBatch}-${fileId}`
}

export function getUploadQuota(signal?: AbortSignal): Promise<UploadQuota> {
  return signal
    ? apiGet<UploadQuota>('/api/v1/upload-quota', { signal })
    : apiGet<UploadQuota>('/api/v1/upload-quota')
}

export function deleteDocument(kbId: string, docId: string): Promise<void> {
  return apiDelete(`/api/v1/knowledge-bases/${kbId}/documents/${docId}`)
}

export function getIngestJob(kbId: string, docId: string, signal?: AbortSignal): Promise<IngestJob> {
  const path = `/api/v1/knowledge-bases/${kbId}/documents/${docId}/ingest-job`
  return signal ? apiGet<IngestJob>(path, { signal }) : apiGet<IngestJob>(path)
}

export function getDocumentIndexDetail(kbId: string, docId: string): Promise<DocumentIndexDetail> {
  return apiGet<DocumentIndexDetail>(`/api/v1/knowledge-bases/${kbId}/documents/${docId}/index-detail`)
}

function createBatchId(): string {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function governedUploadError(error: unknown): string {
  if (error instanceof DOMException && error.name === 'AbortError') {
    return 'Upload cancelled'
  }
  const details = error as { status?: number; retryAfter?: string | null; message?: string }
  if (details.status === 409) {
    return 'Upload idempotency conflict'
  }
  if (details.status === 413) {
    return 'File exceeds the upload size limit'
  }
  if (details.status === 429) {
    const retry = details.retryAfter ? `; retry after ${details.retryAfter} seconds` : ''
    return `Upload quota exhausted${retry}`
  }
  return details.message ?? 'Upload failed'
}
