import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { X } from 'lucide-react'

interface Toast {
  id: number
  message: string
  type: 'error' | 'success'
}

interface ToastContextValue {
  showError: (message: string) => void
  showSuccess: (message: string) => void
}

const ToastContext = createContext<ToastContextValue | null>(null)

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])

  const addToast = useCallback((message: string, type: 'error' | 'success') => {
    const id = Date.now()
    setToasts((prev) => {
      const withoutDuplicate = prev.filter((toast) => !(toast.message === message && toast.type === type))
      return [...withoutDuplicate, { id, message, type }].slice(-4)
    })
    setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), 5000)
  }, [])

  const remove = (id: number) => setToasts((prev) => prev.filter((t) => t.id !== id))
  const contextValue = useMemo(
    () => ({
      showError: (message: string) => addToast(message, 'error'),
      showSuccess: (message: string) => addToast(message, 'success'),
    }),
    [addToast],
  )

  return (
    <ToastContext.Provider value={contextValue}>
      {children}
      <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2">
        {toasts.map((t) => (
          <div
            key={t.id}
            className={cn(
              'flex max-w-sm items-start gap-2 rounded-lg border px-4 py-3 text-sm shadow-lg',
              t.type === 'error' && 'border-red-200 bg-red-50 text-red-800',
              t.type === 'success' && 'border-emerald-200 bg-emerald-50 text-emerald-800',
            )}
          >
            <span className="flex-1">{t.message}</span>
            <button onClick={() => remove(t.id)} className="opacity-60 hover:opacity-100">
              <X className="h-4 w-4" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx
}
