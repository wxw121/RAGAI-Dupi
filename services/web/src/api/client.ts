import type { ApiError } from '@/types'

const CSRF_TOKEN_KEY = 'dupi.auth.csrf'
const COOKIE_SESSION_MARKER = 'cookie-session'
const CSRF_HEADER = 'X-Dupi-CSRF-Token'

export interface LoginResponse {
  csrfToken: string
  username: string
  tenantId: string
  role: string
  expiresAt: string
}

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

export function getAuthToken(): string | null {
  return localStorage.getItem(CSRF_TOKEN_KEY) ? COOKIE_SESSION_MARKER : null
}

export function setAuthToken(token: string): void {
  localStorage.setItem(CSRF_TOKEN_KEY, token)
}

export function clearAuthToken(): void {
  localStorage.removeItem(CSRF_TOKEN_KEY)
}

export function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const csrfToken = localStorage.getItem(CSRF_TOKEN_KEY)
  if (!csrfToken) return extra
  return { ...extra, [CSRF_HEADER]: csrfToken }
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  const res = await fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ username, password }),
  })
  if (!res.ok) throw await parseError(res)
  const body = (await res.json()) as LoginResponse
  setAuthToken(body.csrfToken)
  return body
}

export async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(path, { credentials: 'include' })
  if (!res.ok) throw await parseError(res)
  if (res.status === 204) return undefined as T
  return res.json()
}

export async function apiGetText(path: string): Promise<string> {
  const res = await fetch(path, { credentials: 'include' })
  if (!res.ok) throw await parseError(res)
  return res.text()
}

export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method: 'POST',
    credentials: 'include',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw await parseError(res)
  if (res.status === 204) return undefined as T
  return res.json()
}

export async function apiPatch<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method: 'PATCH',
    credentials: 'include',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw await parseError(res)
  if (res.status === 204) return undefined as T
  return res.json()
}

export async function apiDelete(path: string): Promise<void> {
  const res = await fetch(path, { method: 'DELETE', credentials: 'include', headers: authHeaders() })
  if (!res.ok) throw await parseError(res)
}

export async function apiUpload<T>(path: string, file: File): Promise<T> {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch(path, { method: 'POST', credentials: 'include', headers: authHeaders(), body: form })
  if (!res.ok) throw await parseError(res)
  return res.json()
}

export async function apiUploadMany<T>(path: string, files: File[]): Promise<T> {
  const form = new FormData()
  files.forEach((file) => form.append('files', file))
  const res = await fetch(path, { method: 'POST', credentials: 'include', headers: authHeaders(), body: form })
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
