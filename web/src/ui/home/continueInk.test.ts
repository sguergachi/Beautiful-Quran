import { describe, expect, it } from 'vitest'
import {
  CONTINUE_INK_DRY_MS,
  ContinueInkMachine,
  continueInkShouldFill,
  continueInkShouldFlood,
  continueInkShouldWipe,
  continueInkToContinueChapter,
  continueWipeMs,
  continueWipeProgress,
  DROP_AT,
  SWEEP_AT,
} from './continueInk'

describe('continue wipe', () => {
  it('stays wet through the lift', () => {
    expect(continueWipeProgress(0)).toBe(0)
    expect(continueWipeProgress(SWEEP_AT)).toBe(0)
  })

  it('finishes the frame the drop begins', () => {
    expect(continueWipeProgress(DROP_AT)).toBe(1)
    expect(continueWipeProgress(1)).toBe(1)
  })

  it('is half done midway from sweep to drop', () => {
    const mid = (SWEEP_AT + DROP_AT) / 2
    expect(continueWipeProgress(mid)).toBeCloseTo(0.5, 4)
  })

  it('covers only what a leftover wipe has left', () => {
    expect(continueWipeMs(0)).toBe(CONTINUE_INK_DRY_MS)
    expect(continueWipeMs(0.5)).toBe(106)
    expect(continueWipeMs(1)).toBe(0)
  })
})

describe('continue flood gate', () => {
  it('floods on a Continue tap or a swipe into that chapter', () => {
    expect(continueInkShouldFlood(true, false, false)).toBe(true)
    expect(continueInkShouldFlood(false, false, true)).toBe(true)
    expect(continueInkShouldFlood(false, false, false)).toBe(false)
    expect(continueInkShouldFlood(true, true, true)).toBe(false)
  })

  it('treats a swipe into the Continue chapter as a Continue turn', () => {
    expect(continueInkToContinueChapter(18, 18)).toBe(true)
    expect(continueInkToContinueChapter(2, 18)).toBe(false)
    expect(continueInkToContinueChapter(18, 0)).toBe(false)
  })

  it('wipes only after a Continue-owned reader', () => {
    expect(continueInkShouldWipe(false, true)).toBe(true)
    expect(continueInkShouldWipe(false, false)).toBe(false)
  })

  it('does not keep the row wet when another chapter is open', () => {
    expect(continueInkShouldFill(false, 0, false)).toBe(false)
    expect(continueInkShouldFill(true, 0, false)).toBe(true)
    expect(continueInkShouldFill(false, 1, false)).toBe(true)
    expect(continueInkShouldFill(false, 0, true)).toBe(true)
  })
})

describe('ContinueInkMachine', () => {
  it('floods when Continue is tapped and stays dry for a different chapter', () => {
    const tapped = new ContinueInkMachine()
    tapped.tapped = true
    tapped.step(16, 0, 2, 18)
    const dry = new ContinueInkMachine()
    dry.step(16, 0.4, 2, 18)
    expect(tapped.spread).toBeGreaterThan(0)
    expect(dry.spread).toBe(0)
  })

  it('floods a swipe into the Continue chapter', () => {
    const ink = new ContinueInkMachine()
    ink.step(16, 0.2, 18, 18)
    expect(ink.spread).toBeGreaterThan(0)
  })

  it('wipes on the way back from a Continue-owned reader', () => {
    const ink = new ContinueInkMachine()
    ink.tapped = true
    ink.step(16, 0, 18, 18)
    ink.step(500, 1, 18, 18)
    expect(ink.spread).toBe(1)
    expect(ink.dry).toBe(0)
    ink.step(16, 0.35, 18, 18)
    expect(ink.dry).toBeGreaterThan(0)
    expect(ink.dry).toBeLessThan(1)
  })
})
