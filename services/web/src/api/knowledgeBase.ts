import { apiDelete, apiGet, apiPost } from './client'
import type { CreateKnowledgeBaseRequest, KnowledgeBase } from '@/types'

const BASE = '/api/v1/knowledge-bases'

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
