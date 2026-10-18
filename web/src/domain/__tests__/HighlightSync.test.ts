import { describe, expect, it } from 'vitest'
import events from '../../../../app/src/test/resources/highlight-sync-v2-events.json'
import fixtures from '../../../../app/src/test/resources/highlight-sync-v2-traces.json'
import { PreparedTimings } from '../HighlightEngine'
import { highlightFrame, sampleHighlight, suspendHighlight, type HighlightPlaybackSample, type HighlightSyncState } from '../HighlightSync'
import { mediaWashProgress, highlightTimeline } from '../../playback/highlightTimeline'

const timing = { revision: 1, timings: PreparedTimings.prepare([{ position: 1, startMs: 0, endMs: 1000 }, { position: 2, startMs: 1100, endMs: 10000 }]), voiceStartMs: 0 }
const input = (positionMs: number, atMs: number, extra: Partial<HighlightPlaybackSample> = {}): HighlightPlaybackSample => ({
  item: { surah: 24, ayah: 33, reciter: 7 }, positionMs, sampledAtNanos: atMs * 1e6,
  isPlaying: true, isBuffering: false, speed: 1, clockRevision: 0, inkGeneration: 0, ...extra,
})
describe('HighlightEngine V2 parity', () => {
  for (const fixture of fixtures.cases) it(fixture.name, () => {
    let state: HighlightSyncState | null = null
    for (const sample of fixture.samples) {
      state = sampleHighlight(state, input(sample.rawMs, sample.atMs, { speed: fixture.speed }), timing)
      expect(highlightFrame(state, sample.atMs * 1e6).inkMs).toBe(sample.v2Ms)
    }
  })
  for (const fixture of events.cases) it(fixture.name, () => {
    const c = fixture as { name: string; segments: number[][]; voice?: number; lag?: number; lead?: number;
      events: { at: number; raw: number; ink: number; word: number | null; occurrence: number | null; phase: string;
        playing?: boolean; buffering?: boolean; speed?: number; clock?: number; generation?: number; revision?: number; empty?: boolean; frameAt?: number }[] }
    const timings = PreparedTimings.prepare(c.segments.map(([position, startMs, endMs]) => ({ position, startMs, endMs })))
    let state: HighlightSyncState | null = null
    for (const e of c.events) {
      state = sampleHighlight(state, input(e.raw, e.at, { isPlaying: e.playing ?? true, isBuffering: e.buffering ?? false,
        speed: e.speed ?? 1, clockRevision: e.clock ?? 0, inkGeneration: e.generation ?? 0 }),
        { revision: e.revision ?? 1, timings: e.empty ? PreparedTimings.prepare([]) : timings, voiceStartMs: c.voice ?? 0 },
        { outputLagWallMs: c.lag ?? 0, wordLeadMs: c.lead ?? 0 })
      const frame = highlightFrame(state, (e.frameAt ?? e.at) * 1e6)
      expect([frame.inkMs, frame.active?.position ?? null, frame.occurrence, frame.phase], c.name + '/' + e.at)
        .toEqual([e.ink, e.word, e.occurrence, e.phase])
    }
  })
  it('freezes all wash windows on pause and buffering; speed is applied once', () => {
    let state = sampleHighlight(null, input(0, 0), timing)
    highlightTimeline.state = state
    const window = highlightTimeline.window(0, 1000)!
    state = sampleHighlight(state, input(400, 400, { isPlaying: false }), timing)
    expect(mediaWashProgress(window, highlightFrame(state, 900e6))).toBe(.4)
    state = sampleHighlight(state, input(400, 900, { speed: 2 }), timing)
    expect(mediaWashProgress(window, highlightFrame(state, 1000e6))).toBe(.6)
    state = sampleHighlight(state, input(600, 1000, { speed: 2, isBuffering: true }), timing)
    expect(mediaWashProgress(window, highlightFrame(state, 1400e6))).toBe(.6)
    highlightTimeline.state = null
  })
  it('keeps display frames moving at 60, 90 and 120Hz between delayed polls', () => {
    for (const hz of [60, 90, 120]) {
      const state = sampleHighlight(null, input(0, 0), timing)
      let prior = -1
      for (let frame = 0; frame < hz; frame++) {
        const current = highlightFrame(state, frame / hz * 1e9)
        expect(current.inkMs).toBeGreaterThan(prior)
        prior = current.inkMs
      }
    }
  })
  it('rejects stale seeks, retains ink through corrections, and reopens without background extrapolation', () => {
    let state = sampleHighlight(null, input(500, 500), timing)
    state = suspendHighlight(state, 600e6)
    expect(highlightFrame(state, 20000e6).inkMs).toBe(600)
    state = sampleHighlight(state, input(900, 20000, { isPlaying: false }), timing)
    expect(highlightFrame(state, 21000e6).inkMs).toBe(900)
    const sought = sampleHighlight(state, input(100, 22000, { clockRevision: 2, inkGeneration: 2 }), timing)
    expect(highlightFrame(sought, 22000e6).inkMs).toBe(100)
    expect(sampleHighlight(sought, input(900, 23000, { clockRevision: 1, inkGeneration: 1 }), timing)).toBe(sought)
  })
  it('calibrates after projection with truncated speed-scaled manual lag and an opening ramp', () => {
    const state = sampleHighlight(null, input(200, 0, { speed: 1.5 }), { ...timing, voiceStartMs: 300 }, { outputLagWallMs: 101, wordLeadMs: 100 })
    const frame = highlightFrame(state, 200e6)
    expect(frame.mediaMs).toBe(500)
    expect(frame.heardMs).toBe(349)
    expect(frame.inkMs).toBe(398)
  })
})
