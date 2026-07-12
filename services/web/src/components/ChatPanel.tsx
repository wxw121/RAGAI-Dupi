import { useCallback, useEffect, useRef, useState } from 'react'
import { cancelChat, streamChat } from '@/api/chat'
import {
  batchDeleteChatSessions,
  deleteChatSession,
  getChatSession,
  listChatSessions,
  renameChatSession,
} from '@/api/chatSessions'
import type { ChatMessage, ChatSession, Citation, RetrievalDiagnostics } from '@/types'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { useToast } from '@/components/Toast'
import { Loader2, Send, Square } from 'lucide-react'
import { cn } from '@/lib/utils'
import { MarkdownContent } from '@/components/MarkdownContent'
import { ChatHistorySidebar } from '@/components/ChatHistorySidebar'
import { ChatHistoryDrawer } from '@/components/ChatHistoryDrawer'

interface ChatPanelProps {
  kbId: string
  completedDocCount: number
}

function formatChatError(message: string): string {
  if (!message.trim()) {
    return '问答请求失败，请稍后重试'
  }
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
  const [retrievalDiagnostics, setRetrievalDiagnostics] =
    useState<RetrievalDiagnostics | null>(null)
  const [sessions, setSessions] = useState<ChatSession[]>([])
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  const messagesRef = useRef<ChatMessage[]>([])
  const streamingRef = useRef(false)
  const sessionIdRef = useRef<string | null>(null)
  const kbIdRef = useRef(kbId)
  const abortRef = useRef<AbortController | null>(null)
  const detailRequestIdRef = useRef(0)
  const streamRunIdRef = useRef(0)
  const { showError } = useToast()

  const canChat = completedDocCount > 0

  useEffect(() => {
    messagesRef.current = messages
  }, [messages])

  useEffect(() => {
    streamingRef.current = streaming
  }, [streaming])

  useEffect(() => {
    kbIdRef.current = kbId
  }, [kbId])

  const loadSessionDetail = useCallback(
    async (sessionId: string) => {
      const requestId = detailRequestIdRef.current + 1
      detailRequestIdRef.current = requestId
      const requestedKbId = kbId
      const requestedSessionId = sessionId
      const detail = await getChatSession(kbId, sessionId)
      if (
        detailRequestIdRef.current !== requestId ||
        requestedKbId !== kbIdRef.current ||
        requestedSessionId !== sessionId
      ) {
        return false
      }
      const persistedMessages: ChatMessage[] = detail.messages.map((message) => ({
        id: message.id,
        role: message.role === 'USER' ? 'user' : 'assistant',
        content: message.content,
        citations: message.citations,
        streaming: false,
      }))
      const lastAssistant = [...detail.messages]
        .reverse()
        .find((message) => message.role === 'ASSISTANT' || message.role === 'SYSTEM')

      setActiveSessionId(sessionId)
      sessionIdRef.current = sessionId
      setMessages(persistedMessages)
      setCitations(lastAssistant?.citations ?? [])
      setRetrievalDiagnostics(null)
      return true
    },
    [kbId],
  )

  useEffect(() => {
    let cancelled = false

    async function loadSessions() {
      try {
        const nextSessions = await listChatSessions(kbId)
        if (cancelled) return
        setSessions(nextSessions)

        if (
          !sessionIdRef.current &&
          nextSessions.length > 0 &&
          messagesRef.current.length === 0 &&
          !streamingRef.current
        ) {
          await loadSessionDetail(nextSessions[0].id)
        }
      } catch (e) {
        if (!cancelled) {
          showError(e instanceof Error ? e.message : '加载历史会话失败')
        }
      }
    }

    setHistoryOpen(false)
    loadSessions()

    return () => {
      cancelled = true
    }
  }, [kbId, loadSessionDetail, showError])

  const startNewSession = useCallback(() => {
    if (streamingRef.current) return
    setActiveSessionId(null)
    sessionIdRef.current = null
    setMessages([])
    setCitations([])
    setRetrievalDiagnostics(null)
  }, [])

  const selectSession = useCallback(
    async (sessionId: string) => {
      if (streamingRef.current) {
        showError('当前回答仍在生成中，请先停止后再切换会话')
        return
      }

      try {
        const loaded = await loadSessionDetail(sessionId)
        if (loaded) {
          setHistoryOpen(false)
        }
      } catch (e) {
        showError(e instanceof Error ? e.message : '加载历史会话失败')
      }
    },
    [loadSessionDetail, showError],
  )

  const renameSession = useCallback(
    async (sessionId: string, title: string) => {
      if (streamingRef.current) {
        showError('当前回答仍在生成中，请先停止后再重命名会话')
        return
      }

      const nextTitle = title.trim()
      if (!nextTitle) {
        showError('会话标题不能为空')
        return
      }

      try {
        const updated = await renameChatSession(kbId, sessionId, nextTitle)
        setSessions((prev) =>
          prev.map((session) => (session.id === sessionId ? updated : session)),
        )
      } catch (e) {
        showError(e instanceof Error ? e.message : '重命名会话失败')
      }
    },
    [kbId, showError],
  )

  const loadAfterDelete = useCallback(
    async (nextSessions: ChatSession[]) => {
      const nextSession = nextSessions[0]
      if (nextSession) {
        await loadSessionDetail(nextSession.id)
        return
      }
      startNewSession()
    },
    [loadSessionDetail, startNewSession],
  )

  const removeSession = useCallback(
    async (sessionId: string) => {
      if (streamingRef.current) {
        showError('当前回答仍在生成中，请先停止后再删除会话')
        return
      }

      try {
        await deleteChatSession(kbId, sessionId)
        const nextSessions = sessions.filter((session) => session.id !== sessionId)
        setSessions(nextSessions)

        if (activeSessionId === sessionId) {
          await loadAfterDelete(nextSessions)
        }
      } catch (e) {
        showError(e instanceof Error ? e.message : '删除会话失败')
      }
    },
    [activeSessionId, kbId, loadAfterDelete, sessions, showError],
  )

  const removeSessions = useCallback(
    async (sessionIds: string[]) => {
      if (sessionIds.length === 0) return
      if (streamingRef.current) {
        showError('当前回答仍在生成中，请先停止后再批量删除会话')
        return
      }

      try {
        await batchDeleteChatSessions(kbId, sessionIds)
        const deletedIds = new Set(sessionIds)
        const nextSessions = sessions.filter((session) => !deletedIds.has(session.id))
        setSessions(nextSessions)

        if (activeSessionId && deletedIds.has(activeSessionId)) {
          await loadAfterDelete(nextSessions)
        }
      } catch (e) {
        showError(e instanceof Error ? e.message : '批量删除会话失败')
      }
    },
    [activeSessionId, kbId, loadAfterDelete, sessions, showError],
  )

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
    setRetrievalDiagnostics(null)
    detailRequestIdRef.current += 1

    const controller = new AbortController()
    abortRef.current = controller
    const runId = streamRunIdRef.current + 1
    streamRunIdRef.current = runId
    const isCurrentStream = () => streamRunIdRef.current === runId
    const sessionId = activeSessionId
    sessionIdRef.current = sessionId

    try {
      await streamChat(
        kbId,
        query,
        {
          onRetrieval: (cits, diagnostics) => {
            if (!isCurrentStream()) return
            setCitations(cits)
            setRetrievalDiagnostics(diagnostics ?? null)
          },
          onToken: (token) => {
            if (!isCurrentStream()) return
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantId ? { ...m, content: m.content + token } : m,
              ),
            )
          },
          onDone: (sid) => {
            if (!isCurrentStream()) return
            const resolvedSessionId = sid || sessionId
            if (resolvedSessionId) {
              sessionIdRef.current = resolvedSessionId
              setActiveSessionId(resolvedSessionId)
              listChatSessions(kbId)
                .then((nextSessions) => {
                  if (!isCurrentStream()) return
                  setSessions(nextSessions)
                })
                .catch((e) => {
                  if (!isCurrentStream()) return
                  showError(e instanceof Error ? e.message : '刷新历史会话失败')
                })
            }
            setMessages((prev) =>
              prev.map((m) => (m.id === assistantId ? { ...m, streaming: false } : m)),
            )
            setStreaming(false)
          },
          onError: (msg) => {
            if (!isCurrentStream()) return
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
            if (!isCurrentStream()) return
            setMessages((prev) =>
              prev.map((m) => (m.id === assistantId ? { ...m, streaming: false } : m)),
            )
            setStreaming(false)
          },
        },
        controller.signal,
        sessionId ?? undefined,
      )
    } catch (e) {
      if (!isCurrentStream()) return
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
    streamRunIdRef.current += 1
    abortRef.current?.abort()
    if (sessionIdRef.current) {
      await cancelChat(kbId, sessionIdRef.current)
    }
    setMessages((prev) =>
      prev.map((message) =>
        message.streaming ? { ...message, streaming: false } : message,
      ),
    )
    setStreaming(false)
  }

  const history = (
    <ChatHistorySidebar
      sessions={sessions}
      activeSessionId={activeSessionId}
      streaming={streaming}
      onNewSession={startNewSession}
      onSelectSession={selectSession}
      onRenameSession={renameSession}
      onDeleteSession={removeSession}
      onBatchDelete={removeSessions}
    />
  )

  return (
    <div className="flex h-[calc(100vh-73px)] min-h-[560px] bg-background">
      <div className="hidden w-[260px] shrink-0 border-r border-border bg-[#f9f9f9] px-2 py-3 md:block">
        {history}
      </div>

      <section className="relative flex min-w-0 flex-1 flex-col">
        <div className="border-b border-border px-3 py-2 md:hidden">
          <ChatHistoryDrawer open={historyOpen} onOpenChange={setHistoryOpen}>
            {history}
          </ChatHistoryDrawer>
        </div>

        <div className="chatgpt-scrollbar min-h-0 flex-1 overflow-y-auto overflow-x-hidden px-4">
          <div className="mx-auto flex min-h-full max-w-3xl flex-col py-8 pb-44">
            {messages.length === 0 ? (
              <div className="flex flex-1 flex-col items-center justify-center text-center">
                <h2 className="text-2xl font-semibold tracking-tight md:text-3xl">
                  {canChat ? '今天想了解什么？' : '先上传文档，再开始问答'}
                </h2>
                <p className="mt-3 max-w-md text-sm leading-6 text-muted-foreground">
                  {canChat
                    ? '向知识库提问，系统会基于已摄入文档检索上下文并生成回答。'
                    : `当前有 ${completedDocCount} 份可用文档，请先在“文档”中上传并等待处理完成。`}
                </p>
              </div>
            ) : (
              <div className="space-y-7">
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
                        'min-w-0 text-[15px] leading-7 break-words [overflow-wrap:anywhere]',
                        msg.role === 'user'
                          ? 'max-w-[80%] rounded-3xl bg-muted px-5 py-2.5 text-foreground'
                          : 'w-full max-w-none text-foreground',
                      )}
                    >
                      {msg.role === 'user' ? (
                        <p className="whitespace-pre-wrap">{msg.content}</p>
                      ) : (
                        <div className="rounded-none bg-transparent">
                          <MarkdownContent content={msg.content} />
                        </div>
                      )}
                      {msg.streaming && (
                        <Loader2 className="mt-2 h-4 w-4 animate-spin opacity-60" />
                      )}
                    </div>
                  </div>
                ))}

                {citations.length > 0 && (
                  <div
                    className="chatgpt-scrollbar flex items-start gap-2 overflow-x-auto pb-2 pt-1"
                    data-testid="chat-citation-strip"
                    aria-label="引用来源"
                  >
                    {citations.slice(0, 5).map((c) => (
                      <div
                        key={c.chunkId}
                        className="h-28 w-52 shrink-0 overflow-hidden rounded-2xl border border-border bg-background px-3 py-2 text-xs shadow-sm"
                        title={c.snippet}
                      >
                        <p className="truncate font-medium">{c.fileName}</p>
                        <div className="mt-1 line-clamp-4 text-muted-foreground">
                          <MarkdownContent content={c.snippet} compact />
                        </div>
                      </div>
                    ))}
                  </div>
                )}

                {retrievalDiagnostics && (
                  <p className="text-center text-xs text-muted-foreground">
                    检索诊断：{retrievalDiagnostics.retrievalMode ?? 'unknown'} / 命中{' '}
                    {retrievalDiagnostics.hitCount ?? 0}
                    {retrievalDiagnostics.topK
                      ? ` / TopK ${retrievalDiagnostics.topK}`
                      : ''}
                    {retrievalDiagnostics.fallbackReason
                      ? ` / fallback: ${retrievalDiagnostics.fallbackReason}`
                      : ''}
                  </p>
                )}
              </div>
            )}
          </div>
        </div>

        <div
          className="pointer-events-none absolute inset-x-0 bottom-0 bg-gradient-to-t from-background via-background to-transparent px-4 pb-4 pt-10"
          data-testid="chat-composer"
        >
          <div className="pointer-events-auto mx-auto max-w-3xl">
            {!canChat && (
              <p className="mb-2 text-center text-sm text-amber-700">
                需要至少 1 份已完成摄入的文档才能问答（当前 {completedDocCount} 份可用）
              </p>
            )}

            <div className="flex items-end gap-2 rounded-[28px] border border-border bg-background p-2 shadow-[0_12px_32px_rgba(0,0,0,0.10)]">
              <Textarea
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder={canChat ? '询问知识库…' : '请先上传文档并等待处理完成…'}
                rows={1}
                disabled={streaming || !canChat}
                className="max-h-40 min-h-[44px] resize-none border-0 bg-transparent px-3 py-3 shadow-none focus-visible:ring-0"
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault()
                    send()
                  }
                }}
              />
              {streaming ? (
                <Button
                  variant="destructive"
                  size="lg"
                  onClick={stop}
                  className="h-10 w-10 shrink-0 rounded-full px-0"
                  aria-label="停止生成"
                  title="停止生成"
                >
                  <Square className="h-4 w-4" />
                </Button>
              ) : (
                <Button
                  size="lg"
                  onClick={send}
                  disabled={!input.trim() || !canChat}
                  className="h-10 w-10 shrink-0 rounded-full px-0"
                  aria-label="发送"
                  title="发送"
                >
                  <Send className="h-4 w-4" />
                </Button>
              )}
            </div>
            <p className="mt-2 text-center text-xs text-muted-foreground">
              回答基于当前知识库文档生成，请核对引用来源。
            </p>
          </div>
        </div>
      </section>
    </div>
  )
}
