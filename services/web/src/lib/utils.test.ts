import { describe, expect, it } from 'vitest'
import { cn, formatBytes, formatDate } from './utils'

describe('utils', () => {
  it('merges clsx and tailwind classes with last conflicting class winning', () => {
    expect(cn('px-2', false && 'hidden', ['px-4', 'text-sm'])).toBe('px-4 text-sm')
  })

  it('formats bytes using readable units', () => {
    expect(formatBytes(42)).toBe('42 B')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(2 * 1024 * 1024)).toBe('2.0 MB')
  })

  it('formats ISO date with Chinese locale', () => {
    expect(formatDate('2026-07-03T00:00:00.000Z')).toContain('2026')
  })
})
