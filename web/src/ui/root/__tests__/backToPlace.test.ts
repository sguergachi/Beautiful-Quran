import { describe, expect, it } from 'vitest'
import {
  BACK_TO_PLACE_DISMISS_MS,
  BACK_TO_PLACE_SETTLE_GRACE_MS,
  backToPlaceExpiresAt,
  backToPlaceLabel,
  concordanceReturnTarget,
  createBackToPlaceStore,
  noteUserMoved,
  rememberPlace,
} from '../backToPlace'

const target = { surahId: 2, ayah: 3, surahNameTransliteration: 'Al-Baqarah' }

describe('back to place', () => {
  it('labels the capsule', () => {
    expect(backToPlaceLabel(target)).toBe('Back to Al-Baqarah 2:3')
  })

  it('has no target for a jump onto the same verse', () => {
    expect(concordanceReturnTarget({ surahId: 2, ayah: 3 }, { surahId: 2, ayah: 3 }, 'x')).toBeNull()
    expect(concordanceReturnTarget({ surahId: 2, ayah: 3 }, { surahId: 4, ayah: 1 }, 'Al-Baqarah')).toEqual(target)
  })

  it('ignores the settle, then arms 30 s after the first hand move', () => {
    const remembered = rememberPlace(target, 1000)
    expect(noteUserMoved(remembered, 1500)).toBe(remembered)
    const at = 1000 + BACK_TO_PLACE_SETTLE_GRACE_MS + 1
    const armed = noteUserMoved(remembered, at)
    expect(backToPlaceExpiresAt(armed)).toBe(at + BACK_TO_PLACE_DISMISS_MS)
    expect(noteUserMoved(armed, 99_999)).toBe(armed)
  })

  it('store clears itself after the countdown', () => {
    let now = 0
    type Timer = { fn: () => void; at: number }
    const timers: Timer[] = []
    const store = createBackToPlaceStore({
      now: () => now,
      setTimer: (fn, ms) => {
        const t = { fn, at: now + ms }
        timers.push(t)
        return t
      },
      clearTimer: (h) => {
        const i = timers.indexOf(h as Timer)
        if (i >= 0) timers.splice(i, 1)
      },
      listenForUserMoves: () => () => {},
    })
    const run = (to: number) => {
      now = to
      for (const t of timers.filter((item) => item.at <= to)) {
        timers.splice(timers.indexOf(t), 1)
        t.fn()
      }
    }
    store.remember(target)
    run(BACK_TO_PLACE_SETTLE_GRACE_MS)
    store.userMoved()
    expect(store.getSnapshot().target).toEqual(target)
    run(BACK_TO_PLACE_SETTLE_GRACE_MS + BACK_TO_PLACE_DISMISS_MS)
    expect(store.getSnapshot().target).toBeNull()
  })
})
