import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { UploadZone } from './UploadZone'
import type { OpsGuardrails, UploadQuota } from '@/types'

describe('UploadZone', () => {
  let root: Root | null = null
  let container: HTMLDivElement | null = null

  afterEach(() => {
    if (root) {
      act(() => {
        root?.unmount()
      })
    }
    container?.remove()
    root = null
    container = null
    vi.clearAllMocks()
  })

  it('always renders supported formats and optionally renders upload guardrails', () => {
    const guardrails: OpsGuardrails = {
      uploadRateLimit: {
        enabled: true,
        requests: 20,
        windowSeconds: 60,
      },
      ingestQueue: {
        maxPendingJobs: 200,
        maxRecoveryAttempts: 3,
      },
      audit: {
        alertWindowMinutes: 30,
        alertFailedThreshold: 10,
      },
      multipart: {
        maxFileSizeBytes: 10 * 1024 * 1024,
      },
    }
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    act(() => {
      root?.render(<UploadZone onUpload={vi.fn()} guardrails={guardrails} />)
    })

    expect(container.textContent).toContain('PDF')
    expect(container.textContent).toContain('DOCX')
    expect(container.textContent).toContain('TXT')
    expect(container.textContent).toContain('MD')
    expect(container.textContent).toContain('Excel')
    expect(container.textContent).toContain('20/60s')
    expect(container.textContent).toContain('queue 200')
    expect(container.textContent).toContain('10.0 MB')
  })

  it('renders retained quota and can abort the active transport upload', async () => {
    const quota: UploadQuota = {
      tenantId: 'tenant-a',
      userId: 'user-a',
      retainedBytesUsed: 25,
      retainedBytesLimit: 100,
      retainedDocumentsUsed: 1,
      retainedDocumentsLimit: 10,
      windowBytesUsed: 20,
      windowBytesLimit: 200,
      windowSeconds: 60,
      retryAfter: null,
    }
    let signal: AbortSignal | undefined
    const onUpload = vi.fn((_files, _onProgress, uploadSignal?: AbortSignal) => {
      signal = uploadSignal
      return new Promise<void>((resolve) => uploadSignal?.addEventListener('abort', () => resolve()))
    })
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<UploadZone onUpload={onUpload} quota={quota} />)
    })
    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    Object.defineProperty(input, 'files', {
      configurable: true,
      value: [new File(['abc'], 'a.txt')],
    })
    await act(async () => {
      input.dispatchEvent(new Event('change', { bubbles: true }))
    })

    expect(container.textContent).toContain('25 B')
    const cancel = container.querySelector('[aria-label="Cancel upload"]') as HTMLButtonElement
    expect(cancel).not.toBeNull()
    await act(async () => {
      cancel.click()
    })
    expect(signal?.aborted).toBe(true)
  })

  it('retries a failed file with the original upload batch id', async () => {
    const onUpload = vi.fn(async (
      files: File[],
      onProgress?: (current: number, total: number, file: File, status?: 'uploading' | 'uploaded' | 'failed', errorMessage?: string) => void,
      _signal?: AbortSignal,
      _batchId?: string,
    ) => {
      onProgress?.(1, 1, files[0], 'failed', 'network failed')
    })
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<UploadZone onUpload={onUpload} />)
    })
    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    Object.defineProperty(input, 'files', {
      configurable: true,
      value: [new File(['abc'], 'a.txt', { lastModified: 1 })],
    })
    await act(async () => {
      input.dispatchEvent(new Event('change', { bubbles: true }))
    })

    const retry = container.querySelector('[aria-label="Retry a.txt"]') as HTMLButtonElement
    expect(retry).not.toBeNull()
    await act(async () => {
      retry.click()
    })

    expect(onUpload).toHaveBeenCalledTimes(2)
    expect(onUpload.mock.calls[0][3]).toEqual(expect.any(String))
    expect(onUpload.mock.calls[1][3]).toBe(onUpload.mock.calls[0][3])
  })

  it('updates only the targeted item when selected files share a name', async () => {
    const first = new File(['a'], 'same.txt', { lastModified: 1 })
    const second = new File(['b'], 'same.txt', { lastModified: 1 })
    const onUpload = vi.fn(async (
      _files: File[],
      onProgress?: unknown,
    ) => {
      const report = onProgress as (
        current: number,
        total: number,
        file: File,
        status: 'uploading' | 'uploaded' | 'failed',
        errorMessage?: string,
      ) => void
      report(1, 2, first, 'failed', 'first failed')
    })
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<UploadZone onUpload={onUpload} />)
    })
    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    Object.defineProperty(input, 'files', {
      configurable: true,
      value: [first, second],
    })
    await act(async () => {
      input.dispatchEvent(new Event('change', { bubbles: true }))
    })

    const rows = Array.from(container.querySelectorAll('li')).map((row) => row.textContent)
    expect(rows.filter((row) => row?.includes('first failed'))).toHaveLength(1)
    expect(rows.filter((row) => row?.includes('queued'))).toHaveLength(1)
  })
})
