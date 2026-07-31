import { describe, expect, it } from 'vitest'
import {
  MIDPOINT_SEEK_GRACE_MS,
  fastForwardAction,
  midpointMs,
  nextConsumedAyah,
} from '../FastForwardPolicy'

describe('FastForwardPolicy', () => {
  it('long ayah first skip goes to midpoint', () => {
    const action = fastForwardAction({
      ayah: 5,
      positionMs: 0,
      ayahCount: 20,
      midpointMs: 10_000,
      midpointConsumedForAyah: 0,
    })
    expect(action).toEqual({ kind: 'midpoint', ayah: 5, positionMs: 10_000 })
    expect(nextConsumedAyah(action)).toBe(5)
  })

  it('second skip on same long ayah advances even if position still early', () => {
    // Regression (#560 / Android #532): async seek leaves position pre-midpoint;
    // a second FF must not re-issue the same midpoint seek forever.
    const action = fastForwardAction({
      ayah: 5,
      positionMs: 0,
      ayahCount: 20,
      midpointMs: 10_000,
      midpointConsumedForAyah: 5,
    })
    expect(action).toEqual({ kind: 'ayah', ayah: 6 })
    expect(nextConsumedAyah(action)).toBe(0)
  })

  it('past midpoint goes to next ayah', () => {
    const action = fastForwardAction({
      ayah: 5,
      positionMs: 12_000,
      ayahCount: 20,
      midpointMs: 10_000,
      midpointConsumedForAyah: 0,
    })
    expect(action).toEqual({ kind: 'ayah', ayah: 6 })
  })

  it('short ayah has no midpoint and advances', () => {
    const action = fastForwardAction({
      ayah: 2,
      positionMs: 0,
      ayahCount: 7,
      midpointMs: null,
      midpointConsumedForAyah: 0,
    })
    expect(action).toEqual({ kind: 'ayah', ayah: 3 })
  })

  it('last ayah past midpoint is none', () => {
    const action = fastForwardAction({
      ayah: 7,
      positionMs: 50_000,
      ayahCount: 7,
      midpointMs: 10_000,
      midpointConsumedForAyah: 7,
    })
    expect(action).toEqual({ kind: 'none' })
  })

  it('within grace of midpoint treats as past midpoint', () => {
    const midpoint = 10_000
    const action = fastForwardAction({
      ayah: 3,
      positionMs: midpoint - MIDPOINT_SEEK_GRACE_MS,
      ayahCount: 10,
      midpointMs: midpoint,
      midpointConsumedForAyah: 0,
    })
    expect(action).toEqual({ kind: 'ayah', ayah: 4 })
  })

  it('new ayah after advance can mid-skip again', () => {
    const first = fastForwardAction({
      ayah: 5,
      positionMs: 0,
      ayahCount: 20,
      midpointMs: 8_000,
      midpointConsumedForAyah: 0,
    })
    const consumed = nextConsumedAyah(first)
    const second = fastForwardAction({
      ayah: 5,
      positionMs: 0,
      ayahCount: 20,
      midpointMs: 8_000,
      midpointConsumedForAyah: consumed,
    })
    expect(second.kind).toBe('ayah')
    const third = fastForwardAction({
      ayah: 6,
      positionMs: 0,
      ayahCount: 20,
      midpointMs: 9_000,
      midpointConsumedForAyah: nextConsumedAyah(second),
    })
    expect(third).toEqual({ kind: 'midpoint', ayah: 6, positionMs: 9_000 })
  })

  it('midpointMs is null for short ayahs', () => {
    const segs = Array.from({ length: 10 }, (_, i) => ({
      startMs: i * 1000,
      endMs: i * 1000 + 900,
    }))
    expect(midpointMs(segs)).toBeNull()
  })

  it('midpointMs picks first segment at or after time half', () => {
    const segs = Array.from({ length: 20 }, (_, i) => ({
      startMs: i * 1000,
      endMs: i * 1000 + 1000,
    }))
    expect(midpointMs(segs)).toBe(10_000)
  })

  it('past time half goes to next ayah', () => {
    const segs = Array.from({ length: 20 }, (_, i) => {
      const startMs = i < 10 ? i * 500 : 10_000 + (i - 10) * 1000
      return { startMs, endMs: startMs + 500 }
    })
    segs[19]!.endMs = 20_000
    const mid = midpointMs(segs)!
    const action = fastForwardAction({
      ayah: 1,
      positionMs: mid + 1_000,
      ayahCount: 10,
      midpointMs: mid,
      midpointConsumedForAyah: 0,
    })
    expect(action).toEqual({ kind: 'ayah', ayah: 2 })
  })
})
