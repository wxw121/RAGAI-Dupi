import { apiGet, apiPost } from './client'
import type { OperationJobResponse } from '@/types'

const operationPath = (jobId: string) => `/api/v1/operations/${jobId}`

export function getOperation(jobId: string, signal?: AbortSignal): Promise<OperationJobResponse> {
  const path = operationPath(jobId)
  return signal ? apiGet<OperationJobResponse>(path, { signal }) : apiGet<OperationJobResponse>(path)
}

export function retryOperation(jobId: string): Promise<OperationJobResponse> {
  return apiPost<OperationJobResponse>(`${operationPath(jobId)}/retry`)
}
