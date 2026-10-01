import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  BOOK_OPEN_SCHEDULE,
  COVER_OPEN_SCHEDULE,
  PAGE_TURN_SCHEDULE,
  nextFlipIndex,
} from '../pageTurnSounds'

describe('nextFlipIndex', () => {
  it('never repeats the last recording', () => {
    for (let last = 0; last < 3; last++) {
      for (const random of [0, 0.2, 0.34, 0.5, 0.67, 0.99, 1]) {
        const next = nextFlipIndex(last, random)
        expect(next).not.toBe(last)
        expect(next).toBeGreaterThanOrEqual(0)
        expect(next).toBeLessThan(3)
      }
    }
  })
})

describe('flip schedules', () => {
  it('run lift, sweep, drop in order inside their motion', () => {
    for (const [schedule, motionMs] of [
      [PAGE_TURN_SCHEDULE, 760],
      [COVER_OPEN_SCHEDULE, 1150],
      [BOOK_OPEN_SCHEDULE, 1700],
    ] as const) {
      expect(schedule.lift).toBeLessThan(schedule.sweep)
      expect(schedule.sweep).toBeLessThan(schedule.drop)
      expect(schedule.drop).toBeLessThan(motionMs)
    }
  })

  it('holds the desktop cover silent while the closed book slides', () => {
    // entrance-book-open spends its first 30% (510 ms of 1700) sliding.
    expect(BOOK_OPEN_SCHEDULE.lift).toBeGreaterThanOrEqual(480)
  })
})

async function soundHarness() {
  vi.resetModules()
  const sources: { start: ReturnType<typeof vi.fn>; stop: ReturnType<typeof vi.fn>; disconnect: ReturnType<typeof vi.fn> }[] = []
  const gain = { gain: { value: 0 }, connect: vi.fn(), disconnect: vi.fn() }
  const ctx = {
    state: 'running', currentTime: 10, destination: {},
    resume: vi.fn().mockResolvedValue(undefined),
    decodeAudioData: vi.fn().mockResolvedValue({}),
    createGain: vi.fn(() => gain),
    createBufferSource: vi.fn(() => {
      const source = { playbackRate: { value: 0 }, connect: vi.fn(), start: vi.fn(), stop: vi.fn(), disconnect: vi.fn() }
      sources.push(source)
      return source
    }),
  }
  const ctor = vi.fn(function () { return ctx })
  vi.stubGlobal('window', { AudioContext: ctor })
  const fetch = vi.fn().mockResolvedValue({ ok: true, arrayBuffer: async () => new ArrayBuffer(1) })
  vi.stubGlobal('fetch', fetch)
  vi.spyOn(performance, 'now').mockReturnValue(1000)
  const sounds = await import('../pageTurnSounds')
  const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve() }
  return { sounds, sources, ctx, ctor, fetch, flush, gain }
}

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('flip sound lifecycle', () => {
  it('fetches before a gesture, but only creates and resumes the context at unlock', async () => {
    const h = await soundHarness()
    h.sounds.warmPageTurnSounds()
    await h.flush()
    expect(h.fetch).toHaveBeenCalledTimes(9)
    expect(h.ctor).not.toHaveBeenCalled()
    h.sounds.unlockPageTurnSounds()
    expect(h.ctor).toHaveBeenCalledTimes(1)
    expect(h.ctx.resume).toHaveBeenCalledTimes(1)
    await h.flush()
    h.sounds.playFlip(PAGE_TURN_SCHEDULE, 1000)
    await h.flush()
    expect(h.sources).toHaveLength(3)
    expect(h.sources.map((source) => source.start.mock.calls[0]?.[0])).toEqual([10, 10.23, 10.54])
  })

  it('cancels a pending decode before any stems can start', async () => {
    const h = await soundHarness()
    h.sounds.unlockPageTurnSounds()
    const cancel = h.sounds.playFlip(PAGE_TURN_SCHEDULE)
    cancel()
    await h.flush()
    expect(h.sources).toHaveLength(0)
  })

  it('stops all scheduled stems and leaves a subsequent turn independent', async () => {
    const h = await soundHarness()
    h.sounds.unlockPageTurnSounds()
    await h.flush()
    const cancel = h.sounds.playFlip(PAGE_TURN_SCHEDULE)
    await h.flush()
    cancel()
    cancel()
    expect(h.sources).toHaveLength(3)
    for (const source of h.sources) expect(source.stop).toHaveBeenCalledTimes(1)
    expect(h.gain.disconnect).toHaveBeenCalled()
    h.sounds.playFlip(PAGE_TURN_SCHEDULE)
    await h.flush()
    expect(h.sources).toHaveLength(6)
    for (const source of h.sources.slice(3)) expect(source.stop).not.toHaveBeenCalled()
  })

  it('counts DOM preparation delay from the turn initiation', async () => {
    const h = await soundHarness()
    h.sounds.unlockPageTurnSounds()
    await h.flush()
    h.sounds.playFlip(PAGE_TURN_SCHEDULE, 800)
    await h.flush()
    expect(h.sources).toHaveLength(0)
    h.sounds.playFlip(PAGE_TURN_SCHEDULE, 900)
    await h.flush()
    expect(h.sources[1]?.start).toHaveBeenCalledWith(10.13)
  })

  it('retries failed fetches without downloading the successful stems again', async () => {
    const h = await soundHarness()
    h.fetch.mockResolvedValueOnce({ ok: false })
    h.sounds.warmPageTurnSounds()
    await h.flush()
    h.sounds.warmPageTurnSounds()
    await h.flush()
    expect(h.fetch).toHaveBeenCalledTimes(10)
    h.sounds.unlockPageTurnSounds()
    await h.flush()
    expect(h.ctx.decodeAudioData).toHaveBeenCalledTimes(9)
  })

  it('stays quiet until the gesture has resumed the context', async () => {
    const h = await soundHarness()
    h.sounds.playFlip(PAGE_TURN_SCHEDULE)
    await h.flush()
    expect(h.ctor).not.toHaveBeenCalled()
    h.sounds.unlockPageTurnSounds()
    h.ctx.state = 'suspended'
    h.sounds.playFlip(PAGE_TURN_SCHEDULE)
    await h.flush()
    expect(h.sources).toHaveLength(0)
  })
})
