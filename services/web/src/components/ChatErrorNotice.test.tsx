import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it } from 'vitest'
import { ChatErrorNotice } from './ChatErrorNotice'

describe('ChatErrorNotice', () => {
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

  it('renders structured stage suggestion and request id', () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)

    act(() => {
      root?.render(
        <ChatErrorNotice
          error={{
            error: 'chat_pipeline_error',
            message: 'llm down',
            stage: 'llm',
            suggestion: 'Check CHAT_API_KEY',
            requestId: 'req-1',
          }}
        />,
      )
    })

    expect(container.textContent).toContain('llm down')
    expect(container.textContent).toContain('llm')
    expect(container.textContent).toContain('Check CHAT_API_KEY')
    expect(container.textContent).toContain('req-1')
  })
})
