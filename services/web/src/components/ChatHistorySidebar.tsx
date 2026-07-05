import { useState, type MouseEvent } from 'react'
import { Check, Edit2, Plus, Trash2, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import type { ChatSession } from '@/types'

interface Props {
  sessions: ChatSession[]
  activeSessionId: string | null
  streaming: boolean
  onNewSession: () => void
  onSelectSession: (sessionId: string) => void
  onRenameSession: (sessionId: string, title: string) => Promise<void>
  onDeleteSession: (sessionId: string) => Promise<void>
  onBatchDelete: (sessionIds: string[]) => Promise<void>
}

export function ChatHistorySidebar({
  sessions,
  activeSessionId,
  streaming,
  onNewSession,
  onSelectSession,
  onRenameSession,
  onDeleteSession,
  onBatchDelete,
}: Props) {
  const [selectionMode, setSelectionMode] = useState(false)
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [editingId, setEditingId] = useState<string | null>(null)
  const [draftTitle, setDraftTitle] = useState('')

  const stopClick = (event: MouseEvent) => {
    event.stopPropagation()
  }

  const toggleSelectionMode = () => {
    setSelectionMode((enabled) => {
      if (enabled) {
        setSelectedIds([])
      }
      return !enabled
    })
    setEditingId(null)
  }

  const toggleSelected = (sessionId: string) => {
    setSelectedIds((ids) =>
      ids.includes(sessionId) ? ids.filter((id) => id !== sessionId) : [...ids, sessionId],
    )
  }

  const handleSessionClick = (sessionId: string) => {
    if (selectionMode) {
      toggleSelected(sessionId)
      return
    }
    if (streaming) return
    onSelectSession(sessionId)
  }

  const startEditing = (session: ChatSession) => {
    setEditingId(session.id)
    setDraftTitle(session.title)
  }

  const cancelEditing = () => {
    setEditingId(null)
    setDraftTitle('')
  }

  const saveTitle = async (sessionId: string) => {
    const nextTitle = draftTitle.trim()
    if (!nextTitle) return

    await onRenameSession(sessionId, nextTitle)
    cancelEditing()
  }

  const deleteSession = async (sessionId: string) => {
    if (!confirm('确定删除该会话吗？此操作不可恢复。')) return

    await onDeleteSession(sessionId)
    setSelectedIds((ids) => ids.filter((id) => id !== sessionId))
    if (editingId === sessionId) {
      cancelEditing()
    }
  }

  const deleteSelected = async () => {
    if (selectedIds.length === 0) return
    if (!confirm(`确定删除选中的 ${selectedIds.length} 个会话吗？此操作不可恢复。`)) return

    await onBatchDelete(selectedIds)
    setSelectedIds([])
    setSelectionMode(false)
  }

  return (
    <aside className="flex h-full min-h-0 flex-col rounded-lg border bg-card">
      <div className="flex items-center justify-between gap-2 border-b px-3 py-2">
        <h2 className="text-sm font-semibold">历史会话</h2>
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={onNewSession}
          disabled={streaming}
          title="新建会话"
          aria-label="新建会话"
          className="h-8 px-2"
        >
          <Plus className="h-4 w-4" />
        </Button>
      </div>

      <div className="flex items-center gap-2 border-b px-3 py-2">
        <Button
          type="button"
          size="sm"
          variant={selectionMode ? 'secondary' : 'ghost'}
          onClick={toggleSelectionMode}
          className="h-8 flex-1"
        >
          {selectionMode ? '取消选择' : '批量选择'}
        </Button>
        {selectionMode && (
          <Button
            type="button"
            size="sm"
            variant="destructive"
            onClick={deleteSelected}
            disabled={selectedIds.length === 0}
            className="h-8"
          >
            删除{selectedIds.length > 0 ? ` ${selectedIds.length}` : ''}
          </Button>
        )}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto p-2">
        {sessions.length === 0 ? (
          <p className="px-2 py-6 text-center text-sm text-muted-foreground">暂无历史会话</p>
        ) : (
          <ul className="space-y-1">
            {sessions.map((session) => {
              const isActive = session.id === activeSessionId
              const isEditing = session.id === editingId
              const isSelected = selectedIds.includes(session.id)

              return (
                <li key={session.id}>
                  <div
                    role="button"
                    tabIndex={streaming && !selectionMode ? -1 : 0}
                    aria-disabled={streaming && !selectionMode}
                    onClick={() => handleSessionClick(session.id)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault()
                        handleSessionClick(session.id)
                      }
                    }}
                    className={cn(
                      'group flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-2 text-left text-sm transition-colors',
                      'hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                      isActive && !selectionMode && 'bg-muted font-medium text-primary',
                      isSelected && 'bg-primary/10 text-primary',
                      streaming && !selectionMode && 'cursor-not-allowed opacity-60',
                    )}
                  >
                    {selectionMode && (
                      <span
                        className={cn(
                          'flex h-4 w-4 shrink-0 items-center justify-center rounded border',
                          isSelected
                            ? 'border-primary bg-primary text-primary-foreground'
                            : 'border-input bg-background',
                        )}
                        aria-hidden="true"
                      >
                        {isSelected && <Check className="h-3 w-3" />}
                      </span>
                    )}

                    {isEditing ? (
                      <form
                        className="flex min-w-0 flex-1 items-center gap-1"
                        onClick={stopClick}
                        onSubmit={(event) => {
                          event.preventDefault()
                          saveTitle(session.id)
                        }}
                      >
                        <Input
                          value={draftTitle}
                          onChange={(event) => setDraftTitle(event.target.value)}
                          onKeyDown={(event) => {
                            if (event.key === 'Escape') {
                              event.preventDefault()
                              cancelEditing()
                            }
                          }}
                          autoFocus
                          className="h-8 min-w-0"
                          aria-label="会话标题"
                        />
                        <Button
                          type="submit"
                          size="sm"
                          variant="ghost"
                          className="h-8 w-8 shrink-0 px-0"
                          title="保存标题"
                          aria-label="保存标题"
                          disabled={!draftTitle.trim()}
                        >
                          <Check className="h-4 w-4" />
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          variant="ghost"
                          onClick={cancelEditing}
                          className="h-8 w-8 shrink-0 px-0"
                          title="取消编辑"
                          aria-label="取消编辑"
                        >
                          <X className="h-4 w-4" />
                        </Button>
                      </form>
                    ) : (
                      <>
                        <span className="min-w-0 flex-1 truncate">{session.title || '未命名会话'}</span>
                        {!selectionMode && (
                          <span className="flex shrink-0 items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100">
                            <Button
                              type="button"
                              size="sm"
                              variant="ghost"
                              onClick={(event) => {
                                stopClick(event)
                                startEditing(session)
                              }}
                              className="h-7 w-7 px-0"
                              title="重命名"
                              aria-label="重命名"
                            >
                              <Edit2 className="h-3.5 w-3.5" />
                            </Button>
                            <Button
                              type="button"
                              size="sm"
                              variant="ghost"
                              onClick={(event) => {
                                stopClick(event)
                                deleteSession(session.id)
                              }}
                              className="h-7 w-7 px-0 text-destructive hover:text-destructive"
                              title="删除会话"
                              aria-label="删除会话"
                            >
                              <Trash2 className="h-3.5 w-3.5" />
                            </Button>
                          </span>
                        )}
                      </>
                    )}
                  </div>
                </li>
              )
            })}
          </ul>
        )}
      </div>
    </aside>
  )
}
