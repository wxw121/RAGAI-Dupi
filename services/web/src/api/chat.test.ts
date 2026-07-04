import { beforeEach, describe, expect, it, vi } from 'vitest'
import { cancelChat, streamChat } from './chat'

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
  })

  it('dispatches retrieval, token and done events from chunked SSE data', async () => {
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
      body: JSON.stringify({ query: '问题', stream: true, sessionId: 'sid' }),
    }))
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
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(streamResponse(['event: error\ndata: worker failed\n\n']))
      .mockRejectedValueOnce(new Error('network down')))
    const events: string[] = []

    await streamChat('kb', 'q', {
      onError: (msg) => events.push(msg),
      onDone: (sid) => events.push(`done:${sid}`),
    })
    await expect(streamChat('kb', 'q', {})).rejects.toThrow('network down')

    expect(events).toEqual(['worker failed', 'done:'])
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
  it('posts session cancellation request', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await cancelChat('kb', 'sid')

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/knowledge-bases/kb/chat/cancel', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId: 'sid' }),
    })
  })
})
