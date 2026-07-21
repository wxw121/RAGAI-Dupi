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
  getRagQualityPolicy: vi.fn(),
  updateRagQualityPolicy: vi.fn(),
  promoteRagEvalBaseline: vi.fn(),
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
    api.getRagQualityPolicy.mockResolvedValue({
      id: 'policy-1', kbId: 'kb-1', minimumPassRate: 80, maximumPassRateDrop: 5,
      maximumNewFailures: 0, blockWhenUnbaselined: true, baselineRunId: null,
    })
    api.listRagEvalCases.mockResolvedValue([
      {
        id: 'case-id-1',
        kbId: 'kb-1',
        caseKey: 'case-1',
        query: 'What formats are supported?',
        minHits: 1,
        topK: 5,
        category: 'MULTI_DOCUMENT',
        expectedFileName: 'sample.md',
        expectedFileNames: ['operations.md'],
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
        metrics: {
          failureCategoryCounts: {
            UNEXPECTED_EVIDENCE: 2,
            MISSING_EXPECTED_FILE: 1,
          },
          categorySummaries: {
            MULTI_DOCUMENT: {
              totalCases: 1,
              passedCount: 1,
              passRate: 1,
              hitPassRate: 1,
              citationPassRate: 1,
              latencyP95Ms: 12,
              failureCategoryCounts: { MISSING_EXPECTED_FILE: 1 },
            },
          },
          profileComparisons: {
            PARENT_CHILD: {
              baseline: 'CLASSIC',
              candidate: 'PARENT_CHILD',
              passRateDelta: -0.1,
              hitPassRateDelta: -0.2,
              citationPassRateDelta: -0.3,
              latencyP95DeltaMs: 5,
            },
          },
          releaseGate: {
            status: 'BLOCKED',
            blockers: ['MISSING_EXPECTED_FILE'],
            passRate: 0.5,
          },
          releaseReadiness: {
            version: 'V1.9',
            status: 'BLOCKED',
            readinessScore: 48,
            blockerCount: 2,
            requiredEvidence: ['categorySummaries', 'releaseGate'],
          },
          realQueryFeedback: {
            version: 'V2.0',
            candidateCount: 3,
            source: 'rag_eval_failures_and_degraded_signals',
          },
          experimentMatrix: {
            version: 'V2.1',
            topKValues: [8],
            profiles: ['classic', 'parent-child'],
            retrievalModes: ['hybrid'],
            evaluationCount: 8,
          },
          answerQuality: {
            version: 'V2.2',
            citationEligibleCount: 4,
            citationPassedCount: 2,
            groundedPassRate: 0.5,
            hallucinationRiskCount: 2,
          },
          onlineObservability: {
            version: 'V2.3',
            fallbackCount: 1,
            fallbackRate: 0.125,
            latencyP95Ms: 120,
          },
          dataIndexGovernance: {
            version: 'V2.4',
            expectedSourceCount: 4,
            matchedExpectedSourceCount: 2,
            expectedSourceCoverageRate: 0.5,
            missingSourceCount: 2,
          },
        },
        results: [
          {
            id: 'result-1',
            caseId: 'case-id-1',
            caseKey: 'case-1',
            query: 'What formats are supported?',
            passed: true,
            failureReasons: [],
            failureCategories: [],
            hitCount: 2,
            category: 'MULTI_DOCUMENT',
            expectedFileName: 'sample.md',
            expectedFileNames: ['operations.md'],
            matchedFileName: 'sample.md',
            matchedFileNames: ['sample.md', 'operations.md'],
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
    expect(container.textContent).toContain('MULTI_DOCUMENT')
    expect(container.textContent).toContain('sample.md')
    expect(container.textContent).toContain('operations.md')
    expect(container.textContent).toContain('1/1')
    expect(container.textContent).toContain('已完成')
    expect(container.textContent).toContain('HYBRID')
    expect(container.textContent).toContain('失败分类')
    expect(container.textContent).toContain('UNEXPECTED_EVIDENCE 2')
    expect(container.textContent).toContain('MISSING_EXPECTED_FILE 1')
    expect(container.textContent).toContain('Quality dashboard')
    expect(container.textContent).toContain('Release gate rollup')
    expect(container.textContent).toContain('Category summaries / trend')
    expect(container.textContent).toContain('Profile A/B comparison')
    expect(container.textContent).toContain('MISSING_EXPECTED_FILE')
    expect(container.textContent).toContain('Release readiness')
    expect(container.textContent).toContain('Real query feedback')
    expect(container.textContent).toContain('Experiment matrix')
    expect(container.textContent).toContain('Answer quality')
    expect(container.textContent).toContain('Online observability')
    expect(container.textContent).toContain('Data/index governance')

    await act(async () => {
      setInput('caseKey', 'new-case')
      setInput('query', 'New eval query')
      setInput('minHits', '2')
      setInput('topK', '3')
      setSelect('category', 'AMBIGUOUS')
      setInput('expectedFileName', 'doc.md')
      setInput('expectedFileNames', 'current.md, legacy.md')
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
      category: 'AMBIGUOUS',
      expectedFileName: 'doc.md',
      expectedFileNames: ['current.md', 'legacy.md'],
      mustContainAny: ['alpha', 'beta'],
    })

    await act(async () => {
      setInput('experimentLabel', 'topk sweep')
      setInput('topKOverride', '8')
      checkbox('useRerank').click()
      button('运行评估')?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
    })
    expect(api.runRagEval).toHaveBeenCalledWith('kb-1', {
      useRerank: true,
      experimentLabel: 'topk sweep',
      topKOverride: 8,
    })

    await act(async () => {
      container
        ?.querySelector<HTMLButtonElement>('[aria-label="Delete eval case case-1"]')
        ?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
    })
    expect(api.deleteRagEvalCase).toHaveBeenCalledWith('kb-1', 'case-id-1')
  })


  it('runs eval against selected retrieval profiles', async () => {
    api.listRagEvalCases.mockResolvedValue([
      { id: 'case-id-1', caseKey: 'case-1', query: 'Question?', minHits: 1, topK: 5, mustContainAny: [] },
    ])
    api.listRagEvalRuns.mockResolvedValue([])
    api.runRagEval.mockResolvedValue({
      id: 'run-1',
      kbId: 'kb-1',
      useRerank: false,
      profileSet: ['CLASSIC', 'PARENT_CHILD'],
      passedCount: 2,
      totalCount: 2,
      status: 'COMPLETED',
      failureMessage: null,
      createdAt: '2026-07-12T00:01:00Z',
      results: [],
    })

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<RagEvalPanel kbId="kb-1" />)
      await Promise.resolve()
      await Promise.resolve()
      await new Promise((resolve) => setTimeout(resolve, 20))
    })

    await act(async () => {
      checkbox('profile-PARENT_CHILD').click()
      await Promise.resolve()
    })

    const runButton = container?.querySelector<HTMLButtonElement>('[aria-label="Run RAG eval"]')
    expect(runButton).toBeTruthy()
    expect(runButton?.disabled).toBe(false)

    await act(async () => {
      runButton?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
    })

    expect(api.runRagEval).toHaveBeenCalledWith('kb-1', {
      useRerank: false,
      profiles: ['CLASSIC', 'PARENT_CHILD'],
    })
  })

  it('renders profile gate comparison metrics', async () => {
    api.listRagEvalCases.mockResolvedValue([])
    api.listRagEvalRuns.mockResolvedValue([
      {
        id: 'run-gate',
        kbId: 'kb-1',
        useRerank: false,
        profileSet: ['CLASSIC', 'PARENT_CHILD'],
        passedCount: 5,
        totalCount: 6,
        status: 'COMPLETED',
        failureMessage: null,
        createdAt: '2026-07-12T00:00:00Z',
        gateSummary: {
          PARENT_CHILD: {
            candidate: 'PARENT_CHILD',
            baseline: 'CLASSIC',
            status: 'BLOCKED',
            reason: 'hit_rate_regressed',
            metrics: {
              profile: 'PARENT_CHILD',
              totalCases: 3,
              passedCount: 2,
              hitPassedCount: 2,
              citationEligibleCount: 2,
              citationPassedCount: 1,
              passRate: 0.667,
              hitRate: 0.667,
              citationPassRate: 0.5,
            },
            classicMetrics: {
              profile: 'CLASSIC',
              totalCases: 3,
              passedCount: 3,
              hitPassedCount: 3,
              citationEligibleCount: 2,
              citationPassedCount: 2,
              passRate: 1,
              hitRate: 1,
              citationPassRate: 1,
            },
            hitRateDelta: -0.333,
            citationPassRateDelta: -0.5,
          },
        },
        results: [],
      },
    ])

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<RagEvalPanel kbId="kb-1" />)
      await Promise.resolve()
    })

    expect(container.textContent).toContain('PARENT_CHILD BLOCKED')
    expect(container.textContent).toContain('Hit 66.7%')
    expect(container.textContent).toContain('Citation 50.0%')
    expect(container.textContent).toContain('Hit delta -33.3%')
    expect(container.textContent).toContain('hit_rate_regressed')
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

  function setSelect(name: string, value: string) {
    const field = container?.querySelector<HTMLSelectElement>(`select[name="${name}"]`)
    if (!field) throw new Error(`Missing select ${name}`)
    const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set
    setter?.call(field, value)
    field.dispatchEvent(new Event('change', { bubbles: true }))
  }

  function button(label: string): HTMLButtonElement | undefined {
    return Array.from(container?.querySelectorAll('button') ?? []).find((item) =>
      item.textContent?.includes(label),
    )
  }
})
