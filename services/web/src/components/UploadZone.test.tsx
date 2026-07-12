import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { UploadZone } from './UploadZone'
import type { OpsGuardrails } from '@/types'

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
})
