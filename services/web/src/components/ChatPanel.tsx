import { useRef, useState } from 'react'
import { cancelChat, streamChat } from '@/api/chat'
import type { ChatMessage, Citation } from '@/types'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { useToast } from '@/components/Toast'
import { Loader2, Send, Square } from 'lucide-react'
import { cn } from '@/lib/utils'
import { MarkdownContent } from '@/components/MarkdownContent'

interface ChatPanelProps {
  kbId: string
  completedDocCount: number
}

function formatChatError(message: string): string {
  const lower = message.toLowerCase()
  if (
    lower.includes('401') ||
    lower.includes('unauthorized') ||
    lower.includes('invalid api key') ||
    lower.includes('authentication')
  ) {
    return (
      'LLM API 认证失败。请在 deploy/.env 中配置 CHAT_API_KEY（问答，如 DeepSeek）' +
      ' 和 EMBEDDING_API_KEY（向量化，需支持 /embeddings 的 OpenAI 兼容服务），然后重启 docker compose。'
    )
  }
  return message
}

export function ChatPanel({ kbId, completedDocCount }: ChatPanelProps) {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [citations, setCitations] = useState<Citation[]>([])
  const sessionIdRef = useRef<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const { showError } = useToast()

  const canChat = completedDocCount > 0

  const send = async () => {
    const query = input.trim()
    if (!query || streaming || !canChat) return

    const userMsg: ChatMessage = { id: crypto.randomUUID(), role: 'user', content: query }
    const assistantId = crypto.randomUUID()
    setMessages((prev) => [
      ...prev,
      userMsg,
      { id: assistantId, role: 'assistant', content: '', streaming: true },
    ])
    setInput('')
    setStreaming(true)
    setCitations([])

    const controller = new AbortController()
    abortRef.current = controller
    const sessionId = crypto.randomUUID()
    sessionIdRef.current = sessionId

    try {
      await streamChat(
        kbId,
        query,
        {
          onRetrieval: (cits) => setCitations(cits),
          onToken: (token) => {
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantId ? { ...m, content: m.content + token } : m,
              ),
            )
          },
          onDone: (sid) => {
            sessionIdRef.current = sid || sessionId
            setMessages((prev) =>
              prev.map((m) => (m.id === assistantId ? { ...m, streaming: false } : m)),
            )
            setStreaming(false)
          },
          onError: (msg) => {
            const formatted = formatChatError(msg)
            showError(formatted)
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantId
                  ? { ...m, content: m.content || `错误：${formatted}`, streaming: false }
                  : m,
              ),
            )
            setStreaming(false)
          },
          onAbort: () => {
            setMessages((prev) =>
              prev.map((m) => (m.id === assistantId ? { ...m, streaming: false } : m)),
            )
            setStreaming(false)
          },
        },
        controller.signal,
        sessionId,
      )
    } catch (e) {
      const formatted = formatChatError(e instanceof Error ? e.message : '问答请求失败')
      showError(formatted)
      setMessages((prev) =>
        prev.map((m) =>
          m.id === assistantId
            ? { ...m, content: m.content || `错误：${formatted}`, streaming: false }
            : m,
        ),
      )
      setStreaming(false)
    }
  }

  const stop = async () => {
    abortRef.current?.abort()
    if (sessionIdRef.current) {
      await cancelChat(kbId, sessionIdRef.current)
    }
    setStreaming(false)
  }

  return (
    <div className="flex min-w-0 gap-4">
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="mb-4 min-h-[400px] flex-1 space-y-4 overflow-y-auto overflow-x-hidden rounded-lg border bg-card p-4">
          {messages.length === 0 && (
            <p className="text-center text-sm text-muted-foreground">
              {canChat
                ? '向知识库提问，将基于已摄入的文档进行 RAG 问答'
                : '暂无已完成摄入的文档，请先在「文档管理」上传并等待处理完成'}
            </p>
          )}
          {messages.map((msg) => (
            <div
              key={msg.id}
              className={cn(
                'flex min-w-0',
                msg.role === 'user' ? 'justify-end' : 'justify-start',
              )}
            >
              <div
                className={cn(
                  'min-w-0 rounded-lg px-4 py-2 text-sm break-words [overflow-wrap:anywhere]',
                  msg.role === 'user' ? 'max-w-[85%]' : 'max-w-[90%]',
                  msg.role === 'user'
                    ? 'bg-primary text-primary-foreground'
                    : 'bg-muted',
                )}
              >
                {msg.role === 'user' ? (
                  <p className="whitespace-pre-wrap">{msg.content}</p>
                ) : (
                  <MarkdownContent content={msg.content} />
                )}
                {msg.streaming && (
                  <Loader2 className="mt-1 h-3 w-3 animate-spin opacity-60" />
                )}
              </div>
            </div>
          ))}
        </div>

        {!canChat && (
          <p className="mb-2 text-sm text-amber-600">
            需要至少 1 份已完成摄入的文档才能问答（当前 {completedDocCount} 份可用）
          </p>
        )}

        <div className="flex gap-2">
          <Textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder={canChat ? '输入你的问题…' : '请先上传文档并等待处理完成…'}
            rows={2}
            disabled={streaming || !canChat}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                send()
              }
            }}
          />
          {streaming ? (
            <Button variant="destructive" size="lg" onClick={stop}>
              <Square className="h-4 w-4" />
            </Button>
          ) : (
            <Button size="lg" onClick={send} disabled={!input.trim() || !canChat}>
              <Send className="h-4 w-4" />
            </Button>
          )}
        </div>
      </div>

      {citations.length > 0 && (
        <aside className="w-72 shrink-0 rounded-lg border bg-card p-4">
          <h3 className="mb-3 text-sm font-semibold">引用来源</h3>
          <ul className="space-y-3">
            {citations.map((c) => (
              <li key={c.chunkId} className="text-xs">
                <p className="font-medium text-primary">{c.fileName}</p>
                <p className="mt-1 text-muted-foreground line-clamp-3">{c.snippet}</p>
                <p className="mt-1 text-muted-foreground">相关度: {c.score.toFixed(3)}</p>
              </li>
            ))}
          </ul>
        </aside>
      )}
    </div>
  )
}
