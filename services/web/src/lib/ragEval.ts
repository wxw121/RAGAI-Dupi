import type { RagEvalCase, RagEvalResult, RetrievalHit, RetrieveResponse } from '@/types'

export const BUILT_IN_RAG_EVAL_CASES: RagEvalCase[] = [
  {
    id: 'formats-supported',
    query: 'dupi-RAG supports which document formats?',
    minHits: 1,
    expectedFileName: 'sample-knowledge.md',
    mustContainAny: ['PDF', 'Word', 'Excel'],
  },
  {
    id: 'core-capabilities',
    query: 'What are the core capabilities of dupi-RAG?',
    minHits: 1,
    expectedFileName: 'sample-knowledge.md',
    mustContainAny: ['Milvus', 'SSE', 'BM25', 'Embedding'],
  },
  {
    id: 'chunk-strategies',
    query: 'Which chunk strategies are mentioned?',
    minHits: 1,
    expectedFileName: 'sample-knowledge.md',
    mustContainAny: ['recursive', 'semantic'],
  },
]

export function evaluateRetrievalCase(caseDef: RagEvalCase, response: RetrieveResponse): RagEvalResult {
  const hits = response.hits ?? []
  const expectedFileName = caseDef.expectedFileName ?? null
  const matchedFile = expectedFileName
    ? hits.find((hit) => hit.fileName === expectedFileName)
    : hits[0]
  const matchedToken = findMatchedToken(caseDef.mustContainAny ?? [], hits)
  const failureReasons: string[] = []

  if (hits.length < caseDef.minHits) {
    failureReasons.push(`expected at least ${caseDef.minHits} hits, got ${hits.length}`)
  }
  if (expectedFileName && !matchedFile) {
    failureReasons.push(`missing expected file ${expectedFileName}`)
  }
  if ((caseDef.mustContainAny?.length ?? 0) > 0 && !matchedToken) {
    failureReasons.push(`missing expected token: ${(caseDef.mustContainAny ?? []).join(', ')}`)
  }

  return {
    id: caseDef.id,
    query: caseDef.query,
    passed: failureReasons.length === 0,
    failureReasons,
    hitCount: hits.length,
    expectedFileName,
    matchedFileName: matchedFile?.fileName ?? null,
    matchedToken,
    retrievalMode: stringValue(response.diagnostics?.retrievalMode) ?? response.retrievalMode ?? null,
    fallbackReason: stringValue(response.diagnostics?.fallbackReason),
    embeddingModel: stringValue(response.diagnostics?.embeddingModel),
    embeddingDimension: numberValue(response.diagnostics?.embeddingDimension),
    topK: numberValue(response.diagnostics?.topK) ?? caseDef.topK ?? null,
  }
}

function findMatchedToken(tokens: string[], hits: RetrievalHit[]): string | null {
  if (tokens.length === 0) return null
  const text = hits
    .map((hit) => [hit.fileName, hit.content, JSON.stringify(hit.metadata ?? {})].join(' '))
    .join('\n')
  return tokens.find((token) => text.includes(token)) ?? null
}

function stringValue(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}

function numberValue(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}
