import type { Citation } from '@/types'



export interface ChatStreamCallbacks {
  onRetrieval?: (citations: Citation[]) => void
  onToken?: (token: string) => void
  onDone?: (sessionId: string) => void
  onError?: (message: string) => void
  onAbort?: () => void
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
  const data = dataLines.join('\n')
  return { event, data }
}


// SSE 事件分发器：把后端流式响应解耦成 retrieval/token/done/error 四类回调。
// 这里使用小型状态对象记录 done 是否到达，便于网络提前结束时做兜底收口。
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

    callbacks.onError?.(data)

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
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
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

    let message = res.statusText

    try {

      const err = await res.json()

      message = err.message ?? message

    } catch {

      /* 忽略单个事件解析失败，保持后续 SSE 流继续消费。 */

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

  const remaining = buffer
  if (remaining) {
    const parsed = parseSseBlock(remaining)
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

    headers: { 'Content-Type': 'application/json' },

    body: JSON.stringify({ sessionId }),

  })

}
