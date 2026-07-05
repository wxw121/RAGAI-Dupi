import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiDelete, apiGet, apiPatch, apiPost, apiUpload, apiUploadMany, checkHealth } from './client'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: init.status ?? 200,
    statusText: init.statusText,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('api client', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('returns JSON for successful GET and undefined for 204', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ ok: true }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiGet('/ok')).resolves.toEqual({ ok: true })
    await expect(apiGet('/empty')).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenCalledWith('/ok')
  })

  it('throws parsed API errors and falls back to status text', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(jsonResponse(
        { error: 'bad', message: '输入错误' },
        { status: 400, statusText: 'Bad Request' },
      ))
      .mockResolvedValueOnce(new Response('oops', {
        status: 500,
        statusText: 'Server Error',
      })))

    await expect(apiGet('/bad')).rejects.toMatchObject({
      status: 400,
      message: '输入错误',
      body: { error: 'bad', message: '输入错误' },
    })
    await expect(apiDelete('/bad')).rejects.toMatchObject({
      status: 500,
      message: 'Server Error',
    })
  })

  it('covers POST, upload and generic fallback error branches', async () => {
    const genericError = {
      ok: false,
      status: 418,
      statusText: undefined,
      json: vi.fn().mockRejectedValue(new Error('not json')),
    }
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(jsonResponse(
        { error: 'missing field' },
        { status: 422, statusText: 'Unprocessable Entity' },
      ))
      .mockResolvedValueOnce(jsonResponse(
        { error: 'bad upload' },
        { status: 400, statusText: 'Bad Upload' },
      ))
      .mockResolvedValueOnce(genericError))

    await expect(apiPost('/bad', { name: '' })).rejects.toMatchObject({
      status: 422,
      message: 'Unprocessable Entity',
    })
    await expect(apiUpload('/bad-file', new File(['abc'], 'a.txt'))).rejects.toMatchObject({
      status: 400,
      message: 'Bad Upload',
    })
    await expect(apiDelete('/generic')).rejects.toMatchObject({
      status: 418,
      message: 'Request failed',
    })
  })

  it('posts JSON bodies and supports 204 responses', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ id: 1 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiPost('/items', { name: 'A' })).resolves.toEqual({ id: 1 })
    await expect(apiPost('/items')).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/items', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'A' }),
    })
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/items', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: undefined,
    })
  })

  it('sends PATCH requests and parses JSON responses', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ id: 'x' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiPatch('/resource', { title: 'New' })).resolves.toEqual({ id: 'x' })
    expect(fetchMock).toHaveBeenCalledWith('/resource', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: 'New' }),
    })
  })

  it('uploads files through FormData', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ uploaded: true }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await apiUpload('/upload', new File(['abc'], 'a.txt'))

    expect(result).toEqual({ uploaded: true })
    const [, init] = fetchMock.mock.calls[0]
    expect(init.method).toBe('POST')
    expect(init.body).toBeInstanceOf(FormData)
  })

  it('uploads multiple files through the batch FormData field', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([{ uploaded: true }]))
    vi.stubGlobal('fetch', fetchMock)
    const files = [new File(['abc'], 'a.txt'), new File(['def'], 'b.txt')]

    const result = await apiUploadMany('/upload/batch', files)

    expect(result).toEqual([{ uploaded: true }])
    const [, init] = fetchMock.mock.calls[0]
    expect(init.method).toBe('POST')
    expect(init.body).toBeInstanceOf(FormData)
    expect((init.body as FormData).getAll('files')).toHaveLength(2)
  })

  it('checks health status and treats network failures as unhealthy', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(jsonResponse({ status: 'UP' }))
      .mockResolvedValueOnce(jsonResponse({ status: 'DOWN' }))
      .mockResolvedValueOnce(new Response('{}', { status: 503 }))
      .mockRejectedValueOnce(new Error('network')))

    await expect(checkHealth()).resolves.toBe(true)
    await expect(checkHealth()).resolves.toBe(false)
    await expect(checkHealth()).resolves.toBe(false)
    await expect(checkHealth()).resolves.toBe(false)
  })
})
