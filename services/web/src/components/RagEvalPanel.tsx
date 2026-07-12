import { useMemo, useState } from 'react'
import { retrieveKnowledgeBase } from '@/api/knowledgeBase'
import { useToast } from '@/components/Toast'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/dialog'
import { BUILT_IN_RAG_EVAL_CASES, evaluateRetrievalCase } from '@/lib/ragEval'
import type { RagEvalCase, RagEvalResult } from '@/types'
import { CheckCircle2, Loader2, Play, XCircle } from 'lucide-react'

interface RagEvalPanelProps {
  kbId: string
  cases?: RagEvalCase[]
}

export function RagEvalPanel({ kbId, cases = BUILT_IN_RAG_EVAL_CASES }: RagEvalPanelProps) {
  const [results, setResults] = useState<RagEvalResult[]>([])
  const [running, setRunning] = useState(false)
  const { showError, showSuccess } = useToast()
  const summary = useMemo(() => {
    const passed = results.filter((result) => result.passed).length
    return { passed, total: results.length }
  }, [results])

  const run = async () => {
    setRunning(true)
    try {
      const nextResults = await Promise.all(
        cases.map(async (caseDef) => {
          try {
            const response = await retrieveKnowledgeBase(kbId, {
              query: caseDef.query,
              topK: caseDef.topK ?? 5,
            })
            return evaluateRetrievalCase(caseDef, response)
          } catch (e) {
            const message = e instanceof Error ? e.message : '检索请求失败'
            return {
              id: caseDef.id,
              query: caseDef.query,
              passed: false,
              failureReasons: [message],
              hitCount: 0,
              expectedFileName: caseDef.expectedFileName ?? null,
              matchedFileName: null,
              matchedToken: null,
              retrievalMode: null,
              fallbackReason: message,
              embeddingModel: null,
              embeddingDimension: null,
              topK: caseDef.topK ?? 5,
            } satisfies RagEvalResult
          }
        }),
      )
      setResults(nextResults)
      const passed = nextResults.filter((result) => result.passed).length
      if (passed === nextResults.length) {
        showSuccess('RAG 评估通过')
      } else {
        showError(`RAG 评估未通过：${passed}/${nextResults.length}`)
      }
    } finally {
      setRunning(false)
    }
  }

  return (
    <div className="mx-auto max-w-5xl space-y-5 px-4 py-6 md:px-8">
      <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <div>
          <h2 className="text-base font-semibold">RAG 评估</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            使用内置用例检查当前知识库的检索命中、来源文件和关键片段。
          </p>
        </div>
        <Button onClick={run} disabled={running}>
          {running ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
          运行评估
        </Button>
      </div>

      <div className="rounded-2xl border border-border bg-background p-4">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="font-medium">内置用例</span>
          <Badge variant="muted">{cases.length}</Badge>
          {summary.total > 0 && (
            <span className="text-muted-foreground">
              通过 {summary.passed}/{summary.total}
            </span>
          )}
        </div>
        <div className="mt-3 flex flex-wrap gap-2">
          {cases.map((caseDef) => (
            <span
              key={caseDef.id}
              className="rounded-full border border-border px-2.5 py-1 text-xs text-muted-foreground"
            >
              {caseDef.id}
            </span>
          ))}
        </div>
      </div>

      <div className="chatgpt-scrollbar overflow-x-auto rounded-2xl border border-border bg-background">
        <table className="w-full text-sm">
          <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
            <tr>
              <th className="px-4 py-3 text-left font-medium">用例</th>
              <th className="px-4 py-3 text-left font-medium">结果</th>
              <th className="px-4 py-3 text-left font-medium">命中</th>
              <th className="px-4 py-3 text-left font-medium">文件</th>
              <th className="px-4 py-3 text-left font-medium">Token</th>
              <th className="px-4 py-3 text-left font-medium">模式</th>
              <th className="px-4 py-3 text-left font-medium">诊断</th>
            </tr>
          </thead>
          <tbody>
            {results.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-10 text-center text-muted-foreground">
                  尚未运行评估
                </td>
              </tr>
            ) : (
              results.map((result) => (
                <tr key={result.id} className="border-b last:border-0">
                  <td className="px-4 py-3">
                    <p className="font-mono text-xs">{result.id}</p>
                    <p className="mt-1 max-w-xs truncate text-xs text-muted-foreground">{result.query}</p>
                  </td>
                  <td className="px-4 py-3">
                    <span className="inline-flex items-center gap-1 font-mono text-xs font-semibold">
                      {result.passed ? (
                        <CheckCircle2 className="h-4 w-4 text-emerald-600" />
                      ) : (
                        <XCircle className="h-4 w-4 text-destructive" />
                      )}
                      {result.passed ? 'PASS' : 'FAIL'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">{result.hitCount}</td>
                  <td className="px-4 py-3">
                    <p className="font-mono text-xs">{result.matchedFileName ?? '-'}</p>
                    {result.expectedFileName && (
                      <p className="mt-1 text-xs text-muted-foreground">期望 {result.expectedFileName}</p>
                    )}
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">{result.matchedToken ?? '-'}</td>
                  <td className="px-4 py-3">
                    <p className="font-mono text-xs">{result.retrievalMode ?? '-'}</p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      dim {result.embeddingDimension ?? '-'}
                    </p>
                  </td>
                  <td className="max-w-sm px-4 py-3 text-xs text-muted-foreground">
                    {result.failureReasons.length > 0
                      ? result.failureReasons.join('；')
                      : result.fallbackReason ?? '-'}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
