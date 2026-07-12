import { apiDelete, apiGet, apiUpload, apiUploadMany } from './client'
import type { BatchDocumentUploadResponse, Document, IngestJob } from '@/types'

export function listDocuments(kbId: string): Promise<Document[]> {
  return apiGet<Document[]>(`/api/v1/knowledge-bases/${kbId}/documents`)
}

export function uploadDocument(kbId: string, file: File): Promise<Document> {
  return apiUpload<Document>(`/api/v1/knowledge-bases/${kbId}/documents`, file)
}

export function uploadDocuments(kbId: string, files: File[]): Promise<BatchDocumentUploadResponse> {
  return apiUploadMany<BatchDocumentUploadResponse>(
    `/api/v1/knowledge-bases/${kbId}/documents/batch`,
    files,
  )
}

export function deleteDocument(kbId: string, docId: string): Promise<void> {
  return apiDelete(`/api/v1/knowledge-bases/${kbId}/documents/${docId}`)
}

export function getIngestJob(kbId: string, docId: string): Promise<IngestJob> {
  return apiGet<IngestJob>(`/api/v1/knowledge-bases/${kbId}/documents/${docId}/ingest-job`)
}
