import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  apiDelete,
  apiGet,
  apiPatch,
  apiPost,
  apiUpload,
  apiUploadMany,
  checkHealth,
  clearAuthToken,
  csrfHeaders,
  getAuthToken,
  login,
  setAuthToken,
} from './client'

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
    localStorage.clear()
  })

  it('returns JSON for successful GET and undefined for 204', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ ok: true }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiGet('/ok')).resolves.toEqual({ ok: true })
    await expect(apiGet('/empty')).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenCalledWith('/ok', { credentials: 'include' })
  })

  it('stores login session state and sends credentials plus CSRF for state-changing requests', async () => {
    const tokenResponse = {
      username: 'admin',
      tenantId: 'ops',
      role: 'ADMIN',
      expiresAt: '2026-07-06T08:00:00Z',
      csrfToken: 'csrf-token',
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(tokenResponse))
      .mockResolvedValueOnce(jsonResponse({ ok: true }))
      .mockResolvedValueOnce(jsonResponse({ id: 1 }))
      .mockResolvedValueOnce(jsonResponse({ patched: true }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(jsonResponse({ uploaded: true }))
      .mockResolvedValueOnce(jsonResponse([{ uploaded: true }]))
    vi.stubGlobal('fetch', fetchMock)

    await expect(login('admin', 'pw')).resolves.toEqual(tokenResponse)
    expect(getAuthToken()).toBe('cookie-session')
    await apiGet('/ok')
    await apiPost('/items', { name: 'A' })
    await apiPatch('/items/1', { name: 'B' })
    await apiDelete('/items/1')
    await apiUpload('/upload', new File(['abc'], 'a.txt'))
    await apiUploadMany('/upload/batch', [new File(['abc'], 'a.txt')])

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ username: 'admin', password: 'pw' }),
    })
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/ok', {
      credentials: 'include',
    })
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/items', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-Dupi-CSRF-Token': 'csrf-token' },
      body: JSON.stringify({ name: 'A' }),
    })
    expect(fetchMock).toHaveBeenNthCalledWith(4, '/items/1', {
      method: 'PATCH',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-Dupi-CSRF-Token': 'csrf-token' },
      body: JSON.stringify({ name: 'B' }),
    })
    expect(fetchMock).toHaveBeenNthCalledWith(5, '/items/1', {
      method: 'DELETE',
      credentials: 'include',
      headers: { 'X-Dupi-CSRF-Token': 'csrf-token' },
    })
    expect(fetchMock.mock.calls[5][1]).toMatchObject({
      credentials: 'include',
      headers: { 'X-Dupi-CSRF-Token': 'csrf-token' },
    })
    expect(fetchMock.mock.calls[6][1]).toMatchObject({
      credentials: 'include',
      headers: { 'X-Dupi-CSRF-Token': 'csrf-token' },
    })
  })

  it('supports manual CSRF updates and clearing', () => {
    setAuthToken('manual-token')
    expect(getAuthToken()).toBe('cookie-session')
    expect(csrfHeaders()).toEqual({ 'X-Dupi-CSRF-Token': 'manual-token' })

    clearAuthToken()

    expect(getAuthToken()).toBeNull()
  })

  it('rejects state-changing requests locally when CSRF state is missing', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiPost('/items', { name: 'A' })).rejects.toMatchObject({
      status: 401,
      message: '登录状态已过期，请重新登录',
    })
    await expect(apiPatch('/items/1', { name: 'B' })).rejects.toMatchObject({ status: 401 })
    await expect(apiDelete('/items/1')).rejects.toMatchObject({ status: 401 })
    await expect(apiUpload('/upload', new File(['abc'], 'a.txt'))).rejects.toMatchObject({ status: 401 })
    await expect(apiUploadMany('/upload/batch', [new File(['abc'], 'a.txt')])).rejects.toMatchObject({ status: 401 })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('throws parsed API errors and falls back to status text', async () => {
    setAuthToken('csrf-token')
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse({ error: 'bad', message: '输入错误' }, { status: 400, statusText: 'Bad Request' }))
        .mockResolvedValueOnce(new Response('oops', { status: 500, statusText: 'Server Error' })),
    )

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
    setAuthToken('csrf-token')
    const genericError = {
      ok: false,
      status: 418,
      statusText: undefined,
      json: vi.fn().mockRejectedValue(new Error('not json')),
    }
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse({ error: 'missing field' }, { status: 422, statusText: 'Unprocessable Entity' }))
        .mockResolvedValueOnce(jsonResponse({ error: 'bad upload' }, { status: 400, statusText: 'Bad Upload' }))
        .mockResolvedValueOnce(genericError),
    )

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
    setAuthToken('csrf-token')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ id: 1 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiPost('/items', { name: 'A' })).resolves.toEqual({ id: 1 })
    await expect(apiPost('/items')).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/items', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-Dupi-CSRF-Token': 'csrf-token' },
      body: JSON.stringify({ name: 'A' }),
    })
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/items', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-Dupi-CSRF-Token': 'csrf-token' },
      body: undefined,
    })
  })

  it('sends PATCH requests and parses JSON responses', async () => {
    setAuthToken('csrf-token')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ id: 'x' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiPatch('/resource', { title: 'New' })).resolves.toEqual({ id: 'x' })
    expect(fetchMock).toHaveBeenCalledWith('/resource', {
      method: 'PATCH',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-Dupi-CSRF-Token': 'csrf-token' },
      body: JSON.stringify({ title: 'New' }),
    })
  })

  it('uploads files through FormData', async () => {
    setAuthToken('csrf-token')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ uploaded: true }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await apiUpload('/upload', new File(['abc'], 'a.txt'))

    expect(result).toEqual({ uploaded: true })
    const [, init] = fetchMock.mock.calls[0]
    expect(init.method).toBe('POST')
    expect(init.body).toBeInstanceOf(FormData)
  })

  it('sends upload idempotency headers and abort signal and exposes Retry-After', async () => {
    setAuthToken('csrf-token')
    const controller = new AbortController()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ uploaded: true }))
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ error: 'upload_quota_exceeded', message: 'quota exhausted' }),
        {
          status: 429,
          headers: { 'Content-Type': 'application/json', 'Retry-After': '12' },
        },
      ))
    vi.stubGlobal('fetch', fetchMock)

    await apiUpload('/upload', new File(['abc'], 'a.txt'), {
      headers: { 'Idempotency-Key': 'upload-key' },
      signal: controller.signal,
    })

    expect(fetchMock.mock.calls[0][1]).toMatchObject({
      headers: {
        'X-Dupi-CSRF-Token': 'csrf-token',
        'Idempotency-Key': 'upload-key',
      },
      signal: controller.signal,
    })
    await expect(apiUpload('/upload', new File(['abc'], 'a.txt'))).rejects.toMatchObject({
      status: 429,
      retryAfter: '12',
    })
  })

  it('uploads multiple files through the batch FormData field', async () => {
    setAuthToken('csrf-token')
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
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse({ status: 'UP' }))
        .mockResolvedValueOnce(jsonResponse({ status: 'DOWN' }))
        .mockResolvedValueOnce(new Response('{}', { status: 503 }))
        .mockRejectedValueOnce(new Error('network')),
    )

    await expect(checkHealth()).resolves.toBe(true)
    await expect(checkHealth()).resolves.toBe(false)
    await expect(checkHealth()).resolves.toBe(false)
    await expect(checkHealth()).resolves.toBe(false)
  })
})
