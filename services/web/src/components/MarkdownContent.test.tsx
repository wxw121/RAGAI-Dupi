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
})
