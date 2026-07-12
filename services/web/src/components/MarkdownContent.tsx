import type { Components } from 'react-markdown'
import ReactMarkdown from 'react-markdown'
import remarkBreaks from 'remark-breaks'
import remarkGfm from 'remark-gfm'
import { normalizeMarkdown } from '@/lib/normalizeMarkdown'

interface MarkdownContentProps {
  content: string
  compact?: boolean
}

const markdownComponents: Components = {
  h1: ({ children }) => (
    <h3 className="mb-2 mt-4 text-base font-semibold">{children}</h3>
  ),
  h2: ({ children }) => (
    <h3 className="mb-2 mt-4 text-base font-semibold">{children}</h3>
  ),
  h3: ({ children }) => (
    <h4 className="mb-1 mt-3 text-sm font-semibold">{children}</h4>
  ),
  h4: ({ children }) => (
    <h5 className="mb-1 mt-2 text-sm font-medium">{children}</h5>
  ),
  strong: ({ children }) => <strong className="font-semibold">{children}</strong>,
  a: ({ href, children }) => (
    <a href={href} target="_blank" rel="noopener noreferrer" className="break-words">
      {children}
    </a>
  ),
  p: ({ children }) => <p className="break-words [overflow-wrap:anywhere]">{children}</p>,
  li: ({ children }) => <li className="break-words [overflow-wrap:anywhere]">{children}</li>,
  td: ({ children }) => <td className="break-words [overflow-wrap:anywhere]">{children}</td>,
  th: ({ children }) => <th className="break-words [overflow-wrap:anywhere]">{children}</th>,
  pre: ({ children }) => (
    <pre className="overflow-x-auto whitespace-pre-wrap break-words [overflow-wrap:anywhere]">
      {children}
    </pre>
  ),
  code: ({ className, children }) => {
    const isBlock = Boolean(className)
    if (isBlock) {
      return <code className={className}>{children}</code>
    }
    return (
      <code className="break-words [overflow-wrap:anywhere] whitespace-pre-wrap">{children}</code>
    )
  },
}

export function MarkdownContent({ content, compact = false }: MarkdownContentProps) {
  if (!content) return null

  const normalized = normalizeMarkdown(content)
  const className = compact
    ? `w-full min-w-0 max-w-full break-words [overflow-wrap:anywhere]
        prose prose-sm max-w-full text-xs
        prose-headings:my-1 prose-headings:text-xs prose-headings:font-semibold
        prose-p:my-0 prose-p:text-xs prose-ul:my-0 prose-ol:my-0 prose-li:my-0
        prose-table:my-1 prose-table:text-xs prose-th:px-1 prose-th:py-0.5 prose-td:px-1 prose-td:py-0.5
        prose-pre:my-1 prose-pre:bg-slate-900 prose-pre:text-slate-100
        prose-code:before:content-none prose-code:after:content-none
        prose-code:bg-slate-200/60 prose-code:px-1 prose-code:rounded`
    : `w-full min-w-0 max-w-full break-words [overflow-wrap:anywhere]
        prose prose-sm max-w-full
        prose-headings:font-semibold prose-headings:break-words
        prose-p:my-2 prose-p:text-sm prose-ul:my-2 prose-ol:my-2 prose-li:my-0.5
        prose-table:my-3 prose-table:text-xs prose-th:px-2 prose-th:py-1 prose-td:px-2 prose-td:py-1
        prose-pre:my-3 prose-pre:bg-slate-900 prose-pre:text-slate-100
        prose-code:before:content-none prose-code:after:content-none
        prose-code:bg-slate-200/60 prose-code:px-1 prose-code:rounded`

  return (
    <div className={className}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkBreaks]}
        components={markdownComponents}
      >
        {normalized}
      </ReactMarkdown>
    </div>
  )
}
