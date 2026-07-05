import type { ChatSession, ChatSessionDetail } from '@/types'
import { apiDelete, apiGet, apiPatch, apiPost } from './client'

function basePath(kbId: string) {
  return `/api/v1/knowledge-bases/${kbId}/chat-sessions`
}

export function listChatSessions(kbId: string): Promise<ChatSession[]> {
  return apiGet<ChatSession[]>(basePath(kbId))
}

export function getChatSession(kbId: string, sessionId: string): Promise<ChatSessionDetail> {
  return apiGet<ChatSessionDetail>(`${basePath(kbId)}/${sessionId}`)
}

export function createChatSession(kbId: string, title?: string): Promise<ChatSession> {
  return apiPost<ChatSession>(basePath(kbId), title !== undefined ? { title } : {})
}

export function renameChatSession(kbId: string, sessionId: string, title: string): Promise<ChatSession> {
  return apiPatch<ChatSession>(`${basePath(kbId)}/${sessionId}`, { title })
}

export function deleteChatSession(kbId: string, sessionId: string): Promise<void> {
  return apiDelete(`${basePath(kbId)}/${sessionId}`)
}

export function batchDeleteChatSessions(kbId: string, sessionIds: string[]): Promise<void> {
  return apiPost<void>(`${basePath(kbId)}/batch-delete`, { sessionIds })
}
