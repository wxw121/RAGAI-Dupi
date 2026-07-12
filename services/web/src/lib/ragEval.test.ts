import { describe, expect, it } from 'vitest'
import { evaluateRetrievalCase } from './ragEval'
import type { RagEvalCase, RetrieveResponse } from '@/types'

describe('evaluateRetrievalCase', () => {
  it('passes when minimum hits, expected file, and token all match', () => {
    const caseDef: RagEvalCase = {
      id: 'core-capabilities',
      query: 'What are the core capabilities of dupi-RAG?',
      minHits: 1,
      expectedFileName: 'sample-knowledge.md',
      mustContainAny: ['Milvus', 'BM25'],
    }
    const response: RetrieveResponse = {
      query: caseDef.query,
      retrievalMode: 'hybrid',
      hits: [
        {
          chunkId: 'chunk-1',
          docId: 'doc-1',
          fileName: 'sample-knowledge.md',
          content: 'Core capabilities include Milvus vector search and SSE answers.',
          score: 0.92,
          metadata: { heading: 'Capabilities' },
        },
      ],
      diagnostics: {
        retrievalMode: 'hybrid',
        hitCount: 1,
        topK: 5,
        embeddingModel: 'embedding-2',
        embeddingDimension: 1024,
      },
    }

    const result = evaluateRetrievalCase(caseDef, response)

    expect(result.passed).toBe(true)
    expect(result.hitCount).toBe(1)
    expect(result.expectedFileName).toBe('sample-knowledge.md')
    expect(result.matchedFileName).toBe('sample-knowledge.md')
    expect(result.matchedToken).toBe('Milvus')
    expect(result.retrievalMode).toBe('hybrid')
    expect(result.embeddingDimension).toBe(1024)
  })

  it('fails with concrete reasons when required hits are missing', () => {
    const caseDef: RagEvalCase = {
      id: 'formats-supported',
      query: 'dupi-RAG supports which document formats?',
      minHits: 2,
      expectedFileName: 'sample-knowledge.md',
      mustContainAny: ['PDF'],
    }
    const response: RetrieveResponse = {
      query: caseDef.query,
      retrievalMode: 'vector',
      hits: [],
      diagnostics: {
        retrievalMode: 'vector',
        hitCount: 0,
        fallbackReason: 'milvus_unavailable',
      },
    }

    const result = evaluateRetrievalCase(caseDef, response)

    expect(result.passed).toBe(false)
    expect(result.failureReasons).toContain('expected at least 2 hits, got 0')
    expect(result.failureReasons).toContain('missing expected file sample-knowledge.md')
    expect(result.failureReasons).toContain('missing expected token: PDF')
    expect(result.fallbackReason).toBe('milvus_unavailable')
  })
})
