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
  listRetrievalProfiles: vi.fn(),
  runRagEval: vi.fn(),
  getRagQualityPolicy: vi.fn(),
  updateRagQualityPolicy: vi.fn(),
  promoteRagEvalBaseline: vi.fn(),
  previewRagEvalCaseGeneration: vi.fn(),
  confirmRagEvalCaseGeneration: vi.fn(),
  previewAddRagEvalCases: vi.fn(),
  confirmAddRagEvalCases: vi.fn(),
}))

const documentApi = vi.hoisted(() => ({ listDocuments: vi.fn() }))

vi.mock('@/api/knowledgeBase', () => api)
vi.mock('@/api/documents', () => documentApi)

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
            version: 'V2.0',
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
            version: 'V2.0',
            topKValues: [8],
            profiles: ['classic', 'parent-child'],
            retrievalModes: ['hybrid'],
            evaluationCount: 8,
          },
          answerQuality: {
            version: 'V2.0',
            citationEligibleCount: 4,
            citationPassedCount: 2,
            groundedPassRate: 0.5,
            hallucinationRiskCount: 2,
            judgeStatus: 'REVIEW_REQUIRED',
          },
          onlineObservability: {
            version: 'V2.0',
            fallbackCount: 1,
            fallbackRate: 0.125,
            latencyP95Ms: 120,
            sloStatus: 'DEGRADED',
          },
          dataIndexGovernance: {
            version: 'V2.0',
            expectedSourceCount: 4,
            matchedExpectedSourceCount: 2,
            expectedSourceCoverageRate: 0.5,
            missingSourceCount: 2,
            governanceStatus: 'ACTION_REQUIRED',
          },
          onlineSlo: {
            version: 'V2.0',
            status: 'BREACHED',
            breachedObjectives: ['fallbackRate', 'passRate'],
          },
          canaryGate: {
            version: 'V2.0',
            decision: 'ROLLBACK',
            reasons: ['releaseGateBlocked', 'onlineSloBreached'],
            profileGateStatuses: { 'parent-child': 'PASSED' },
          },
          v2QualityClosure: {
            version: 'V2.0',
            status: 'BLOCKED',
            completedCapabilities: ['persistentFeedbackCandidates', 'deterministicAnswerJudge', 'canaryPromoteRollbackGate'],
            recommendedActions: ['hold_canary_and_triage_quality_regressions'],
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
    expect(container.textContent).toContain('多文档')
    expect(container.textContent).toContain('sample.md')
    expect(container.textContent).toContain('operations.md')
    expect(container.textContent).toContain('1/1')
    expect(container.textContent).toContain('已完成')
    expect(container.textContent).toContain('HYBRID')
    expect(container.textContent).toContain('失败分类')
    expect(container.textContent).toContain('出现非预期来源 2')
    expect(container.textContent).toContain('缺少期望来源文件 1')
    expect(container.textContent).toContain('Quality dashboard')
    expect(container.textContent).toContain('Release gate rollup')
    expect(container.textContent).toContain('Category summaries / trend')
    expect(container.textContent).toContain('Profile A/B comparison')
    expect(container.textContent).toContain('缺少期望来源文件')
    expect(container.textContent).toContain('Release readiness')
    expect(container.textContent).toContain('V2.0 Quality closure')
    expect(container.textContent).toContain('Real query feedback')
    expect(container.textContent).toContain('Experiment matrix')
    expect(container.textContent).toContain('Answer quality')
    expect(container.textContent).toContain('Canary gate')
    expect(container.textContent).toContain('Online SLO')
    expect(container.textContent).toContain('parent-child:PASSED')
    expect(container.textContent).toContain('Online observability')
    expect(container.textContent).toContain('Data/index governance')

    const releaseReadinessHelp = container.querySelector<HTMLButtonElement>(
      '[aria-label="查看“Release readiness”的解释"]',
    )
    const releaseReadinessTooltip = releaseReadinessHelp?.parentElement?.querySelector('[role="tooltip"]')
    expect(releaseReadinessHelp).not.toBeNull()
    expect(releaseReadinessTooltip?.textContent).toContain('发布准备状态')
    expect(releaseReadinessTooltip?.textContent).toContain('作用：')
    expect(releaseReadinessTooltip?.className).toContain('group-hover:visible')

    const answerQualityTooltip = container
      .querySelector<HTMLButtonElement>('[aria-label="查看“Answer quality”的解释"]')
      ?.parentElement?.querySelector('[role="tooltip"]')
    expect(answerQualityTooltip?.textContent).toContain('幻觉风险')

    const profileComparisonHelp = container.querySelector<HTMLButtonElement>(
      '[aria-label="查看“Profile A/B comparison”的解释"]',
    )
    expect(profileComparisonHelp?.closest('.overflow-x-auto')).toBeNull()
    expect(profileComparisonHelp?.parentElement?.querySelector('[role="tooltip"]')?.textContent)
      .toContain('检索 Profile')

    await act(async () => {
      setInput('caseKey', 'new-case')
      setInput('query', 'New eval query')
      setInput('minHits', '2')
      setInput('topK', '3')
      setSelect('category', 'AMBIGUOUS')
      setInput('expectedFileName', 'doc.md')
      setInput('expectedFileNames', 'current.md,    legacy.md')
      setInput('mustContainAny', 'POST /api/users,    HTTP 方法')
    })

    expect(input('expectedFileNames').value).toBe('current.md, legacy.md')
    expect(input('mustContainAny').value).toBe('POST /api/users, HTTP 方法')

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
      mustContainAny: ['POST /api/users', 'HTTP 方法'],
    })

    await act(async () => {
      setInput('experimentLabel', 'topk sweep')
      setInput('topKOverride', '8')
      checkbox('useRerank').click()
      button('运行常规评估')?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
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

    const runButton = button('运行常规评估')
    expect(runButton).toBeTruthy()
    expect(runButton?.getAttribute('aria-label')).toBeNull()
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

  it('runs a selected retrieval configuration without experimental overrides', async () => {
    api.getRagQualityPolicy.mockResolvedValue(null)
    api.listRagEvalCases.mockResolvedValue([
      { id: 'case-id-1', caseKey: 'case-1', query: 'Question?', minHits: 1, topK: 5, mustContainAny: [] },
    ])
    api.listRagEvalRuns.mockResolvedValue([])
    api.listRetrievalProfiles.mockResolvedValue([
      { id: 'profile-v1', version: 1, name: '默认方案', active: false },
    ])
    api.runRagEval.mockResolvedValue({
      id: 'run-1', kbId: 'kb-1', useRerank: true, passedCount: 1, totalCount: 1,
      status: 'COMPLETED', gateStatus: 'PASS', failureMessage: null,
      createdAt: '2026-07-12T00:01:00Z', results: [], profileSnapshot: { profileId: 'profile-v1' },
    })

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => {
      root?.render(<RagEvalPanel kbId="kb-1" />)
      await new Promise((resolve) => setTimeout(resolve, 20))
    })

    await act(async () => {
      const select = container?.querySelector<HTMLSelectElement>('[aria-label="评估方案"]')
      if (!select) throw new Error('evaluation profile selector was not rendered')
      select.value = 'profile-v1'
      select.dispatchEvent(new Event('change', { bubbles: true }))
      await Promise.resolve()
    })

    expect(container.textContent).toContain('正在评估 v1 · 默认方案')
    await act(async () => {
      button('运行方案评估')?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
    })
    expect(api.runRagEval).toHaveBeenCalledWith('kb-1', { profileId: 'profile-v1' })
  })

  it('blocks invalid cases and replaces them only after preview confirmation', async () => {
    api.getRagQualityPolicy.mockResolvedValue(null)
    api.listRagEvalRuns.mockResolvedValue([])
    api.listRetrievalProfiles.mockResolvedValue([])
    api.listRagEvalCases.mockResolvedValue([{ id: 'old-1', caseKey: 'old', query: 'old?', minHits: 1,
      sourceValid: false, missingExpectedFileNames: ['sample-knowledge.md'] }])
    api.previewRagEvalCaseGeneration.mockResolvedValue({
      documentFingerprint: 'docs-v1', caseFingerprint: 'cases-v1', confirmable: true,
      retainedCases: [], replacedCases: [{ id: 'old-1', query: 'old?', minHits: 1 }],
      documents: [{ documentId: 'doc-1', fileName: 'tutorial.md', existingSingleSourceCount: 0,
        deficit: 2, covered: false, error: null, proposals: [
          { caseKey: 'tutorial-1', query: '如何安装？', expectedFileName: 'tutorial.md', mustContainAny: ['安装', '命令'], category: 'REAL_QUERY', minHits: 1, topK: 5 },
          { caseKey: 'tutorial-2', query: '如何配置？', expectedFileName: 'tutorial.md', mustContainAny: ['配置', '文件'], category: 'REAL_QUERY', minHits: 1, topK: 5 },
        ] }],
    })
    api.confirmRagEvalCaseGeneration.mockResolvedValue([{ id: 'new-1', caseKey: 'tutorial-1', query: '如何安装？', minHits: 1, sourceValid: true }])

    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => { root?.render(<RagEvalPanel kbId="kb-1" />); await Promise.resolve() })

    expect(container.textContent).toContain('发现 1 条失效评估用例')
    expect(button('运行常规评估')?.disabled).toBe(true)
    await act(async () => { button('AI 修复失效用例')?.click(); await Promise.resolve() })
    expect(container.textContent).toContain('tutorial.md')
    expect(container.textContent).toContain('如何安装？')
    expect(api.confirmRagEvalCaseGeneration).not.toHaveBeenCalled()

    await act(async () => { button('确认替换')?.click(); await Promise.resolve() })
    expect(api.confirmRagEvalCaseGeneration).toHaveBeenCalledWith('kb-1', {
      documentFingerprint: 'docs-v1', caseFingerprint: 'cases-v1', replaceCaseIds: ['old-1'],
      generatedCases: expect.arrayContaining([expect.objectContaining({ expectedFileName: 'tutorial.md' })]),
    })
    expect(container.textContent).not.toContain('发现 1 条失效评估用例')
  })

  it('keeps a generation request failure visible for retry', async () => {
    api.getRagQualityPolicy.mockResolvedValue(null)
    api.listRagEvalRuns.mockResolvedValue([])
    api.listRagEvalCases.mockResolvedValue([{ id: 'old-1', query: 'old?', minHits: 1,
      sourceValid: false, missingExpectedFileNames: ['gone.md'] }])
    api.previewRagEvalCaseGeneration.mockRejectedValue(new Error('模型服务暂时不可用'))
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => { root?.render(<RagEvalPanel kbId="kb-1" />); await Promise.resolve() })

    await act(async () => { button('AI 修复失效用例')?.click(); await Promise.resolve() })

    expect(container.textContent).toContain('生成失败：模型服务暂时不可用')
    expect(button('AI 修复失效用例')?.disabled).toBe(false)
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

  it('uses the release gate when the legacy run gate status is absent', async () => {
    api.getRagQualityPolicy.mockResolvedValue(null)
    api.listRagEvalCases.mockResolvedValue([{ id: 'case-1', query: 'q', minHits: 1, sourceValid: true }])
    api.listRagEvalRuns.mockResolvedValue([{ id: 'run-1', passedCount: 10, totalCount: 10,
      status: 'COMPLETED', createdAt: '2026-08-13T05:08:29Z', results: [],
      metrics: { releaseGate: { status: 'PASS', passed: 10, total: 10, passRate: 1 } } }])
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => { root?.render(<RagEvalPanel kbId="kb-1" />); await Promise.resolve() })

    expect(container.textContent).toContain('门禁 PASS')
    expect(container.textContent).not.toContain('NO GATE')
  })

  it('appends AI-generated cases for selected completed documents', async () => {
    api.getRagQualityPolicy.mockResolvedValue(null)
    api.listRagEvalCases.mockResolvedValue([])
    api.listRagEvalRuns.mockResolvedValue([])
    documentApi.listDocuments.mockResolvedValue([
      { id: 'doc-1', kbId: 'kb-1', fileName: 'guide.md', status: 'COMPLETED' },
      { id: 'doc-2', kbId: 'kb-1', fileName: 'pending.md', status: 'PROCESSING' },
    ])
    const proposals = [
      { caseKey: 'guide-1', query: '问题一', expectedFileName: 'guide.md', mustContainAny: ['关键词一', '关键词二'], category: 'REAL_QUERY', minHits: 1, topK: 5 },
      { caseKey: 'guide-2', query: '问题二', expectedFileName: 'guide.md', mustContainAny: ['关键词三', '关键词四'], category: 'REAL_QUERY', minHits: 1, topK: 5 },
    ]
    api.previewAddRagEvalCases.mockResolvedValue({
      documentFingerprint: 'docs', caseFingerprint: 'cases', retainedCases: [], replacedCases: [], confirmable: true,
      documents: [{ documentId: 'doc-1', fileName: 'guide.md', existingSingleSourceCount: 0, deficit: 2, covered: false, proposals }],
    })
    api.confirmAddRagEvalCases.mockResolvedValue([
      { id: 'case-1', caseKey: 'guide-1', query: '问题一', minHits: 1, sourceValid: true },
      { id: 'case-2', caseKey: 'guide-2', query: '问题二', minHits: 1, sourceValid: true },
    ])
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => { root?.render(<RagEvalPanel kbId="kb-1" />); await Promise.resolve() })
    expect(button('AI 新增用例')).not.toBeUndefined()

    await act(async () => { button('AI 新增用例')?.click(); await Promise.resolve(); await Promise.resolve() })
    expect(documentApi.listDocuments).toHaveBeenCalledWith('kb-1')
    expect(container.textContent).toContain('guide.md')
    expect(container.textContent).not.toContain('pending.md')

    await act(async () => {
      const field = checkbox('addDocument-doc-1')
      field.click()
    })
    await act(async () => { button('生成预览')?.click(); await Promise.resolve() })
    expect(api.previewAddRagEvalCases).toHaveBeenCalledWith('kb-1', { documentIds: ['doc-1'], casesPerDocument: 2 })
    expect(container.textContent).toContain('问题一')
    expect(container.textContent).toContain('现有 0 条保持不变')

    await act(async () => { button('确认新增')?.click(); await Promise.resolve() })
    expect(api.confirmAddRagEvalCases).toHaveBeenCalledWith('kb-1', {
      documentFingerprint: 'docs', caseFingerprint: 'cases', documentIds: ['doc-1'], casesPerDocument: 2, generatedCases: proposals,
    })
    expect(container.textContent).toContain('guide-1')
  })

  it('localizes and applies every result detail filter', async () => {
    api.getRagQualityPolicy.mockResolvedValue(null)
    api.listRagEvalCases.mockResolvedValue([])
    api.listRagEvalRuns.mockResolvedValue([{
      id: 'run-filter', passedCount: 1, totalCount: 3, status: 'COMPLETED', createdAt: '2026-08-13T05:08:29Z',
      results: [
        { id: 'r-pass', caseKey: 'pass-case', query: 'pass query', passed: true, category: 'REAL_QUERY',
          failureReasons: [], failureCategories: [], hitCount: 1 },
        { id: 'r-file', caseKey: 'file-case', query: 'file query', passed: false, category: 'MULTI_DOCUMENT',
          failureReasons: ['missing file'], failureCategories: ['MISSING_EXPECTED_FILE'], hitCount: 0 },
        { id: 'r-exception', caseKey: 'exception-case', query: 'exception query', passed: false, category: 'HARD_NEGATIVE',
          failureReasons: ['retrieval failed'], failureCategories: ['RETRIEVAL_EXCEPTION'], hitCount: 0 },
      ],
    }])
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => { root?.render(<RagEvalPanel kbId="kb-1" />); await Promise.resolve() })

    const categorySelect = container.querySelector<HTMLSelectElement>('select[name="diagnosticCategory"]')
    const statusSelect = container.querySelector<HTMLSelectElement>('select[name="diagnosticStatus"]')
    const failureSelect = container.querySelector<HTMLSelectElement>('select[name="diagnosticFailureCategory"]')
    expect(categorySelect?.textContent).toContain('全部场景（3）')
    expect(categorySelect?.textContent).not.toContain('歧义问题')
    expect(statusSelect?.textContent).toContain('通过（1）')
    expect(statusSelect?.textContent).toContain('未通过（2）')
    expect(failureSelect?.textContent).toContain('缺少期望来源文件')

    await act(async () => { setSelect('diagnosticCategory', 'MULTI_DOCUMENT') })
    expect(container.textContent).toContain('显示 1/3 条结果')
    expect(container.textContent).toContain('file-case')
    expect(container.textContent).not.toContain('exception-case')

    await act(async () => {
      setSelect('diagnosticCategory', 'ALL')
      setSelect('diagnosticStatus', 'FAIL')
    })
    expect(container.textContent).toContain('显示 2/3 条结果')

    await act(async () => { setSelect('diagnosticFailureCategory', 'RETRIEVAL_EXCEPTION') })
    expect(container.textContent).toContain('显示 1/3 条结果')
    expect(container.textContent).toContain('exception-case')
    expect(container.textContent).not.toContain('file-case')

    await act(async () => { setSelect('diagnosticStatus', 'PASS') })
    expect(container.textContent).toContain('显示 1/3 条结果')
    expect(failureSelect?.disabled).toBe(true)
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
