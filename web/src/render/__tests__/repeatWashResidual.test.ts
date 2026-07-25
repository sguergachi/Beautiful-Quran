import { describe, expect, it } from 'vitest'
import { hasRepeatWashProgress, runRepeatResidualAsync } from '../inkWash'

/** Minimal element stand-in (no Motion / WAAPI). */
function fakeEl(): HTMLElement {
  const style: Record<string, string> = {}
  return {
    style: {
      setProperty: (k: string, v: string) => {
        style[k] = v
      },
      removeProperty: (k: string) => {
        delete style[k]
      },
      get opacity() {
        return style.opacity ?? ''
      },
      set opacity(v: string) {
        style.opacity = v
      },
      get maskImage() {
        return style['mask-image'] ?? ''
      },
      get webkitMaskImage() {
        return style['-webkit-mask-image'] ?? ''
      },
    },
    classList: {
      add: () => {},
      remove: () => {},
      contains: () => false,
    },
    removeAttribute: () => {},
  } as unknown as HTMLElement
}

describe('repeat residual never-started', () => {
  it('has no progress until a wash starts', () => {
    expect(hasRepeatWashProgress(fakeEl())).toBe(false)
  })

  it('residual is a no-op when the word never washed (no orange bloom)', async () => {
    const el = fakeEl()
    await runRepeatResidualAsync(el, true)
    expect(hasRepeatWashProgress(el)).toBe(false)
    expect(el.style.opacity).toBe('')
  })
})
