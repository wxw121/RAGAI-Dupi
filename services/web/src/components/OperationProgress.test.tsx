import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OperationProgress } from './OperationProgress'
import type { OperationJobResponse } from '@/types'

const operationApi = vi.hoisted(() => ({
  getOperation: vi.fn(),
  retryOperation: vi.fn(),
}))

vi.mock('@/api/operations', () => operationApi)

const runningJob: OperationJobResponse = {
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

  it('ignores a superseded job response that settles after the job id changes', async () => {
    vi.useFakeTimers()
    let resolveOld: ((job: typeof runningJob) => void) | undefined
    operationApi.getOperation.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
    const onCompleted = vi.fn()
    render(<OperationProgress initialJob={runningJob} onCompleted={onCompleted} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })

    const nextJob = {
      ...runningJob,
      id: 'job-2',
      steps: [{ ...runningJob.steps[0], id: 'job-2-step', stepKey: 'new-job-step' }],
    }
    act(() => root?.render(<OperationProgress initialJob={nextJob} onCompleted={onCompleted} />))
    await act(async () => { resolveOld?.({ ...runningJob, status: 'COMPLETED', completedAt: '2026-08-25T00:01:00Z' }); await Promise.resolve() })

    expect(container?.textContent).toContain('new-job-step')
    expect(container?.textContent).not.toContain('store-assets')
    expect(onCompleted).not.toHaveBeenCalled()
  })

  it('does not reconcile a completed old job when its response and new props commit together', async () => {
    vi.useFakeTimers()
    let resolveOld: ((job: OperationJobResponse) => void) | undefined
    operationApi.getOperation.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
    const oldCompletion = vi.fn()
    const newCompletion = vi.fn()
    render(<OperationProgress initialJob={runningJob} onCompleted={oldCompletion} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })

    const nextJob = { ...runningJob, id: 'job-2' }
    await act(async () => {
      resolveOld?.({ ...runningJob, status: 'COMPLETED', completedAt: '2026-08-25T00:01:00Z' })
      await Promise.resolve()
      root?.render(<OperationProgress initialJob={nextJob} onCompleted={newCompletion} />)
    })

    expect(oldCompletion).not.toHaveBeenCalled()
    expect(newCompletion).not.toHaveBeenCalled()
  })

  it('retains completed evidence and offers refresh retry when completion reconciliation fails', async () => {
    const completedJob = { ...runningJob, status: 'COMPLETED' as const, completedAt: '2026-08-25T00:01:00Z' }
    const onCompleted = vi.fn().mockRejectedValueOnce(new Error('refresh failed')).mockResolvedValueOnce(undefined)
    render(<OperationProgress initialJob={completedJob} onCompleted={onCompleted} />)
    await act(async () => { await Promise.resolve(); await Promise.resolve() })

    expect(container?.textContent).toContain('刷新失败')
    const retryRefresh = Array.from(container!.querySelectorAll('button')).find((button) => button.textContent === '重试刷新')
    await act(async () => { retryRefresh?.click(); await Promise.resolve(); await Promise.resolve() })
    expect(onCompleted).toHaveBeenCalledTimes(2)
  })

  function render(node: React.ReactNode) {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    act(() => root?.render(node))
  }
})
