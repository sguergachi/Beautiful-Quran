import { describe, expect, it } from 'vitest'
import { readerKeyboardAction, readerOwnsKeyboard } from '../keyboardNavigation'

describe('readerKeyboardAction', () => {
  it('steps and pages through ayahs with chapter bounds', () => {
    expect(readerKeyboardAction('ArrowUp', 1, 10)).toEqual({ type: 'jump', ayah: 1 })
    expect(readerKeyboardAction('ArrowDown', 4, 10)).toEqual({ type: 'jump', ayah: 5 })
    expect(readerKeyboardAction('PageUp', 3, 10)).toEqual({ type: 'jump', ayah: 1 })
    expect(readerKeyboardAction('PageDown', 8, 10)).toEqual({ type: 'jump', ayah: 10 })
    expect(readerKeyboardAction('Home', 7, 10)).toEqual({ type: 'jump', ayah: 1 })
    expect(readerKeyboardAction('End', 2, 10)).toEqual({ type: 'jump', ayah: 10 })
  })

  it('maps reader commands and leaves other keys alone', () => {
    expect(readerKeyboardAction(' ', 3, 10)).toEqual({ type: 'playPause' })
    expect(readerKeyboardAction('/', 3, 10)).toEqual({ type: 'search' })
    expect(readerKeyboardAction('b', 3, 10)).toEqual({ type: 'bookmark' })
    expect(readerKeyboardAction('Tab', 3, 10)).toBeNull()
  })
})

describe('reader keyboard ownership', () => {
  it('yields when hidden or when the root viewer owns the surface', () => {
    expect(readerOwnsKeyboard(false, false)).toBe(false)
    expect(readerOwnsKeyboard(true, true)).toBe(false)
    expect(readerOwnsKeyboard(false, true)).toBe(false)
    expect(readerOwnsKeyboard(true, false)).toBe(true)
  })
})
