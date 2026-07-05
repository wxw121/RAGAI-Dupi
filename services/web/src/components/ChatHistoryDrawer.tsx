import type { ReactNode } from 'react'
import { Menu, X } from 'lucide-react'
import { Button } from '@/components/ui/button'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  children: ReactNode
}

export function ChatHistoryDrawer({ open, onOpenChange, children }: Props) {
  return (
    <>
      <Button
        type="button"
        variant="outline"
        size="sm"
        onClick={() => onOpenChange(true)}
        className="md:hidden"
        aria-label="打开历史会话"
        title="历史会话"
      >
        <Menu className="h-4 w-4" />
        历史会话
      </Button>

      {open && (
        <div className="fixed inset-0 z-50 md:hidden">
          <button
            type="button"
            className="absolute inset-0 bg-background/80 backdrop-blur-sm"
            onClick={() => onOpenChange(false)}
            aria-label="关闭历史会话"
          />
          <div className="absolute inset-y-0 left-0 flex w-80 max-w-[85vw] flex-col border-r bg-background shadow-lg">
            <div className="flex items-center justify-between border-b px-3 py-2">
              <h2 className="text-sm font-semibold">历史会话</h2>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => onOpenChange(false)}
                className="h-8 w-8 px-0"
                aria-label="关闭历史会话"
                title="关闭"
              >
                <X className="h-4 w-4" />
              </Button>
            </div>
            <div className="min-h-0 flex-1 p-3">{children}</div>
          </div>
        </div>
      )}
    </>
  )
}
