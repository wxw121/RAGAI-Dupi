import { authHeaders } from './client'
import type { Citation, RetrievalDiagnostics, StructuredChatError } from '@/types'

export interface ChatStreamCallbacks {
  onRetrieval?: (citations: Citation[], diagnostics?: RetrievalDiagnostics) => void
  onToken?: (token: string) => void
  onDone?: (sessionId: string) => void
  onError?: (message: string, error?: StructuredChatError) => void
  onAbort?: () => void
}

function fallbackErrorMessage(message: string | undefined | null, status?: number): string {
  const trimmed = message?.trim()
  if (trimmed) return trimmed
  return status ? `HTTP ${status}` : '问答请求失败，请稍后重试'
}

function parseStructuredError(data: string): { message: string; error?: StructuredChatError } {
  try {
    const parsed = JSON.parse(data) as StructuredChatError
    if (parsed && typeof parsed === 'object' && typeof parsed.message === 'string') {
      return { message: fallbackErrorMessage(parsed.message), error: parsed }
    }
  } catch {
    /* Plain SSE error strings remain supported. */
  }
  return { message: fallbackErrorMessage(data) }
}

function parseSseBlock(block: string): { event: string; data: string } | null {
  let event = 'message'
  const dataLines: string[] = []

  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) {
      let value = line.slice(5)
      if (value.startsWith(' ')) value = value.slice(1)
      dataLines.push(value)
    }
  }

  if (dataLines.length === 0 && event === 'message') return null
  return { event, data: dataLines.join('\n') }
}

// SSE 事件分发器：把后端流式响应解耦成 retrieval/token/done/error 四类回调。
// retrieval 同时兼容旧版数组结构与新版 { citations, diagnostics } 对象结构。
function dispatchSseEvent(
  event: string,
  data: string,
  callbacks: ChatStreamCallbacks,
  state: { doneReceived: boolean },
): void {
  if (event === 'retrieval') {
    try {
      const payload = JSON.parse(data) as
        | Citation[]
        | { citations?: Citation[]; diagnostics?: RetrievalDiagnostics }

      if (Array.isArray(payload)) {
        callbacks.onRetrieval?.(payload)
      } else {
        callbacks.onRetrieval?.(payload.citations ?? [], payload.diagnostics)
      }
    } catch {
      /* 忽略单个事件解析失败，保持后续 SSE 流继续消费。 */
    }
  } else if (event === 'token') {
    callbacks.onToken?.(data)
  } else if (event === 'done') {
    state.doneReceived = true
    try {
      const payload = JSON.parse(data) as { sessionId?: string }
      callbacks.onDone?.(payload.sessionId ?? '')
    } catch {
      callbacks.onDone?.('')
    }
  } else if (event === 'error') {
    const parsed = parseStructuredError(data)
    callbacks.onError?.(parsed.message, parsed.error)
  }
}

export async function streamChat(
  kbId: string,
  query: string,
  callbacks: ChatStreamCallbacks,
  signal?: AbortSignal,
  sessionId?: string,
): Promise<void> {
  let res: Response

  try {
    res = await fetch(`/api/v1/knowledge-bases/${kbId}/chat`, {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders({
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      }),
      body: JSON.stringify(sessionId ? { query, stream: true, sessionId } : { query, stream: true }),
      signal,
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      callbacks.onAbort?.()
      return
    }
    throw e
  }

  if (!res.ok) {
    let message = fallbackErrorMessage(res.statusText, res.status)
    let structuredError: StructuredChatError | undefined

    try {
      const err = await res.json() as Partial<StructuredChatError>
      message = fallbackErrorMessage(err.message ?? message, res.status)
      if (typeof err.error === 'string' && typeof err.message === 'string') {
        structuredError = err as StructuredChatError
      }
    } catch {
      /* 非 JSON 错误响应时保留 HTTP 状态文本。 */
    }

    if (res.status === 401) {
      message = `401 Unauthorized: ${message}`
    }

    callbacks.onError?.(message, structuredError)
    return
  }

  const reader = res.body?.getReader()
  if (!reader) {
    callbacks.onError?.('No response body')
    return
  }

  const decoder = new TextDecoder()
  let buffer = ''
  const state = { doneReceived: false }

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      const parts = buffer.split('\n\n')
      buffer = parts.pop() ?? ''

      for (const part of parts) {
        const parsed = parseSseBlock(part)
        if (!parsed) continue
        dispatchSseEvent(parsed.event, parsed.data, callbacks, state)
      }
    }
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      callbacks.onAbort?.()
      return
    }
    throw e
  }

  // 刷新最后一个未以空行结尾的 SSE 事件，避免流结束时丢掉尾包。
  if (buffer) {
    const parsed = parseSseBlock(buffer)
    if (parsed) {
      dispatchSseEvent(parsed.event, parsed.data, callbacks, state)
    }
  }

  // 流没有显式 done 事件时仍然结束前端状态，避免 UI 一直停留在加载中。
  if (!state.doneReceived) {
    callbacks.onDone?.('')
  }
}

export async function cancelChat(kbId: string, sessionId: string): Promise<void> {
  await fetch(`/api/v1/knowledge-bases/${kbId}/chat/cancel`, {
    method: 'POST',
    credentials: 'include',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ sessionId }),
  })
}
