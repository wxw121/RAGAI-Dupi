import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DocTable } from './DocTable'
import type { Document, IngestJob } from '@/types'

describe('DocTable', () => {
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

  it('renders ingest diagnosis summary and next action beside the document', () => {
    const doc: Document = {
      id: 'doc-1',
      kbId: 'kb-1',
      fileName: 'sample-knowledge.md',
      mimeType: 'text/markdown',
      fileSize: 1024,
      status: 'FAILED',
      errorMessage: 'bad pdf',
      createdAt: '2026-07-12T00:00:00Z',
      updatedAt: '2026-07-12T00:00:00Z',
    }
    const job: IngestJob = {
      id: 'job-1',
      kbId: 'kb-1',
      docId: 'doc-1',
      documentFileName: 'sample-knowledge.md',
      documentStatus: 'FAILED',
      status: 'FAILED',
      stage: 'FAILED',
      retryCount: 1,
      errorMessage: 'bad pdf',
      diagnosis: {
        severity: 'error',
        summary: '摄入失败：bad pdf',
        nextAction: '检查文档格式、Embedding 配置和 Worker 日志，必要时手动重试摄入。',
        retryable: true,
        stalled: false,
        ageSeconds: 120,
        lastUpdatedSeconds: 30,
      },
      createdAt: '2026-07-12T00:00:00Z',
      updatedAt: '2026-07-12T00:00:00Z',
    }
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    act(() => {
      root?.render(<DocTable documents={[doc]} jobStages={{}} ingestJobs={[job]} />)
    })

    expect(container.textContent).toContain('摄入失败')
    expect(container.textContent).toContain('Embedding')
    expect(container.textContent).toContain('Worker')
    expect(container.textContent).toContain('可重试')
  })
})
