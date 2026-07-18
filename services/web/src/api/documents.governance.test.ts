import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getUploadQuota, uploadDocumentsGoverned, uploadIdempotencyKey } from './documents'

const apiClient = vi.hoisted(() => ({
  apiDelete: vi.fn(),
  apiGet: vi.fn(),
  apiUpload: vi.fn(),
  apiUploadMany: vi.fn(),
}))

vi.mock('./client', () => apiClient)

describe('document upload governance', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('fetches user-visible quota', async () => {
    apiClient.apiGet.mockResolvedValue({ retainedBytesUsed: 10, retainedBytesLimit: 100 })

    await expect(getUploadQuota()).resolves.toMatchObject({ retainedBytesUsed: 10 })

    expect(apiClient.apiGet).toHaveBeenCalledWith('/api/v1/upload-quota')
  })

  it('uploads each file with stable idempotency and bounded concurrency', async () => {
    let active = 0
    let maxActive = 0
    apiClient.apiUpload.mockImplementation(async (_path: string, file: File) => {
      active += 1
      maxActive = Math.max(maxActive, active)
      await new Promise((resolve) => setTimeout(resolve, 5))
      active -= 1
      return { id: file.name, fileName: file.name, currentJob: { id: `job-${file.name}` } }
    })
    const files = [
      new File(['a'], 'a.txt', { lastModified: 1 }),
      new File(['b'], 'b.txt', { lastModified: 2 }),
      new File(['c'], 'c.txt', { lastModified: 3 }),
      new File(['d'], 'd.txt', { lastModified: 4 }),
    ]

    const result = await uploadDocumentsGoverned('kb', files, {
      batchId: 'batch-1',
      concurrency: 2,
    })

    expect(result.succeeded).toBe(4)
    expect(maxActive).toBe(2)
    expect(apiClient.apiUpload).toHaveBeenCalledTimes(4)
    for (const [index, call] of apiClient.apiUpload.mock.calls.entries()) {
      expect(call[0]).toBe('/api/v1/knowledge-bases/kb/documents')
      expect(call[1]).toBe(files[index])
      expect(call[2].headers['Idempotency-Key']).toBe(uploadIdempotencyKey(files[index], 'batch-1'))
    }
  })

  it('gives distinct file objects distinct stable idempotency keys', () => {
    const first = new File(['a'], 'same.txt', { lastModified: 1 })
    const second = new File(['b'], 'same.txt', { lastModified: 1 })

    const firstKey = uploadIdempotencyKey(first, 'batch-same')

    expect(uploadIdempotencyKey(first, 'batch-same')).toBe(firstKey)
    expect(uploadIdempotencyKey(second, 'batch-same')).not.toBe(firstKey)
  })

  it('keeps other files running and renders quota Retry-After per file', async () => {
    apiClient.apiUpload
      .mockRejectedValueOnce(Object.assign(new Error('quota exhausted'), { status: 429, retryAfter: '15' }))
      .mockResolvedValueOnce({ id: 'b', fileName: 'b.txt' })

    const result = await uploadDocumentsGoverned(
      'kb',
      [new File(['a'], 'a.txt'), new File(['b'], 'b.txt')],
      { batchId: 'batch-2', concurrency: 1 },
    )

    expect(result.succeeded).toBe(1)
    expect(result.failed).toBe(1)
    expect(result.results[0].errorMessage).toContain('15')
    expect(result.results[1].success).toBe(true)
  })
})
