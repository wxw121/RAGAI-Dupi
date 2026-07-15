import { beforeEach, describe, expect, it, vi } from 'vitest'
import { cancelChat, streamChat } from './chat'
import { setAuthToken } from './client'

function streamResponse(chunks: string[]) {
  const encoder = new TextEncoder()
  return new Response(
    new ReadableStream({
      start(controller) {
        chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)))
        controller.close()
      },
    }),
    { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
  )
}

describe('streamChat', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('dispatches retrieval, token and done events from chunked SSE data', async () => {
    setAuthToken('csrf-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([
      'event: retrieval\ndata: [{"chunkId":"c1","docId":"d1","fileName":"a.md","snippet":"s","score":0.9}]\n\n',
      'event: token\ndata: 你\n\n',
      'event: token\ndata: 好\n\nevent: done\ndata: {"sessionId":"sid"}\n\n',
    ])))
    const seen = { tokens: '', session: '', citations: 0 }

    await streamChat('kb', '问题', {
      onRetrieval: (items) => { seen.citations = items.length },
      onToken: (token) => { seen.tokens += token },
      onDone: (sessionId) => { seen.session = sessionId },
    }, undefined, 'sid')

    expect(seen).toEqual({ tokens: '你好', session: 'sid', citations: 1 })
    expect(fetch).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/chat', expect.objectContaining({
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        'X-Dupi-CSRF-Token': 'csrf-token',
      },
      credentials: 'include',
      body: JSON.stringify({ query: '问题', stream: true, sessionId: 'sid' }),
    }))
  })

  it('dispatches retrieval diagnostics from object payloads', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([
      'event: retrieval\ndata: {"citations":[{"chunkId":"c1","docId":"d1","fileName":"a.md","snippet":"s","score":0.9}],"diagnostics":{"hitCount":1,"retrievalMode":"vector"}}\n\n',
      'event: done\ndata: {"sessionId":"sid"}\n\n',
    ])))
    const seen = { citations: 0, hitCount: 0, mode: '' }

    await streamChat('kb', 'q', {
      onRetrieval: (items, diagnostics) => {
        seen.citations = items.length
        seen.hitCount = diagnostics?.hitCount ?? 0
        seen.mode = diagnostics?.retrievalMode ?? ''
      },
    })

    expect(seen).toEqual({ citations: 1, hitCount: 1, mode: 'vector' })
  })

  it('omits session id for new conversations', async () => {
    const onDone = vi.fn()
    const fetchMock = vi.fn().mockResolvedValue(streamResponse([
      'event: done\ndata: {"sessionId":"new-session"}\n\n',
    ]))
    vi.stubGlobal('fetch', fetchMock)

    await streamChat('kb', 'new question', { onDone })

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/chat', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ query: 'new question', stream: true }),
    }))
    expect(onDone).toHaveBeenCalledWith('new-session')
  })

  it('preserves multiline data payloads and finalizes when done is missing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([
      'event: token\ndata: 第一行\ndata: 第二行',
    ])))
    const seen: string[] = []

    await streamChat('kb', 'q', {
      onToken: (token) => seen.push(token),
      onDone: (sid) => seen.push(`done:${sid}`),
    })

    expect(seen).toEqual(['第一行\n第二行', 'done:'])
  })

  it('reports API errors with status-specific messages', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ message: 'bad key' }),
      { status: 401, statusText: 'Unauthorized', headers: { 'Content-Type': 'application/json' } },
    )))
    const errors: string[] = []

    await streamChat('kb', 'q', { onError: (msg) => errors.push(msg) })

    expect(errors).toEqual(['401 Unauthorized: bad key'])
  })

  it('preserves structured details from non-streaming HTTP errors', async () => {
    const structured = {
      error: 'chat_pipeline_error',
      message: 'provider down',
      stage: 'llm',
      suggestion: 'Check CHAT_API_KEY',
      requestId: 'trace-http-1',
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify(structured),
      { status: 500, statusText: 'Server Error', headers: { 'Content-Type': 'application/json' } },
    )))
    const onError = vi.fn()

    await streamChat('kb', 'q', { onError })

    expect(onError).toHaveBeenCalledWith('provider down', structured)
  })

  it('falls back to status text for non-json errors and rethrows non-abort stream failures', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response('plain error', {
        status: 500,
        statusText: 'Server Error',
      }))
      .mockResolvedValueOnce(new Response(new ReadableStream({
        pull() { throw new Error('reader down') },
      }), { status: 200 })))
    const errors: string[] = []

    await streamChat('kb', 'q', { onError: (msg) => errors.push(msg) })
    await expect(streamChat('kb', 'q', {})).rejects.toThrow('reader down')

    expect(errors).toEqual(['Server Error'])
  })

  it('dispatches error events and rethrows non-abort fetch failures', async () => {
    const structured = {
      error: 'chat_pipeline_error',
      message: 'llm down',
      stage: 'llm',
      suggestion: 'Check CHAT_API_KEY',
      requestId: 'req-1',
    }
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(streamResponse([`event: error\ndata: ${JSON.stringify(structured)}\n\n`]))
      .mockRejectedValueOnce(new Error('network down')))
    const events: string[] = []
    const structuredErrors: string[] = []

    await streamChat('kb', 'q', {
      onError: (msg, error) => {
        events.push(msg)
        if (error) structuredErrors.push(`${error.stage}:${error.requestId}:${error.suggestion}`)
      },
      onDone: (sid) => events.push(`done:${sid}`),
    })
    await expect(streamChat('kb', 'q', {})).rejects.toThrow('network down')

    expect(events).toEqual(['llm down', 'done:'])
    expect(structuredErrors).toEqual(['llm:req-1:Check CHAT_API_KEY'])
  })

  it('normalizes blank API and SSE error messages', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ message: '' }), {
        status: 500,
        statusText: '',
        headers: { 'Content-Type': 'application/json' },
      }))
      .mockResolvedValueOnce(streamResponse(['event: error\ndata: \n\n'])))
    const errors: string[] = []

    await streamChat('kb', 'q', { onError: (msg) => errors.push(msg) })
    await streamChat('kb', 'q', { onError: (msg) => errors.push(msg) })

    expect(errors).toEqual(['HTTP 500', '问答请求失败，请稍后重试'])
  })

  it('falls back to an empty session id when done payload is malformed', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([
      'event: done\ndata: not-json\n\n',
    ])))
    const events: string[] = []

    await streamChat('kb', 'q', {
      onDone: (sid) => events.push(`done:${sid}`),
    })

    expect(events).toEqual(['done:'])
  })

  it('ignores empty SSE blocks and uses status text when JSON error has no message', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ error: 'missing message' }),
        { status: 422, statusText: 'Unprocessable Entity', headers: { 'Content-Type': 'application/json' } },
      ))
      .mockResolvedValueOnce(streamResponse([
        '\n\n',
        'event: ping\n\n',
        'event: token\ndata:  padded\n\n',
      ])))
    const seen: string[] = []

    await streamChat('kb', 'q', { onError: (msg) => seen.push(`error:${msg}`) })
    await streamChat('kb', 'q', {
      onToken: (token) => seen.push(`token:${token}`),
      onDone: (sid) => seen.push(`done:${sid}`),
    })

    expect(seen).toEqual(['error:Unprocessable Entity', 'token: padded', 'done:'])
  })

  it('reports missing body, ignores malformed retrieval, and handles aborts', async () => {
    const abortError = new DOMException('aborted', 'AbortError')
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 200 }))
      .mockResolvedValueOnce(streamResponse(['event: retrieval\ndata: not-json\n\nevent: done\ndata: {}\n\n']))
      .mockRejectedValueOnce(abortError)
      .mockResolvedValueOnce(new Response(new ReadableStream({ pull() { throw abortError } }), { status: 200 })))
    const errors: string[] = []
    let retrievalCalled = false
    let done = false
    let aborts = 0

    await streamChat('kb', 'q', { onError: (msg) => errors.push(msg) })
    await streamChat('kb', 'q', {
      onRetrieval: () => { retrievalCalled = true },
      onDone: () => { done = true },
    })
    await streamChat('kb', 'q', { onAbort: () => { aborts += 1 } })
    await streamChat('kb', 'q', { onAbort: () => { aborts += 1 } })

    expect(errors).toEqual(['No response body'])
    expect(retrievalCalled).toBe(false)
    expect(done).toBe(true)
    expect(aborts).toBe(2)
  })
})

describe('cancelChat', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('posts session cancellation request', async () => {
    setAuthToken('cancel-token')
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await cancelChat('kb', 'sid')

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/chat/cancel', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-Dupi-CSRF-Token': 'cancel-token' },
      body: JSON.stringify({ sessionId: 'sid' }),
    })
  })
})
