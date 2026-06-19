import type { Citation } from '@/types'

export interface ChatStreamCallbacks {
  onRetrieval?: (citations: Citation[]) => void
  onToken?: (token: string) => void
  onDone?: (sessionId: string) => void
  onError?: (message: string) => void
}

function parseSseBlock(block: string): { event: string; data: string } | null {
  let event = 'message'
  let data = ''
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data += line.slice(5).trim()
  }
  if (!data && !event) return null
  return { event, data }
}

function dispatchSseEvent(
  event: string,
  data: string,
  callbacks: ChatStreamCallbacks,
  state: { doneReceived: boolean },
): void {
  if (event === 'retrieval') {
    try {
      callbacks.onRetrieval?.(JSON.parse(data) as Citation[])
    } catch {
      /* ignore */
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
    callbacks.onError?.(data)
  }
}

export async function streamChat(
  kbId: string,
  query: string,
  callbacks: ChatStreamCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(`/api/v1/knowledge-bases/${kbId}/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify({ query, stream: true }),
    signal,
  })

  if (!res.ok) {
    let message = res.statusText
    try {
      const err = await res.json()
      message = err.message ?? message
    } catch {
      /* ignore */
    }
    if (res.status === 401) {
      message = `401 Unauthorized: ${message}`
    }
    callbacks.onError?.(message)
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

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    const parts = buffer.split('\n\n')
    buffer = parts.pop() ?? ''

    for (const part of parts) {
      const parsed = parseSseBlock(part.trim())
      if (!parsed) continue
      dispatchSseEvent(parsed.event, parsed.data, callbacks, state)
    }
  }

  // Flush any remaining buffered event
  const remaining = buffer.trim()
  if (remaining) {
    const parsed = parseSseBlock(remaining)
    if (parsed) {
      dispatchSseEvent(parsed.event, parsed.data, callbacks, state)
    }
  }

  // Stream ended without explicit done event — still finalize streaming state
  if (!state.doneReceived) {
    callbacks.onDone?.('')
  }
}

export async function cancelChat(kbId: string, sessionId: string): Promise<void> {
  await fetch(`/api/v1/knowledge-bases/${kbId}/chat/cancel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId }),
  })
}
