import { afterEach, describe, expect, it, vi } from 'vitest'
import { createSerialPoller } from './serialPoller'

describe('createSerialPoller', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('waits for one poll to settle before scheduling the next and aborts on stop', async () => {
    vi.useFakeTimers()
    let finish: (() => void) | undefined
    const signals: AbortSignal[] = []
    const task = vi.fn((signal: AbortSignal) => {
      signals.push(signal)
      return new Promise<void>((resolve) => {
        finish = resolve
      })
    })
    const poller = createSerialPoller(task, 3000)

    poller.start()
    await vi.advanceTimersByTimeAsync(3000)
    expect(task).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(9000)
    expect(task).toHaveBeenCalledTimes(1)

    finish?.()
    await Promise.resolve()
    await vi.advanceTimersByTimeAsync(3000)
    expect(task).toHaveBeenCalledTimes(2)

    poller.stop()
    expect(signals[1].aborted).toBe(true)
  })
})
