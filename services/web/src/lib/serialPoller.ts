export interface SerialPoller {
  start: () => void
  stop: () => void
}

export function createSerialPoller(
  task: (signal: AbortSignal) => Promise<void>,
  intervalMs: number,
): SerialPoller {
  let active = false
  let timer: ReturnType<typeof setTimeout> | null = null
  let controller: AbortController | null = null

  const schedule = () => {
    if (!active) return
    timer = setTimeout(run, intervalMs)
  }

  const run = async () => {
    if (!active) return
    controller = new AbortController()
    try {
      await task(controller.signal)
    } catch (error) {
      if (!(error instanceof DOMException && error.name === 'AbortError')) {
        console.error('Serialized poll failed', error)
      }
    } finally {
      controller = null
      schedule()
    }
  }

  return {
    start() {
      if (active) return
      active = true
      schedule()
    },
    stop() {
      active = false
      if (timer) clearTimeout(timer)
      timer = null
      controller?.abort()
    },
  }
}
