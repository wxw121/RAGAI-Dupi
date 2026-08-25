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
  const completedJobId = useRef<string | null>(null)
  const onCompletedRef = useRef(onCompleted)
  onCompletedRef.current = onCompleted

  useEffect(() => {
    setJob(initialJob)
    completedJobId.current = null
  }, [initialJob.id])

  useEffect(() => {
    if (job.status !== 'COMPLETED' || completedJobId.current === job.id) return
    completedJobId.current = job.id
    void onCompletedRef.current(job)
  }, [job])

  useEffect(() => {
    if (job.status === 'COMPLETED' || job.status === 'FAILED') return
    const poller = createSerialPoller(async (signal) => {
      setJob(await getOperation(job.id, signal))
    }, 3000)
    poller.start()
    return poller.stop
  }, [job.id, job.status])

  const retry = async () => {
    setRetrying(true)
    try {
      setJob(await retryOperation(job.id))
    } finally {
      setRetrying(false)
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
      {job.status === 'FAILED' && canRetry && (
        <Button className="mt-3" size="sm" variant="outline" disabled={retrying} onClick={() => void retry()}>
          重试
        </Button>
      )}
    </section>
  )
}
