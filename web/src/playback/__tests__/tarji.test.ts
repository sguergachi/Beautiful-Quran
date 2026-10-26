import { describe, expect, it } from 'vitest'
import {
  analyzeTarji,
  reciterTarjiKnobs,
  sampleTarji,
  TarjiEventLedger,
  TarjiWordGate,
} from '../tarji'

function voice(
  seconds: number,
  depth: number,
  rate: number,
  fm = false,
): Float32Array {
  const pcm = new Float32Array(seconds * 8000)
  for (let i = 0; i < pcm.length; i++) {
    const t = i / 8000,
      carrier = fm
        ? 2 * Math.PI * 150 * t +
          ((depth * 150) / rate) * Math.sin(2 * Math.PI * rate * t)
        : 2 * Math.PI * 150 * t
    pcm[i] =
      0.2 *
      (fm ? 1 : 1 + depth * Math.sin(2 * Math.PI * rate * t)) *
      Math.sin(carrier)
  }
  return pcm
}
describe('source-clock tarjīʿ', () => {
  it('silence and a steady held note never synthesize a shimmer', () => {
    for (const pcm of [new Float32Array(16000), voice(2, 0, 5)]) {
      const track = analyzeTarji(pcm)
      expect(Math.max(...track.gain)).toBe(0)
    }
  })
  it('detects repeated amplitude modulation on a held note', () => {
    const track = analyzeTarji(voice(4, 0.2, 5))
    expect(Math.max(...track.gain)).toBeGreaterThan(0.5)
    expect(sampleTarji(track, 2800).rate).toBeCloseTo(5, 0)
    expect(Math.max(...track.pulse)).toBeGreaterThan(0.2)
    expect(Math.min(...track.pulse)).toBeLessThan(-0.2)
  })
  it('detects pitch-only vibrato without amplitude oscillation', () => {
    const track = analyzeTarji(voice(4, 0.02, 5, true))
    expect(Math.max(...track.gain)).toBeGreaterThan(0.2)
  })
  it('rejects faster flutter instead of admitting its subharmonic', () => {
    const track = analyzeTarji(voice(3, 0.2, 15))
    expect(Math.max(...track.gain)).toBe(0)
  })
  it('stops the pulse through silence and cannot read beyond the clip', () => {
    const pcm = new Float32Array(40000)
    pcm.set(voice(3, 0.2, 5))
    const track = analyzeTarji(pcm)
    expect(sampleTarji(track, 4900).gain).toBeLessThan(0.01)
    expect(sampleTarji(track, 5000).gain).toBe(0)
    expect(sampleTarji(track, -1).gain).toBe(0)
  })
  it('uses the shipped Hani capture tuning without changing other reciters', () => {
    expect(reciterTarjiKnobs(7)).toMatchObject({
      holdMinMs: 1200,
      brightness: 2,
      maxHz: 8.3,
    })
    expect(reciterTarjiKnobs(1)).toMatchObject({
      holdMinMs: 300,
      brightness: 1,
    })
  })
  it('an event can illuminate one word, and a spent word cannot relight', () => {
    const ledger = new TarjiEventLedger(),
      first = new TarjiWordGate(ledger),
      next = new TarjiWordGate(ledger)
    expect(first.allows(0.5, true, 1000, 1100)).toBe(true)
    expect(next.allows(0.5, true, 1000, 1400)).toBe(false)
    expect(first.allows(0, false, -1, 1100)).toBe(false)
    expect(first.allows(0.8, true, 2000, 1100)).toBe(false)
  })
  it('rejects inherited old events but does not spend a word on a weak attack', () => {
    const gate = new TarjiWordGate()
    expect(gate.allows(0.5, true, 100, 1000)).toBe(false)
    expect(gate.allows(0.1, true, 1000, 1000)).toBe(true)
    expect(gate.allows(0, false, -1, 1000)).toBe(false)
    expect(gate.allows(0.6, true, 1500, 1000)).toBe(true)
  })
})

// Recording mode is the shipped path; causal analysis above remains the phase
// and feature extractor, not a substitute for two-sided event admission.
import { analyzeRecording, dramaScore } from '../tarjiRecording'
describe('Android recording-mode admission', () => {
  it('accepts sustained AM and FM and rejects still vowels and out-of-band flutter', () => {
    for (const pcm of [voice(4, 0.2, 5), voice(4, 0.02, 5, true)]) {
      expect(Math.max(...analyzeRecording(pcm).gain)).toBeGreaterThan(0.3)
    }
    for (const pcm of [
      voice(3, 0, 5),
      voice(3, 0.2, 15),
      new Float32Array(16000),
    ]) {
      expect(Math.max(...analyzeRecording(pcm).gain)).toBe(0)
    }
  })
  it('judges Hani as held AND lifted, and ignores untrusted pitch octave errors', () => {
    expect(dramaScore(1800, 3000, 1, 1, 7)).toBe(0)
    expect(dramaScore(1800, 3000, 2, 1, 7)).toBe(1)
    expect(dramaScore(1800, 3000, 1, 2, 7)).toBe(0)
    expect(dramaScore(100, 100, 1, 1)).toBeLessThan(0.5)
  })
})

it('matches unchanged Android recording decisions on AM, FM and matched stills', () => {
  // Produced by the actual Tarji.kt + TarjiRecordingDetector.kt JVM engines,
  // 200 hops of identical 8 kHz PCM. This tests admission and lifecycle,
  // including the first hop above 0.1 gain, not just eventual peak detection.
  for (const [pcm, peak, count, first] of [
    [voice(4, 0.2, 5), 0.99999565, 180, 29],
    [voice(4, 0.02, 5, true), 0.9999949, 178, 29],
    [voice(4, 0, 5), 0, 0, -1],
    [voice(4, 0.2, 15), 0, 0, -1],
  ] as const) {
    const track = analyzeRecording(pcm)
    expect(Math.max(...track.gain)).toBeCloseTo(peak, 6)
    expect([...track.event].filter((x) => x >= 0)).toHaveLength(count)
    expect([...track.gain].findIndex((x) => x > 0.1)).toBe(first)
  }
})

it('samples PCM at hop centres while decisions remain on hop ends', () => {
  const track = {
    pulse: new Float32Array([0, 1, 0]),
    gain: new Float32Array([0, 1, 0]),
    rate: new Float32Array([5, 5, 5]),
    event: new Int32Array([0, 0, 0]),
  }
  expect(sampleTarji(track, 20)).toMatchObject({ pulse: 0.5, gain: 0 })
  expect(sampleTarji(track, 30)).toMatchObject({ pulse: 1, gain: 0.5 })
})
