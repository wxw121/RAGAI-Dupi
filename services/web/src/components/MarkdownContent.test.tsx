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

  it('renders a relative Markdown image as an image element', () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    act(() => {
      root?.render(
        <MarkdownContent content={'![异步编程四大核心](/api/v1/documents/image.png)'} />,
      )
    })

    const image = container.querySelector('img')
    expect(image?.getAttribute('src')).toBe('/api/v1/documents/image.png')
    expect(image?.getAttribute('alt')).toBe('异步编程四大核心')
    expect(image?.getAttribute('loading')).toBe('lazy')
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

  it('renders REST acronym expansion without exposing bold markers', () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    act(() => {
      root?.render(
        <MarkdownContent
          content={[
            '## REST 的核心思想',
            '',
            '**REST** = **RE**presentational **S**tate **T**ransfer（表述性状态转移）[4]',
            '',
            '核心思想：**把服务器上的一切都看作「资源」。用 URL 定位资源，用 HTTP 方法操作资源。每个资源有唯一地址，你通过这个地址访问和修改它的「表述」（representation）**[4]。',
            '',
            '- **representation**（表述）：资源当前呈现给客户端的样子。',
          ].join('\n')}
        />,
      )
    })

    expect(container.textContent).not.toContain('**')
    expect(container.textContent).toContain('REST = REpresentational State Transfer（表述性状态转移）[4]')
    expect([...container.querySelectorAll('strong')].map((node) => node.textContent)).toEqual(
      expect.arrayContaining(['REST', 'REpresentational', 'State', 'Transfer', 'representation']),
    )
  })
})
