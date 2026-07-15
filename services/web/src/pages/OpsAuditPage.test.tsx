import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OpsAuditPage } from './OpsAuditPage'

const api = vi.hoisted(() => ({
  exportAuditLogs: vi.fn(),
  listAuditAlerts: vi.fn(),
  listAuditLogs: vi.fn(),
  listOpsMetadata: vi.fn(),
}))

const toast = vi.hoisted(() => ({
  showError: vi.fn(),
  showSuccess: vi.fn(),
}))

vi.mock('@/api/knowledgeBase', () => api)

vi.mock('@/components/AppLayout', () => ({
  AppLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

vi.mock('@/components/Toast', () => ({
  useToast: () => toast,
}))

describe('OpsAuditPage', () => {
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

  it('renders guardrails and aggregated active alerts from ops metadata', async () => {
    api.listAuditLogs.mockResolvedValue([])
    api.listAuditAlerts.mockResolvedValue([
      {
        code: 'INGEST_FAILURES_OPEN',
        severity: 'WARN',
        message: 'Open ingest failures',
        count: 2,
        threshold: 0,
        windowStart: '2026-07-12T00:00:00Z',
        windowEnd: '2026-07-12T00:30:00Z',
      },
    ])
    api.listOpsMetadata.mockResolvedValue({
      permissions: [],
      permissionDetails: [],
      auditActions: ['ACCOUNT_CREATE'],
      auditTargetTypes: ['ACCOUNT'],
      auditStatuses: ['SUCCESS', 'FAILED'],
      guardrails: {
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
      },
    })
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<OpsAuditPage />)
      await Promise.resolve()
    })

    await act(async () => {
      await Promise.resolve()
    })

    expect(container.textContent).toContain('Guardrails')
    expect(container.textContent).toContain('20/60s')
    expect(container.textContent).toContain('queue 200')
    expect(container.textContent).toContain('10.0 MB')
    expect(container.textContent).toContain('INGEST_FAILURES_OPEN')
  })
})
