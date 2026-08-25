import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OperationProgress } from './OperationProgress'

const operationApi = vi.hoisted(() => ({
  getOperation: vi.fn(),
  retryOperation: vi.fn(),
}))

vi.mock('@/api/operations', () => operationApi)

const runningJob = {
  id: 'job-1',
  operationType: 'MARKDOWN_PACKAGE_IMPORT' as const,
  aggregateType: 'KNOWLEDGE_BASE',
  aggregateId: 'kb-1',
  status: 'RUNNING' as const,
  attemptCount: 1,
  nextAttemptAt: null,
  errorCode: null,
  errorMessage: null,
  createdAt: '2026-08-25T00:00:00Z',
  updatedAt: '2026-08-25T00:00:00Z',
  completedAt: null,
  steps: [
    { id: 'step-2', sequenceNumber: 2, stepKey: 'publish', stepType: 'ACTION', status: 'PENDING' as const, attemptCount: 0, lastError: null, startedAt: null, completedAt: null, nextAttemptAt: null },
    { id: 'step-1', sequenceNumber: 1, stepKey: 'store-assets', stepType: 'ACTION', status: 'RUNNING' as const, attemptCount: 1, lastError: null, startedAt: null, completedAt: null, nextAttemptAt: null },
  ],
}

describe('OperationProgress', () => {
  let root: Root | null = null
  let container: HTMLDivElement | null = null

  afterEach(() => {
    act(() => root?.unmount())
    root = null
    container?.remove()
    container = null
    vi.clearAllMocks()
    vi.useRealTimers()
  })

  it('polls serially, renders steps in sequence, and completes once', async () => {
    vi.useFakeTimers()
    const completedJob = { ...runningJob, status: 'COMPLETED' as const, completedAt: '2026-08-25T00:01:00Z' }
    operationApi.getOperation.mockResolvedValue(completedJob)
    const onCompleted = vi.fn()
    render(<OperationProgress initialJob={runningJob} onCompleted={onCompleted} />)

    const labels = Array.from(container!.querySelectorAll('[data-operation-step]')).map((node) => node.textContent)
    expect(labels[0]).toContain('store-assets')
    expect(labels[1]).toContain('publish')

    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    expect(operationApi.getOperation).toHaveBeenCalledTimes(1)
    expect(onCompleted).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(9000) })
    expect(operationApi.getOperation).toHaveBeenCalledTimes(1)
    expect(onCompleted).toHaveBeenCalledTimes(1)
  })

  it('shows sanitized failure and retries the same job id when authorized', async () => {
    const failedJob = {
      ...runningJob,
      status: 'FAILED' as const,
      errorMessage: 'Operation failed. Retry when the underlying service is available.',
      steps: [{ ...runningJob.steps[1], status: 'FAILED' as const, lastError: 'Operation step failed.' }],
    }
    operationApi.retryOperation.mockResolvedValue({ ...failedJob, status: 'RUNNING' as const })
    render(<OperationProgress initialJob={failedJob} onCompleted={vi.fn()} canRetry />)

    expect(container!.textContent).toContain('Operation step failed.')
    expect(container!.textContent).not.toContain('java.')
    const retry = Array.from(container!.querySelectorAll('button')).find((button) => button.textContent === '重试')
    await act(async () => { retry?.click(); await Promise.resolve() })

    expect(operationApi.retryOperation).toHaveBeenCalledWith('job-1')
    expect(container!.textContent).toContain('RUNNING')
  })

  it('stops the previous job poll on job id change and on unmount', async () => {
    vi.useFakeTimers()
    const signals: AbortSignal[] = []
    operationApi.getOperation.mockImplementation((_id: string, signal?: AbortSignal) => {
      if (signal) signals.push(signal)
      return new Promise(() => undefined)
    })
    render(<OperationProgress initialJob={runningJob} onCompleted={vi.fn()} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })

    act(() => root?.render(<OperationProgress initialJob={{ ...runningJob, id: 'job-2' }} onCompleted={vi.fn()} />))
    expect(signals[0].aborted).toBe(true)
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    act(() => root?.unmount())
    root = null
    expect(signals[1].aborted).toBe(true)
  })

  function render(node: React.ReactNode) {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    act(() => root?.render(node))
  }
})
