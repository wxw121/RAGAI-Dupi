import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it } from 'vitest'
import { DocumentIndexDetailPanel } from './DocumentIndexDetailPanel'
import type { DocumentIndexDetail } from '@/types'

describe('DocumentIndexDetailPanel', () => {
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
  })

  it('renders object job diagnosis and chunk samples', () => {
    const detail: DocumentIndexDetail = {
      document: {
        id: 'doc-1',
        kbId: 'kb-1',
        fileName: 'sample.md',
        mimeType: 'text/markdown',
        fileSize: 128,
        status: 'FAILED',
        errorMessage: 'parse failed',
        createdAt: '2026-07-12T00:00:00Z',
        updatedAt: '2026-07-12T00:00:00Z',
      },
      latestJob: {
        id: 'job-1',
        kbId: 'kb-1',
        docId: 'doc-1',
        status: 'FAILED',
        stage: 'FAILED',
        retryCount: 1,
        errorMessage: 'parse failed',
        diagnosis: {
          severity: 'error',
          summary: 'ingest failed: parse failed',
          nextAction: 'check worker logs',
          retryable: true,
          stalled: false,
          ageSeconds: 60,
          lastUpdatedSeconds: 10,
        },
        createdAt: '2026-07-12T00:00:00Z',
        updatedAt: '2026-07-12T00:00:00Z',
      },
      objectKey: 'kb/doc/sample.md',
      objectAvailable: false,
      indexReady: false,
      chunkCount: 1,
      chunks: [
        {
          id: 'chunk-1',
          chunkIndex: 0,
          contentPreview: 'chunk preview',
          tokenCount: 3,
          metadata: { heading: 'Intro' },
          milvusId: 'm1',
        },
      ],
    }
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    act(() => {
      root?.render(<DocumentIndexDetailPanel detail={detail} />)
    })

    expect(container.textContent).toContain('sample.md')
    expect(container.textContent).toContain('kb/doc/sample.md')
    expect(container.textContent).toContain('object missing')
    expect(container.textContent).toContain('ingest failed')
    expect(container.textContent).toContain('check worker logs')
    expect(container.textContent).toContain('chunk preview')
    expect(container.textContent).toContain('Intro')
  })
})
