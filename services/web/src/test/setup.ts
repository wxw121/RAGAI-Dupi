import { ReadableStream } from 'node:stream/web'

declare global {
  // eslint-disable-next-line no-var
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true

type ResponseBody = string | null | ReadableStream<Uint8Array>

interface TestResponseInit {
  status?: number
  statusText?: string
  headers?: Record<string, string> | Headers
}

class TestResponse {
  readonly status: number
  readonly statusText: string
  readonly headers: Headers
  readonly ok: boolean
  readonly body: ReadableStream<Uint8Array> | null
  private readonly rawBody: ResponseBody

  constructor(body: ResponseBody = null, init: TestResponseInit = {}) {
    this.status = init.status ?? 200
    this.statusText = init.statusText ?? ''
    this.headers = init.headers instanceof Headers ? init.headers : new Headers(init.headers)
    this.ok = this.status >= 200 && this.status < 300
    this.rawBody = body
    this.body = body instanceof ReadableStream ? body : null
  }

  async text(): Promise<string> {
    if (typeof this.rawBody === 'string') return this.rawBody
    if (!this.rawBody) return ''

    const reader = this.rawBody.getReader()
    const decoder = new TextDecoder()
    let result = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      result += decoder.decode(value, { stream: true })
    }

    return result + decoder.decode()
  }

  async json(): Promise<unknown> {
    return JSON.parse(await this.text())
  }
}

// Node 16 + jsdom 不默认提供 Fetch API 的 Response；测试里只需要最小响应模型。
if (!globalThis.Response) {
  globalThis.Response = TestResponse as unknown as typeof Response
}

if (!globalThis.ReadableStream) {
  globalThis.ReadableStream = ReadableStream as unknown as typeof globalThis.ReadableStream
}
