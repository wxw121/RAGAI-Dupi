import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ChatPanel } from './ChatPanel'

vi.mock('@/api/chatSessions', () => ({
  batchDeleteChatSessions: vi.fn(),
  deleteChatSession: vi.fn(),
  getChatSession: vi.fn().mockResolvedValue({
    session: {
      id: 'session-1',
      kbId: 'kb-1',
      title: '什么是虚拟环境',
      createdAt: '2026-07-12T00:00:00Z',
      updatedAt: '2026-07-12T00:00:00Z',
    },
    messages: [
      {
        id: 'message-1',
        sessionId: 'session-1',
        sequenceNumber: 1,
        role: 'USER',
        content: '什么是虚拟环境',
        citations: [],
        createdAt: '2026-07-12T00:00:00Z',
      },
      {
        id: 'message-2',
        sessionId: 'session-1',
        sequenceNumber: 2,
        role: 'ASSISTANT',
        content: '回答内容',
        citations: [
          {
            chunkId: 'chunk-1',
            docId: 'doc-1',
            fileName: 'python-virtual-env-tutorial.md',
            snippet: '引用原文内容',
            score: 0.9,
          },
        ],
        createdAt: '2026-07-12T00:00:00Z',
      },
    ],
  }),
  listChatSessions: vi.fn().mockResolvedValue([
    {
      id: 'session-1',
      kbId: 'kb-1',
      title: '什么是虚拟环境',
      createdAt: '2026-07-12T00:00:00Z',
      updatedAt: '2026-07-12T00:00:00Z',
    },
  ]),
  renameChatSession: vi.fn(),
}))

vi.mock('@/api/chat', () => ({
  cancelChat: vi.fn(),
  streamChat: vi.fn(async (_kbId, _query, callbacks) => {
    callbacks.onRetrieval?.(
      [
        {
          chunkId: 'chunk-1',
          docId: 'doc-1',
          fileName: 'python-virtual-env-tutorial.md',
          snippet: '引用原文内容',
          score: 0.9,
        },
      ],
      {
        retrievalMode: 'local_text_fallback',
        topK: 5,
        hitCount: 1,
        fallbackReason: 'milvus_unavailable',
      },
    )
    callbacks.onToken?.('回答内容')
    callbacks.onDone?.('session-1')
  }),
}))

vi.mock('@/components/Toast', () => ({
  useToast: () => ({ showError: vi.fn() }),
}))

describe('ChatPanel', () => {
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

  it('renders citation cards in the scrollable answer area instead of the bottom composer overlay', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    await act(async () => {
      root?.render(<ChatPanel kbId="kb-1" completedDocCount={1} />)
    })

    await act(async () => {
      await Promise.resolve()
    })

    const citationRegion = container.querySelector('[data-testid="chat-citation-strip"]')
    const composer = container.querySelector('[data-testid="chat-composer"]')

    expect(citationRegion).not.toBeNull()
    expect(composer).not.toBeNull()
    expect(composer!.contains(citationRegion)).toBe(false)
  })
})
