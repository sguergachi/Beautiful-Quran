import { describe, expect, it } from 'vitest'
import {
  HOLD_IDLE,
  MARK_TAP_TEASE,
  SHAMSA_RAY_COUNT,
  markFrame,
  markTapTeasePeak,
  nextHoldStep,
  shamsaExpansion,
  shamsaRayLook,
  shamsaRays,
} from '../markHoldShamsa'

describe('mark hold shamsa', () => {
  it('a lift while charging is the lesson, a slide retracts', () => {
    const [charging, start] = nextHoldStep(HOLD_IDLE, 'down')
    expect(start).toBe('startCharge')
    expect(nextHoldStep(charging, 'released')[1]).toBe('startLesson')
    expect(nextHoldStep(charging, 'cancelled')[1]).toBe('retract')
  })

  it('a press during the burst is ignored along with its lift', () => {
    const popped = { phase: 'popped' as const, ignoringPress: false }
    const [ignoring, action] = nextHoldStep(popped, 'down')
    expect(action).toBe('ignore')
    expect(ignoring.ignoringPress).toBe(true)
    const [after, lift] = nextHoldStep(ignoring, 'released')
    expect(lift).toBe('ignore')
    expect(after.ignoringPress).toBe(false)
    expect(nextHoldStep(popped, 'released')[1]).toBe('ignore')
  })

  it('sixteen rays, never six-fold, alternating long and short', () => {
    const rays = shamsaRays({ cx: 0, cy: 0, rx: 10, ry: 8 }, 2, 5, 3, 1, true)
    expect(rays).toHaveLength(SHAMSA_RAY_COUNT)
    expect(SHAMSA_RAY_COUNT % 6).not.toBe(0)
    expect(rays[0]!.length).toBe(5)
    expect(rays[1]!.length).toBe(3)
    expect(rays[0]!.dirY).toBeCloseTo(-1)
  })

  it('the tease never takes the ring back', () => {
    expect(markTapTeasePeak(0.1)).toBe(MARK_TAP_TEASE)
    expect(markTapTeasePeak(0.8)).toBe(0.8)
  })

  it('expansion is complete by 45% of the hold', () => {
    expect(shamsaExpansion(0)).toBe(0)
    expect(shamsaExpansion(0.45)).toBeCloseTo(1)
  })

  it('rays fade out entirely by the end of the burst', () => {
    expect(shamsaRayLook(0, 16, 1, 1).alpha).toBeCloseTo(0)
    expect(shamsaRayLook(0, 16, 1, 0).alpha).toBeGreaterThan(0.8)
  })

  it('sits the frame on the cups when the baseline is known', () => {
    const frame = markFrame({ left: 0, top: 0, width: 20, height: 40 }, 30, 9)
    expect(frame.cx).toBe(10)
    expect(frame.cy).toBeCloseTo(30 - 0.105 * 20)
  })
})
