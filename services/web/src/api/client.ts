import type { ApiError } from '@/types'

export class HttpError extends Error {
  status: number
  body: ApiError | null

  constructor(status: number, message: string, body: ApiError | null = null) {
    super(message)
    this.status = status
    this.body = body
  }
}

async function parseError(res: Response): Promise<HttpError> {
  let body: ApiError | null = null
  try {
    body = await res.json()
  } catch {
    /* 响应体不是 JSON 时忽略解析失败，继续使用 HTTP 状态文本作为错误信息。 */
  }
  const message = body?.message ?? res.statusText ?? 'Request failed'
  return new HttpError(res.status, message, body)
}

export async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(path)
  if (!res.ok) throw await parseError(res)
  if (res.status === 204) return undefined as T
  return res.json()
}

export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw await parseError(res)
  if (res.status === 204) return undefined as T
  return res.json()
}

export async function apiPatch<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw await parseError(res)
  if (res.status === 204) return undefined as T
  return res.json()
}

export async function apiDelete(path: string): Promise<void> {
  const res = await fetch(path, { method: 'DELETE' })
  if (!res.ok) throw await parseError(res)
}

export async function apiUpload<T>(path: string, file: File): Promise<T> {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch(path, { method: 'POST', body: form })
  if (!res.ok) throw await parseError(res)
  return res.json()
}

export async function checkHealth(): Promise<boolean> {
  try {
    const res = await fetch('/actuator/health')
    if (!res.ok) return false
    const data = await res.json()
    return data.status === 'UP'
  } catch {
    return false
  }
}
