import { describe, expect, it } from 'vitest'
import type { Segment } from '../../../data/models'
import type { ActiveWord } from '../../../data/models'
import { PreparedTimings } from '../../../domain/HighlightEngine'
import {
  readerAyahInkPolicyActive,
  readerHighlightKey,
  readerHighlightState,
  readerInkAyah,
  readerWaitsOnStream,
} from '../ReaderHighlightState'

function prepared(segments: Segment[]) {
  return PreparedTimings.prepare(segments)
}

describe('ReaderHighlightState', () => {
  it('uses karaoke hold duration and repeat metadata', () => {
    const timings = prepared([
      { position: 1, startMs: 0, endMs: 90 },
      { position: 2, startMs: 100, endMs: 200 },
      { position: 1, startMs: 200, endMs: 300 },
    ])
    const state = readerHighlightState(
      { ayah: 1, positionMs: 250, durationMs: 1_000, isPlaying: true, ayahCount: 7, repeatRange: null },
      timings,
    )
    expect(state.activeWord).toEqual({
      ayah: 1,
      wordPosition: 1,
      durationMs: 100,
      startMs: 200,
      spokenMs: 100,
      isRepeat: true,
      highWater: 2,
      repeatStart: 1,
      activation: 0,
    })
  })

  it('advances ayah focus during fade lead without changing word ownership', () => {
    const state = readerHighlightState(
      { ayah: 1, positionMs: 700, durationMs: 1_000, isPlaying: true, ayahCount: 7, repeatRange: null },
      prepared([{ position: 1, startMs: 0, endMs: 1_000 }]),
    )
    expect(state.activeAyah).toBe(2)
    expect(state.activeWord?.ayah).toBe(1)
    expect(readerInkAyah(state.activeWord, 1)).toBe(1)
  })

  it('uses highlightPositionMs for word ink while fade lead stays on heard position', () => {
    const timings = prepared([
      { position: 1, startMs: 0, endMs: 200 },
      { position: 2, startMs: 200, endMs: 1_000 },
    ])
    // Heard still on word 1; lead-advanced query already on word 2.
    const state = readerHighlightState(
      {
        ayah: 1,
        positionMs: 100,
        highlightPositionMs: 250,
        durationMs: 1_000,
        isPlaying: true,
        ayahCount: 7,
        repeatRange: null,
      },
      timings,
    )
    expect(state.activeAyah).toBe(1)
    expect(state.activeWord?.wordPosition).toBe(2)
  })

  it('keeps ink on the media verse when no word owns the trailing silence', () => {
    expect(readerInkAyah(null, 4)).toBe(4)
    expect(readerInkAyah(null, null)).toBeNull()
  })

  it('waits on the stream only while buffering holds and nothing is spoken', () => {
    // Hits play on an uncached item: spinner up, no word yet — the whole page
    // waits under Upcoming paper.
    expect(readerWaitsOnStream(true, null)).toBe(true)
    // Mid-chapter rebuffer: the last spoken word still owns the ink, so the
    // page keeps the wash as it froze.
    expect(
      readerWaitsOnStream(true, { ayah: 9, wordPosition: 3 } as ActiveWord),
    ).toBe(false)
    // No buffering — playing, paused, or idle.
    expect(readerWaitsOnStream(false, null)).toBe(false)
    expect(readerWaitsOnStream(false, { ayah: 9, wordPosition: 3 } as ActiveWord)).toBe(false)
  })

  it('prepares the fade-lead verse for Upcoming ink without stealing word ownership', () => {
    // During fade lead: ink stays on ayah 1, focus leads to ayah 2.
    // Both must run active-ayah policy so ayah 2 softens before handoff and
    // its first word can wash in once the media item advances.
    expect(readerAyahInkPolicyActive(1, 1, 2)).toBe(true)
    expect(readerAyahInkPolicyActive(2, 1, 2)).toBe(true)
    expect(readerAyahInkPolicyActive(3, 1, 2)).toBe(false)
    // Mid-verse: ink and lead agree.
    expect(readerAyahInkPolicyActive(4, 4, 4)).toBe(true)
    expect(readerAyahInkPolicyActive(5, 4, 4)).toBe(false)
    // Idle / not reciting.
    expect(readerAyahInkPolicyActive(1, null, null)).toBe(false)
  })

  it('distinguishes the real handoff when adjacent words share a position', () => {
    const timings = prepared([{ position: 1, startMs: 0, endMs: 1_000 }])
    const lead = readerHighlightState(
      { ayah: 1, positionMs: 700, durationMs: 1_000, isPlaying: true, ayahCount: 7, repeatRange: null },
      timings,
    )
    const joined = readerHighlightState(
      { ayah: 2, positionMs: 10, durationMs: 1_000, isPlaying: true, ayahCount: 7, repeatRange: null },
      timings,
    )
    expect(lead.activeAyah).toBe(joined.activeAyah)
    expect(lead.activeWord?.wordPosition).toBe(joined.activeWord?.wordPosition)
    expect(readerHighlightKey(lead)).not.toBe(readerHighlightKey(joined))
  })

  it('distinguishes repeat-chain and duration changes', () => {
    const base = {
      activeAyah: 1,
      activeBasmalah: false,
      activeWord: { ayah: 1, wordPosition: 2, durationMs: 100, isRepeat: true, highWater: 4, repeatStart: 2 },
    }
    expect(readerHighlightKey(base)).not.toBe(
      readerHighlightKey({
        ...base,
        activeWord: { ...base.activeWord, durationMs: 120, repeatStart: 1 },
      }),
    )
  })
})
