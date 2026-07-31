import { describe, expect, it } from 'vitest'
import { HighlightClock } from '../HighlightClock'
import { HighlightEngine } from '../HighlightEngine'
import {
  A2DP_MS,
  LE_MS,
  LOCAL_MS,
  OutputKind,
  OutputLatency,
  OutputRoute,
  classify,
  heardMs,
  highlightMs,
  latencyMs,
  latencyMsForRoute,
} from '../OutputLatency'
import type { Segment } from '../../data/models'

describe('OutputLatency', () => {
  it('empty kinds are local with zero latency', () => {
    expect(classify(new Set())).toBe(OutputRoute.LOCAL)
    expect(latencyMs(new Set())).toBe(0)
  })

  it('local only is zero latency', () => {
    expect(classify(new Set([OutputKind.LOCAL]))).toBe(OutputRoute.LOCAL)
    expect(latencyMsForRoute(OutputRoute.LOCAL)).toBe(LOCAL_MS)
  })

  it('A2DP wins over speaker still listed as an output', () => {
    const kinds = new Set([OutputKind.LOCAL, OutputKind.BLUETOOTH_A2DP])
    expect(classify(kinds)).toBe(OutputRoute.BLUETOOTH_A2DP)
    expect(latencyMs(kinds)).toBe(A2DP_MS)
  })

  it('LE is used when no classic A2DP is present', () => {
    const kinds = new Set([OutputKind.LOCAL, OutputKind.BLUETOOTH_LE])
    expect(classify(kinds)).toBe(OutputRoute.BLUETOOTH_LE)
    expect(latencyMs(kinds)).toBe(LE_MS)
  })

  it('A2DP wins over LE when both are present', () => {
    const kinds = new Set([
      OutputKind.BLUETOOTH_A2DP,
      OutputKind.BLUETOOTH_LE,
      OutputKind.LOCAL,
    ])
    expect(classify(kinds)).toBe(OutputRoute.BLUETOOTH_A2DP)
    expect(latencyMs(kinds)).toBe(A2DP_MS)
  })

  it('heardMs subtracts latency and never goes negative', () => {
    expect(heardMs(1_000, 180)).toBe(820)
    expect(heardMs(50, 180)).toBe(0)
    expect(heardMs(1_000, 0)).toBe(1_000)
  })

  it('highlightMs advances the clock by lead after lag', () => {
    expect(highlightMs(3_800, 0, 1_200)).toBe(5_000)
    expect(highlightMs(1_000, 180, 1_200)).toBe(2_020)
    expect(highlightMs(1_000, 180, 0)).toBe(heardMs(1_000, 180))
    expect(highlightMs(50, 180, 0)).toBe(0)
  })

  it('word lead does not move the heard clock used by non-word surfaces', () => {
    expect(heardMs(1_000, 180)).toBe(820)
    expect(highlightMs(1_000, 180, 1_200)).toBe(2_020)
  })

  it('word lead cannot cross encoded opening silence', () => {
    const segments: Segment[] = [
      { position: 1, startMs: 1_179, endMs: 2_094 },
      { position: 2, startMs: 2_094, endMs: 2_814 },
    ]
    const duringSilence = highlightMs(1_100, 0, 114, segments[0]!.startMs)
    expect(duringSilence).toBe(1_100)
    expect(HighlightEngine.activeWord(segments, duringSilence)).toBeNull()

    const voiceStart = highlightMs(1_179, 0, 114, segments[0]!.startMs)
    expect(voiceStart).toBe(1_179)
    expect(HighlightEngine.activeWord(segments, voiceStart)).toBe(1)
  })

  it('word lead ramps in after the first word so handoff settle cannot skip it', () => {
    const gate = 210
    const lead = 114
    expect(highlightMs(210, 0, lead, gate)).toBe(210)
    expect(highlightMs(260, 0, lead, gate)).toBe(310)
    expect(highlightMs(324, 0, lead, gate)).toBe(438)
    expect(highlightMs(260, 0, lead, 0)).toBe(374)
  })

  it('ramped lead plus settle lights word 1 after media-item handoff', () => {
    const segments: Segment[] = [
      { position: 1, startMs: 210, endMs: 490 },
      { position: 2, startMs: 490, endMs: 1_340 },
    ]
    const clock = new HighlightClock()
    const words: Array<number | null> = []
    for (let tick = 0; tick < 25; tick++) {
      const pos = tick * 33
      const raw = highlightMs(pos, 0, 114, segments[0]!.startMs)
      words.push(HighlightEngine.activeWord(segments, clock.sample('ayah7', raw)))
    }
    expect(words.some((w) => w === 1)).toBe(true)
    expect(words.find((w) => w != null)).toBe(1)
  })

  it('preset values are the documented table', () => {
    expect(OutputLatency.LOCAL_MS).toBe(0)
    expect(OutputLatency.A2DP_MS).toBe(180)
    expect(OutputLatency.LE_MS).toBe(80)
  })
})
