import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RagEvalPanel } from './RagEvalPanel'

const api = vi.hoisted(() => ({
  listRagEvalCases: vi.fn(),
  createRagEvalCase: vi.fn(),
  updateRagEvalCase: vi.fn(),
  deleteRagEvalCase: vi.fn(),
  listRagEvalRuns: vi.fn(),
  runRagEval: vi.fn(),
}))

vi.mock('@/api/knowledgeBase', () => api)

vi.mock('@/components/Toast', () => ({
  useToast: () => ({ showError: vi.fn(), showSuccess: vi.fn() }),
}))

describe('RagEvalPanel', () => {
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

  it('manages persisted cases and renders run history', async () => {
    api.listRagEvalCases.mockResolvedValue([
      {
        id: 'case-id-1',
        kbId: 'kb-1',
        caseKey: 'case-1',
        query: 'What formats are supported?',
        minHits: 1,
        topK: 5,
        expectedFileName: 'sample.md',
        mustContainAny: ['PDF'],
      },
    ])
    api.listRagEvalRuns.mockResolvedValue([
      {
        id: 'run-1',
        kbId: 'kb-1',
        useRerank: true,
        passedCount: 1,
        totalCount: 1,
        status: 'COMPLETED',
        failureMessage: null,
        createdAt: '2026-07-12T00:00:00Z',
        results: [
          {
            id: 'result-1',
            caseId: 'case-id-1',
            caseKey: 'case-1',
            query: 'What formats are supported?',
            passed: true,
            failureReasons: [],
            hitCount: 2,
            expectedFileName: 'sample.md',
            matchedFileName: 'sample.md',
            matchedToken: 'PDF',
            retrievalMode: 'HYBRID',
            fallbackReason: null,
            embeddingModel: 'bge-m3',
            embeddingDimension: 1024,
            topK: 5,
          },
        ],
      },
    ])
    api.createRagEvalCase.mockResolvedValue({ id: 'case-id-2', caseKey: 'new-case' })
    api.deleteRagEvalCase.mockResolvedValue(undefined)
    api.runRagEval.mockResolvedValue({
      id: 'run-2',
      kbId: 'kb-1',
      useRerank: true,
      passedCount: 1,
      totalCount: 1,
      createdAt: '2026-07-12T00:01:00Z',
      results: [],
    })

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<RagEvalPanel kbId="kb-1" />)
      await Promise.resolve()
    })

    expect(api.listRagEvalCases).toHaveBeenCalledWith('kb-1')
    expect(api.listRagEvalRuns).toHaveBeenCalledWith('kb-1')
    expect(container.textContent).toContain('case-1')
    expect(container.textContent).toContain('sample.md')
    expect(container.textContent).toContain('1/1')
    expect(container.textContent).toContain('已完成')
    expect(container.textContent).toContain('HYBRID')

    await act(async () => {
      setInput('caseKey', 'new-case')
      setInput('query', 'New eval query')
      setInput('minHits', '2')
      setInput('topK', '3')
      setInput('expectedFileName', 'doc.md')
      setInput('mustContainAny', 'alpha, beta')
    })

    await act(async () => {
      button('保存用例')?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
    })

    expect(api.createRagEvalCase).toHaveBeenCalledWith('kb-1', {
      caseKey: 'new-case',
      query: 'New eval query',
      minHits: 2,
      topK: 3,
      expectedFileName: 'doc.md',
      mustContainAny: ['alpha', 'beta'],
    })

    await act(async () => {
      checkbox('useRerank').click()
      button('运行评估')?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
    })
    expect(api.runRagEval).toHaveBeenCalledWith('kb-1', { useRerank: true })

    await act(async () => {
      container
        ?.querySelector<HTMLButtonElement>('[aria-label="Delete eval case case-1"]')
        ?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
    })
    expect(api.deleteRagEvalCase).toHaveBeenCalledWith('kb-1', 'case-id-1')
  })

  function input(name: string): HTMLInputElement {
    const field = container?.querySelector<HTMLInputElement>(`input[name="${name}"]`)
    if (!field) throw new Error(`Missing input ${name}`)
    return field
  }

  function checkbox(name: string): HTMLInputElement {
    const field = input(name)
    if (field.type !== 'checkbox') throw new Error(`${name} is not a checkbox`)
    return field
  }

  function setInput(name: string, value: string) {
    const field = input(name)
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
    setter?.call(field, value)
    field.dispatchEvent(new Event('input', { bubbles: true }))
  }

  function button(label: string): HTMLButtonElement | undefined {
    return Array.from(container?.querySelectorAll('button') ?? []).find((item) =>
      item.textContent?.includes(label),
    )
  }
})
