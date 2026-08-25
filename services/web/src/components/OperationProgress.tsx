import { useEffect, useRef, useState } from 'react'
import { getOperation, retryOperation } from '@/api/operations'
import type { OperationJobResponse, OperationStepStatus } from '@/types'
import { createSerialPoller } from '@/lib/serialPoller'
import { Button } from '@/components/ui/button'

export interface OperationProgressProps {
  initialJob: OperationJobResponse
  onCompleted: (job: OperationJobResponse) => void | Promise<void>
  canRetry?: boolean
}

const stepStatusLabel: Record<OperationStepStatus, string> = {
  PENDING: '等待中',
  RUNNING: '进行中',
  RETRY_WAIT: '等待重试',
  COMPLETED: '已完成',
  FAILED: '失败',
  COMPENSATED: '已补偿',
}

export function OperationProgress({ initialJob, onCompleted, canRetry = true }: OperationProgressProps) {
  const [job, setJob] = useState(initialJob)
  const [retrying, setRetrying] = useState(false)
  const [completionRefreshing, setCompletionRefreshing] = useState(false)
  const [completionError, setCompletionError] = useState<string | null>(null)
  const completedJobId = useRef<string | null>(null)
  const completionInFlightJobId = useRef<string | null>(null)
  const onCompletedRef = useRef(onCompleted)
  const currentJobIdRef = useRef(initialJob.id)
  const requestEpochRef = useRef(0)
  const activeRef = useRef(true)
  if (currentJobIdRef.current !== initialJob.id) {
    currentJobIdRef.current = initialJob.id
    requestEpochRef.current += 1
  }
  onCompletedRef.current = onCompleted

  useEffect(() => {
    setJob(initialJob)
    completedJobId.current = null
    completionInFlightJobId.current = null
    setCompletionError(null)
    setCompletionRefreshing(false)
    setRetrying(false)
  }, [initialJob.id])

  useEffect(() => {
    activeRef.current = true
    return () => {
      activeRef.current = false
      requestEpochRef.current += 1
    }
  }, [])

  const reconcileCompletion = async (
    completedJob: OperationJobResponse,
    completionCallback = onCompletedRef.current,
  ) => {
    const epoch = requestEpochRef.current
    if (!activeRef.current || currentJobIdRef.current !== completedJob.id) return
    if (completionInFlightJobId.current === completedJob.id || completedJobId.current === completedJob.id) return
    completionInFlightJobId.current = completedJob.id
    setCompletionRefreshing(true)
    setCompletionError(null)
    try {
      if (!activeRef.current || requestEpochRef.current !== epoch || currentJobIdRef.current !== completedJob.id) return
      await completionCallback(completedJob)
      if (!activeRef.current || requestEpochRef.current !== epoch || currentJobIdRef.current !== completedJob.id) return
      completedJobId.current = completedJob.id
    } catch {
      if (!activeRef.current || requestEpochRef.current !== epoch || currentJobIdRef.current !== completedJob.id) return
      setCompletionError('操作已完成，但刷新失败。请重试刷新。')
    } finally {
      if (activeRef.current && requestEpochRef.current === epoch && currentJobIdRef.current === completedJob.id) {
        completionInFlightJobId.current = null
        setCompletionRefreshing(false)
      }
    }
  }

  useEffect(() => {
    if (job.status !== 'COMPLETED' || completedJobId.current === job.id) return
    const completionCallback = onCompletedRef.current
    void reconcileCompletion(job, completionCallback)
  }, [job])

  useEffect(() => {
    if (job.status === 'COMPLETED' || job.status === 'FAILED') return
    const jobId = job.id
    const epoch = requestEpochRef.current
    let active = true
    const poller = createSerialPoller(async (signal) => {
      const nextJob = await getOperation(jobId, signal)
      if (!active || requestEpochRef.current !== epoch || currentJobIdRef.current !== jobId) return
      setJob(nextJob)
    }, 3000)
    poller.start()
    return () => {
      active = false
      poller.stop()
    }
  }, [job.id, job.status])

  const retry = async () => {
    const jobId = job.id
    const epoch = requestEpochRef.current
    setRetrying(true)
    try {
      const nextJob = await retryOperation(jobId)
      if (requestEpochRef.current === epoch && currentJobIdRef.current === jobId) setJob(nextJob)
    } finally {
      if (requestEpochRef.current === epoch && currentJobIdRef.current === jobId) setRetrying(false)
    }
  }

  const steps = [...job.steps].sort((left, right) => left.sequenceNumber - right.sequenceNumber)

  return (
    <section aria-label="操作进度" className="rounded-lg border border-border p-3 text-sm">
      <div className="flex items-center justify-between gap-3">
        <span className="font-medium">操作状态</span>
        <span className="font-mono text-xs">{job.status}</span>
      </div>
      <ol className="mt-3 space-y-2">
        {steps.map((step) => (
          <li key={step.id} data-operation-step className="flex items-start justify-between gap-3">
            <div>
              <p className="font-mono text-xs">{step.stepKey}</p>
              {step.lastError && <p className="mt-1 text-xs text-destructive">{step.lastError}</p>}
            </div>
            <span className="shrink-0 text-xs text-muted-foreground">{stepStatusLabel[step.status]}</span>
          </li>
        ))}
      </ol>
      {job.errorMessage && <p className="mt-3 text-xs text-destructive">{job.errorMessage}</p>}
      {completionError && (
        <div className="mt-3">
          <p className="text-xs text-destructive">{completionError}</p>
          <Button className="mt-2" size="sm" variant="outline" disabled={completionRefreshing} onClick={() => void reconcileCompletion(job)}>
            重试刷新
          </Button>
        </div>
      )}
      {job.status === 'FAILED' && canRetry && (
        <Button className="mt-3" size="sm" variant="outline" disabled={retrying} onClick={() => void retry()}>
          重试
        </Button>
      )}
    </section>
  )
}
