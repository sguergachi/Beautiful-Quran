import { describe, expect, it } from 'vitest'
import {
  EMPTY_PULL,
  nextChapterPullOffset,
  nextInviteAlpha,
  pillInkMix,
  previousChapterPullOffset,
  settlePull,
  stepPull,
  type PullInput,
} from '../chapterPull'

const base: PullInput = {
  dy: 0,
  hasNext: true,
  hasPrevious: true,
  beganAtTop: false,
  beganAtBottom: false,
  atTop: false,
  atBottom: false,
}

describe('chapter pull', () => {
  it('fills the Continue pill only for a gesture that began at the bottom', () => {
    const stray = stepPull(EMPTY_PULL, { ...base, dy: -50, atBottom: true })
    expect(stray.consumed).toBe(false)
    let step = stepPull(EMPTY_PULL, { ...base, dy: -52, atBottom: true, beganAtBottom: true })
    expect(step.state.next).toBeCloseTo(0.5)
    step = stepPull(step.state, { ...base, dy: -60, atBottom: true, beganAtBottom: true })
    expect(step.state.next).toBe(1)
    expect(step.armed).toBe(true)
    expect(settlePull(step.state)).toBe('next')
  })

  it('needs a longer pull for the previous chapter and drains on reversal', () => {
    let step = stepPull(EMPTY_PULL, { ...base, dy: 110, atTop: true, beganAtTop: true })
    expect(step.state.previous).toBeCloseTo(0.5)
    step = stepPull(step.state, { ...base, dy: 120, atTop: true, beganAtTop: true })
    expect(step.state.previous).toBe(1)
    expect(step.armed).toBe(true)
    // Retract a little: no longer full, so releasing unfills instead of opening.
    step = stepPull(step.state, { ...base, dy: -22, atTop: true })
    expect(step.consumed).toBe(true)
    expect(step.state.previous).toBeCloseTo(0.9)
    expect(step.state.previousArmed).toBe(false)
    expect(settlePull(step.state)).toBe('unfill')
  })

  it('does not pull without a neighbour chapter', () => {
    const step = stepPull(EMPTY_PULL, { ...base, hasPrevious: false, dy: 80, atTop: true, beganAtTop: true })
    expect(step.consumed).toBe(false)
  })

  it('shapes the rubber, ink and invite curves like Android', () => {
    expect(previousChapterPullOffset(1)).toBe(156)
    expect(previousChapterPullOffset(0.5)).toBe(117)
    expect(nextChapterPullOffset(1)).toBeCloseTo(56)
    expect(pillInkMix(0.32)).toBe(0)
    expect(pillInkMix(0.6)).toBeCloseTo(1)
    expect(nextInviteAlpha(0)).toBe(1)
    expect(nextInviteAlpha(1)).toBeGreaterThan(0.3)
    expect(nextInviteAlpha(1)).toBeLessThan(0.45)
  })
})
