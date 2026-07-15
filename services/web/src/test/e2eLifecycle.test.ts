import { describe, expect, it, vi } from 'vitest'
import { finalizeE2eRun, type E2eRunResources } from './e2eLifecycle'

const resources: E2eRunResources = {
  runId: '42',
  gateUsername: 'e2e_gate_42',
  accountUsername: 'e2e_account_42',
  kbName: 'e2e-browser-42',
  kbId: 'kb-42',
}

describe('E2E lifecycle', () => {
  it('attaches evidence and skips cleanup when the product flow fails', async () => {
    const cleanup = vi.fn()
    const attach = vi.fn()

    await finalizeE2eRun(false, resources, cleanup, attach)

    expect(cleanup).not.toHaveBeenCalled()
    expect(attach).toHaveBeenCalledWith('e2e-evidence', expect.stringContaining(resources.kbName))
  })

  it('waits for asynchronous failure evidence attachment', async () => {
    let resolveAttachment: (() => void) | undefined
    const attachment = new Promise<void>((resolve) => {
      resolveAttachment = resolve
    })
    const attach = vi.fn(() => attachment)
    const finalization = finalizeE2eRun(false, resources, vi.fn(), attach)
    let settled = false
    void finalization.then(() => {
      settled = true
    })

    await Promise.resolve()
    expect(settled).toBe(false)
    resolveAttachment?.()
    await expect(finalization).resolves.toBeUndefined()
  })

  it('runs cleanup once when every product assertion completed', async () => {
    const cleanup = vi.fn().mockResolvedValue(undefined)
    const attach = vi.fn()

    await finalizeE2eRun(true, resources, cleanup, attach)

    expect(cleanup).toHaveBeenCalledTimes(1)
    expect(attach).not.toHaveBeenCalled()
  })

  it('attaches remaining resources and fails when successful-run cleanup fails', async () => {
    const cleanup = vi.fn().mockRejectedValue(new Error('delete rejected'))
    const attach = vi.fn()

    await expect(finalizeE2eRun(true, resources, cleanup, attach)).rejects.toThrow('delete rejected')

    expect(attach).toHaveBeenCalledWith('e2e-evidence', expect.stringContaining('delete rejected'))
    expect(attach).toHaveBeenCalledWith('e2e-evidence', expect.stringContaining(resources.kbName))
  })
})
