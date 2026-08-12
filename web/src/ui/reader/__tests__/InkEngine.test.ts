import { describe, expect, it, beforeEach } from 'vitest'
import {
  InkEngine,
  InkState,
  resetTuning,
  wordState,
  inRepeatChain,
  word,
  minSweepFloorMs,
  setHighlightLeadMs,
  DEFAULT_HIGHLIGHT_LEAD_MS,
  sweepMs,
  glinting,
  glintResonance,
  prefaceState,
  prefaceWashProgress,
  advancePrefaceWashProgress,
  PREFACE_WASH_SETTLE_FRACTION,
  inkAlpha,
  getTuning,
} from '../InkEngine'
import type { ActiveWord } from '../../../data/models'

function active(
  wordPosition: number,
  durationMs = 600,
  isRepeat = false,
  highWater = wordPosition,
  repeatStart = wordPosition,
): ActiveWord {
  return { ayah: 1, wordPosition, durationMs, isRepeat, highWater, repeatStart }
}

function states(count: number, activeWord: ActiveWord | null): InkState[] {
  return Array.from({ length: count }, (_, i) =>
    wordState(i + 1, activeWord, true, false),
  )
}

describe('InkEngine', () => {
  beforeEach(() => resetTuning())

  it('idle ayah words are plain, recessed ayah words are upcoming', () => {
    expect(wordState(1, null, false, false)).toBe(InkState.Plain)
    expect(wordState(1, null, false, true)).toBe(InkState.Upcoming)
  })

  it('basmalah preface ink follows active and recess', () => {
    expect(prefaceState(false, false)).toBe(InkState.Plain)
    expect(prefaceState(true, false)).toBe(InkState.Active)
    expect(prefaceState(true, true)).toBe(InkState.Active)
    expect(prefaceState(false, true)).toBe(InkState.Upcoming)
  })

  it('active ayah with no lit word rests every word at upcoming', () => {
    expect(states(4, null)).toEqual([
      InkState.Upcoming,
      InkState.Upcoming,
      InkState.Upcoming,
      InkState.Upcoming,
    ])
  })

  it('words split around the active word', () => {
    expect(states(4, active(3))).toEqual([
      InkState.Recited,
      InkState.Recited,
      InkState.Active,
      InkState.Upcoming,
    ])
  })

  it('high-water keeps already-recited words lit during a repeat', () => {
    expect(states(5, active(2, 600, true, 4, 2))).toEqual([
      InkState.Recited,
      InkState.Active,
      InkState.Recited,
      InkState.Recited,
      InkState.Upcoming,
    ])
  })

  it('no chain while not repeating', () => {
    expect(inRepeatChain(2, active(3))).toBe(false)
    expect(inRepeatChain(2, null)).toBe(false)
  })

  it('chain spans repeat start through the re-recited word', () => {
    const repeating = active(3, 600, true, 4, 2)
    expect(inRepeatChain(1, repeating)).toBe(false)
    expect(inRepeatChain(2, repeating)).toBe(true)
    expect(inRepeatChain(3, repeating)).toBe(true)
    expect(inRepeatChain(4, repeating)).toBe(false)
  })

  it('chain releases once playback advances past the high water', () => {
    const moved = active(5, 600, false, 5)
    for (let position = 1; position <= 5; position++) {
      expect(inRepeatChain(position, moved)).toBe(false)
    }
  })

  it('word bundles state and repeat membership', () => {
    const repeating = active(2, 600, true, 4, 2)
    const w = word(2, repeating, true, false)
    expect(w.state).toBe(InkState.Active)
    expect(w.repeat).toBe(true)
  })

  it('inactive ayah words never wear the repeat wash', () => {
    const repeating = active(2, 600, true, 4, 2)
    const w = word(2, repeating, false, true)
    expect(w.state).toBe(InkState.Upcoming)
    expect(w.repeat).toBe(false)
  })

  it('sweep follows the word duration corrected for speed', () => {
    expect(sweepMs(active(1, 600), 1)).toBe(600)
    expect(sweepMs(active(1, 600), 2)).toBe(300)
    expect(sweepMs(active(1, 600), 0.5)).toBe(1200)
    expect(sweepMs(active(1, 601), 1.5)).toBe(400)
  })

  it('sweep clamps to the tuned floor and ceiling', () => {
    const floor = minSweepFloorMs()
    expect(sweepMs(active(1, floor), 1)).toBe(floor)
    expect(sweepMs(active(1, 500), 1)).toBe(500)
    expect(sweepMs(active(1, 60_000), 1)).toBe(getTuning().maxSweepMs)
  })

  it('short hold is scaled up to the min sweep floor', () => {
    // Residual wash finishes after handoff so short words still breathe.
    // Floor includes highlight lead so early-started short words breathe longer.
    const floor = minSweepFloorMs()
    expect(sweepMs(active(1, 80), 1)).toBe(floor)
    expect(sweepMs(active(1, 80), 2)).toBe(floor)
    expect(sweepMs(active(1, 10), 1)).toBe(floor)
    expect(sweepMs(active(1, 0), 1)).toBe(floor)
  })

  it('highlight lead raises the short-hold sweep floor', () => {
    setHighlightLeadMs(0)
    expect(minSweepFloorMs()).toBe(getTuning().minSweepMs)
    setHighlightLeadMs(114)
    expect(minSweepFloorMs()).toBe(getTuning().minSweepMs + 114)
    expect(sweepMs(active(1, 80), 1)).toBe(minSweepFloorMs())
    setHighlightLeadMs(DEFAULT_HIGHLIGHT_LEAD_MS)
  })

  it('no active word means no sweep', () => {
    expect(sweepMs(null, 1)).toBeNull()
  })

  it('active words wear the fresh-ink glint', () => {
    expect(glinting(InkState.Active)).toBe(true)
    expect(glinting(InkState.Plain)).toBe(false)
    expect(glinting(InkState.Upcoming)).toBe(false)
    expect(glinting(InkState.Recited)).toBe(false)
  })

  it('glint resonance is identity when not holding', () => {
    expect(glintResonance(false, 0.5)).toBe(1)
  })

  it('glint resonance tracks voice energy above resting', () => {
    const hot = glintResonance(true, 0, 0.6, 0.3, 0.4, 0)
    const cold = glintResonance(true, 0, 0.15, 0.3, 0.4, 0)
    expect(hot).toBeGreaterThan(1)
    expect(cold).toBeLessThan(1)
  })

  it('glint resonance free sine still breathes without voice', () => {
    const hz = 5.5
    expect(glintResonance(true, 0.25 / hz, 0, 0, 0, 0.22, hz)).toBeCloseTo(1.22, 4)
    expect(glintResonance(true, 0.75 / hz, 0, 0, 0, 0.22, hz)).toBeCloseTo(0.78, 4)
  })

  it('only upcoming ink is faint', () => {
    expect(inkAlpha(InkState.Upcoming)).toBe(InkEngine.tuning.upcomingAlpha)
    expect(inkAlpha(InkState.Plain)).toBe(1)
    expect(inkAlpha(InkState.Active)).toBe(1)
    expect(inkAlpha(InkState.Recited)).toBe(1)
  })

  it('calligraphy wash follows the lead-in playback clock', () => {
    expect(prefaceWashProgress(0, 5000)).toBe(0)
    expect(prefaceWashProgress(100, 0)).toBe(0)
    const settleAt = Math.trunc(5000 * PREFACE_WASH_SETTLE_FRACTION)
    expect(prefaceWashProgress(settleAt / 2, 5000)).toBeCloseTo(0.5, 2)
    expect(prefaceWashProgress(settleAt, 5000)).toBe(1)
    expect(prefaceWashProgress(5000, 5000)).toBe(1)
    expect(prefaceWashProgress(settleAt + 1, 5000)).toBe(1)
    expect(settleAt).toBeLessThan(5000)
  })

  it('keeps a completed calligraphy wash settled across the playlist clock reset', () => {
    const completed = advancePrefaceWashProgress(0, 4400, 5000)
    expect(completed).toBe(1)

    // Ayah 1 can take over before the active-preface React prop commits.
    expect(advancePrefaceWashProgress(completed, 0, 7000)).toBe(1)
  })

})
