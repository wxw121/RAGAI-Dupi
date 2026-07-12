import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RagEvalPanel } from './RagEvalPanel'

const api = vi.hoisted(() => ({
  retrieveKnowledgeBase: vi.fn(),
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

  it('runs built-in cases and renders pass/fail diagnostics', async () => {
    api.retrieveKnowledgeBase.mockResolvedValue({
      query: 'q',
      retrievalMode: 'hybrid',
      hits: [
        {
          chunkId: 'chunk-1',
          docId: 'doc-1',
          fileName: 'sample-knowledge.md',
          content: 'PDF Word Excel Milvus SSE BM25 Embedding recursive semantic',
          score: 0.9,
          metadata: {},
        },
      ],
      diagnostics: {
        retrievalMode: 'hybrid',
        hitCount: 1,
        embeddingDimension: 1024,
      },
    })
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<RagEvalPanel kbId="kb-1" />)
    })

    const button = Array.from(container.querySelectorAll('button')).find((item) =>
      item.textContent?.includes('运行评估'),
    )
    expect(button).toBeTruthy()

    await act(async () => {
      button?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
    })

    expect(api.retrieveKnowledgeBase).toHaveBeenCalledWith('kb-1', expect.objectContaining({ topK: 5 }))
    expect(container.textContent).toContain('formats-supported')
    expect(container.textContent).toContain('sample-knowledge.md')
    expect(container.textContent).toContain('PASS')
    expect(container.textContent).toContain('hybrid')
    expect(container.textContent).toContain('1024')
  })
})
