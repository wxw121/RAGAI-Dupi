import type { StructuredChatError } from '@/types'

interface ChatErrorNoticeProps {
  error: StructuredChatError
}

export function ChatErrorNotice({ error }: ChatErrorNoticeProps) {
  return (
    <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm leading-6 text-red-800">
      <p className="font-medium">{error.message}</p>
      <div className="mt-1 space-y-0.5 text-xs">
        {error.stage && <p>stage: {error.stage}</p>}
        {error.suggestion && <p>{error.suggestion}</p>}
        {error.requestId && <p>requestId: {error.requestId}</p>}
      </div>
    </div>
  )
}
