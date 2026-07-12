import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it } from 'vitest'
import { MarkdownContent } from './MarkdownContent'

describe('MarkdownContent', () => {
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
  })

  it('renders compact markdown snippets for citation cards', () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    act(() => {
      root?.render(<MarkdownContent content={'**重点**\n- 第一条'} compact />)
    })

    expect(container.querySelector('strong')?.textContent).toBe('重点')
    expect(container.querySelector('li')?.textContent).toBe('第一条')
    expect(container.firstElementChild?.className).toContain('text-xs')
  })

  it('normalizes malformed numbered headings in persisted assistant messages', () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    act(() => {
      root?.render(
        <MarkdownContent
          content={[
            '根据现有知识库资料，可以回答「venv是什么」。',
            '',
            'venv 是 Python 内置的虚拟环境管理工具。',
            '',
            '## 3.4 venv 的优缺点 [4]',
            '',
            '**优点：**',
            '- 内置，无需额外安装',
          ].join('\n')}
        />,
      )
    })

    expect(container.querySelector('h3')?.textContent).toBe('venv 的优缺点 [4]')
    expect(container.textContent).not.toContain('3.4 venv 的优缺点')
    expect(container.querySelector('li')?.textContent).toBe('内置，无需额外安装')
  })

  it('renders fenced code blocks with black text and no inline code background', () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    act(() => {
      root?.render(<MarkdownContent content={'```bash\npython -m venv .venv\n```'} />)
    })

    const pre = container.querySelector('pre')
    const code = container.querySelector('pre code')

    expect(pre?.className).toContain('bg-slate-50')
    expect(code?.className).toContain('text-black')
    expect(code?.className).toContain('bg-transparent')
    expect(code?.className).toContain('p-0')
    expect(code?.className).not.toContain('font-medium')
    expect(code?.textContent).toContain('python -m venv .venv')
  })
})
