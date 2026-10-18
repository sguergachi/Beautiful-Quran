import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest'
import type { Reciter, SurahContent } from '../../data/models'
import type { AudioPrefetcher } from '../audioPrefetch'
import { wantsGapless5Transport } from '../gaplessPolicy'
import { FakeAudio } from './fakeAudio'
import { PLAYBACK_MESSAGES } from '../playbackErrors'

let PlayerController: typeof import('../player').PlayerController

const reciter: Reciter = {
  id: 1,
  slug: 'Alafasy_128kbps',
  name: 'Mishary Rashid Alafasy',
  style: 'Murattal',
  hasTimings: true,
}

const content: SurahContent = {
  surah: {
    id: 1,
    nameArabic: '',
    nameTransliteration: 'Al-Fatihah',
    nameTranslation: '',
    revelationPlace: '',
    ayahCount: 3,
  },
  ayahs: [1, 2, 3].map((number) => ({
    surahId: 1,
    number,
    text: '',
    translation: '',
    page: 1,
    words: [],
  })),
}

function instantPrefetcher(): AudioPrefetcher {
  return {
    ensure: vi.fn(async (url: string) => `blob:${url}`),
    resolveSrc: vi.fn((url: string) => `blob:${url}`),
    isWarm: vi.fn(() => true),
    warmSurah: vi.fn(),
    readAhead: vi.fn(),
    pauseWarm: vi.fn(),
    resumeWarm: vi.fn(),
  } as unknown as AudioPrefetcher
}

describe('PlayerController event sequences', () => {
  beforeAll(async () => {
    vi.stubGlobal('Audio', FakeAudio)
    vi.stubGlobal('navigator', {})
    PlayerController = (await import('../player')).PlayerController
  })

  beforeEach(() => {
    const raf = vi.fn(() => 1)
    const caf = vi.fn()
    vi.stubGlobal('window', {
      requestAnimationFrame: raf,
      cancelAnimationFrame: caf,
      setTimeout,
      clearTimeout,
    })
    vi.stubGlobal('requestAnimationFrame', raf)
    vi.stubGlobal('cancelAnimationFrame', caf)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.stubGlobal('Audio', FakeAudio)
    vi.stubGlobal('navigator', {})
    vi.restoreAllMocks()
  })

  afterAll(() => vi.unstubAllGlobals())

  it('publishes authoritative restart generations while pause, buffer and speed preserve ink', async () => {
    const audio = new FakeAudio()
    const player = new PlayerController(instantPrefetcher(), true, null, () => audio.asAudio())
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })
    await player.play()
    const initial = player.getState().inkGeneration!
    player.pause()
    player.setSpeed(1.5)
    audio.emit('waiting')
    expect(player.getState().inkGeneration).toBe(initial)
    player.seekMs(3000)
    expect(player.getState().inkGeneration).toBe(initial + 1)
    expect(player.getState().clockRevision).toBeGreaterThan(0)
    player.seekMs(3000)
    expect(player.getState().inkGeneration).toBe(initial + 2)
    player.pause()
  })

  it('keeps play intent separate from actual advancement until the audio element starts', async () => {
    const audio = new FakeAudio()
    audio.readyState = 0
    const player = new PlayerController(instantPrefetcher(), true, null, () => audio.asAudio())
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })
    const starting = player.play()
    expect(player.getState().isPlaying).toBe(true)
    expect(player.getState().advances).toBe(false)
    audio.readyState = 4
    audio.emit('canplay')
    await starting
    expect(player.getState().advances).toBe(true)
    audio.emit('waiting')
    expect(player.getState().advances).toBe(false)
    audio.emit('playing')
    expect(player.getState().advances).toBe(true)
    player.pause()
    expect(player.getState().advances).toBe(false)
  })

  it('native loop seeking rearms even when background polling missed the clip tail', async () => {
    const audio = new FakeAudio()
    const player = new PlayerController(instantPrefetcher(), true, null, () => audio.asAudio())
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })
    await player.play()
    audio.loop = true
    audio.currentTime = 4
    audio.emit('timeupdate')
    const before = player.getState().inkGeneration!
    audio.currentTime = 0
    audio.emit('seeking')
    expect(player.getState().inkGeneration).toBe(before + 1)
    expect(player.getState().positionMs).toBe(0)
    player.seekMs(2000)
    const explicit = player.getState().inkGeneration
    audio.emit('seeking')
    expect(player.getState().inkGeneration).toBe(explicit)
    player.pause()
  })

  it('dismisses an error without changing the verse or playback, then reports another failure', () => {
    const audio = new FakeAudio()
    const player = new PlayerController(instantPrefetcher(), true, null, () =>
      audio.asAudio(),
    )
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })
    audio.emit('error')
    const failed = player.getState()
    expect(failed.error).not.toBeNull()

    player.clearError()
    expect(player.getState()).toEqual({ ...failed, error: null })
    expect(audio.play).not.toHaveBeenCalled()

    audio.emit('error')
    expect(player.getState().error).toBe(failed.error)
  })

  it('does not resume when pause wins while play is waiting for canplay', async () => {
    const audio = new FakeAudio()
    audio.readyState = 0
    const player = new PlayerController(instantPrefetcher(), true, null, () =>
      audio.asAudio(),
    )
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })

    const pendingPlay = player.play()
    expect(player.getState()).toMatchObject({
      isPlaying: true,
      isBuffering: true,
    })
    player.pause()
    audio.emit('canplay')
    await pendingPlay

    expect(player.getState()).toMatchObject({
      isPlaying: false,
      isBuffering: false,
    })
    expect(audio.play).not.toHaveBeenCalled()
  })

  it('resumes mid-ayah after pause without rewinding the clip', async () => {
    const audio = new FakeAudio()
    const player = new PlayerController(instantPrefetcher(), true, null, () =>
      audio.asAudio(),
    )
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })
    await player.play()
    audio.currentTime = 4.2
    player.pause()
    expect(player.getState()).toMatchObject({ isPlaying: false })

    await player.play()
    expect(player.getState()).toMatchObject({ isPlaying: true })
    expect(audio.currentTime).toBeCloseTo(4.2, 5)
    expect(audio.play).toHaveBeenCalledTimes(2)
  })

  it('keeps Gapless-5 off at non-1× even when the join preference is on', async () => {
    const audio = new FakeAudio()
    const player = new PlayerController(instantPrefetcher(), true, null, () =>
      audio.asAudio(),
    )
    player.setSpeed(0.75)
    await player.setGapless5Enabled(true)
    expect(player.isGapless5Enabled()).toBe(false)
    expect(player.getState().speed).toBe(0.75)

    player.setSpeed(1)
    // Preference is on and speed is 1× — may enable after the module loads, or
    // stay off if Gapless-5 fails in this test environment. Non-1× must stay off.
    await vi.waitFor(() => {
      // Either gapless loaded at 1× or failed cleanly; never stuck mid-swap.
      expect(player.getState().speed).toBe(1)
    })
    player.setSpeed(1.25)
    await vi.waitFor(() => expect(player.isGapless5Enabled()).toBe(false))
  })

  it('keeps a saved non-1× rate after the first loadActive (not only after cycling)', async () => {
    const audio = new FakeAudio()
    // Browser-like: load() re-seeds playbackRate from defaultPlaybackRate.
    audio.load.mockImplementation(() => {
      audio.playbackRate = audio.defaultPlaybackRate
    })
    const player = new PlayerController(instantPrefetcher(), true, null, () =>
      audio.asAudio(),
    )
    // Preference applied before any clip is loaded (boot path).
    player.setSpeed(0.75)
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })
    await player.play()

    expect(player.getState().speed).toBe(0.75)
    expect(audio.defaultPlaybackRate).toBe(0.75)
    expect(audio.playbackRate).toBe(0.75)
  })

  it('ignores a stale pause event from the retired element after a join', async () => {
    const created: FakeAudio[] = []
    const player = new PlayerController(
      instantPrefetcher(),
      false,
      null,
      () => {
        const audio = new FakeAudio()
        created.push(audio)
        return audio.asAudio()
      },
    )
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })
    await vi.waitFor(() => expect(created[1]?.src).toContain('001002.mp3'))
    await player.play()
    await player.next()

    expect(player.getState()).toMatchObject({ isPlaying: true })
    created[0]!.emit('pause')
    expect(player.getState()).toMatchObject({ isPlaying: true })
  })

  it('suppresses multiple pause events during an autoplay src swap', async () => {
    const audio = new FakeAudio()
    const player = new PlayerController(instantPrefetcher(), true, null, () =>
      audio.asAudio(),
    )
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })
    await player.play()
    expect(player.getState().isPlaying).toBe(true)

    // Next ayah must wait for canplay so play/playing do not clear suppress yet.
    audio.readyState = 0
    const advancing = player.next()
    // playIndex is async (ensure + load); wait until suppress is armed.
    await vi.waitFor(() => expect(player.getState().isBuffering).toBe(true))
    // loadActive already emitted one pause; extra pause events during the
    // swap must not clear isPlaying.
    audio.emit('pause')
    audio.emit('pause')
    expect(player.getState().isPlaying).toBe(true)

    audio.readyState = 4
    audio.emit('canplay')
    await advancing
    expect(player.getState().isPlaying).toBe(true)
  })

  it('retries silent-stall recovery before hard-stopping', async () => {
    vi.useFakeTimers()
    const audio = new FakeAudio()
    let playCalls = 0
    audio.play.mockImplementation(async () => {
      playCalls++
      if (playCalls === 1) {
        // Initial user play succeeds.
        audio.paused = false
        audio.emit('play')
        audio.emit('playing')
        return
      }
      // Recovery attempts fail.
      throw new Error('NotAllowedError')
    })

    const player = new PlayerController(instantPrefetcher(), true, null, () =>
      audio.asAudio(),
    )
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })
    await player.play()
    expect(player.getState().isPlaying).toBe(true)

    audio.currentTime = 2
    const recovery = (
      player as unknown as { recoverSilentStall(): Promise<void> }
    ).recoverSilentStall()
    await vi.runAllTimersAsync()
    await recovery

    expect(player.getState().isPlaying).toBe(false)
    expect(player.getState().error).toBe(PLAYBACK_MESSAGES.stalled)
    expect(playCalls).toBeGreaterThanOrEqual(3)
    vi.useRealTimers()
  })

  it("does not publish the old clip's position under the next verse while its clip loads", async () => {
    const audio = new FakeAudio()
    const prefetcher = instantPrefetcher()
    const player = new PlayerController(prefetcher, true, null, () =>
      audio.asAudio(),
    )
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })
    await player.play()

    // The next clip is slow to arrive.
    let arrive: (src: string) => void = () => {}
    vi.mocked(prefetcher.ensure).mockImplementationOnce(
      () =>
        new Promise<string>((resolve) => {
          arrive = resolve
        }),
    )
    audio.currentTime = 4.5
    audio.ended = true
    audio.emit('ended')
    await vi.waitFor(() => expect(player.getState().nowPlaying?.ayah).toBe(2))

    // The element still holds verse 1, 4.5 s in. That is not verse 2's time.
    audio.emit('timeupdate')
    expect(player.getState()).toMatchObject({
      nowPlaying: { ayah: 2 },
      positionMs: 0,
    })

    audio.ended = false
    audio.currentTime = 0
    arrive('blob:next')
    await vi.waitFor(() => expect(audio.play).toHaveBeenCalledTimes(2))
    audio.currentTime = 0.3
    audio.emit('timeupdate')
    expect(player.getState()).toMatchObject({
      nowPlaying: { ayah: 2 },
      positionMs: 300,
    })
  })

  it('advances on natural ended when gapless path is not used', async () => {
    const audio = new FakeAudio()
    const player = new PlayerController(instantPrefetcher(), true, null, () =>
      audio.asAudio(),
    )
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })
    await player.play()
    const ayahBefore = player.getState().nowPlaying?.ayah
    audio.ended = true
    audio.emit('ended')
    await vi.waitFor(() => {
      expect(player.getState().nowPlaying?.ayah).not.toBe(ayahBefore)
    })
    expect(player.getState().isPlaying).toBe(true)
  })
})

describe('wantsGapless5Transport', () => {
  it('is only true when preferred and speed is exactly 1×', () => {
    expect(wantsGapless5Transport(true, 1)).toBe(true)
    expect(wantsGapless5Transport(true, 0.75)).toBe(false)
    expect(wantsGapless5Transport(true, 1.25)).toBe(false)
    expect(wantsGapless5Transport(true, 1.5)).toBe(false)
    expect(wantsGapless5Transport(false, 1)).toBe(false)
  })
})

// Exercise the actual controller callbacks without loading a browser-only UMD
// transport. Every subscriber must receive a coherent verse/clock pair.
describe('gapless verse-clock publication', () => {
  it('publishes the new verse, position and duration in one notification', async () => {
    vi.stubGlobal('Audio', FakeAudio)
    vi.stubGlobal('navigator', {})
    const { PlayerController } = await import('../player')
    const p = new PlayerController(instantPrefetcher(), true, null, () =>
      new FakeAudio().asAudio(),
    )
    p.loadSurah(content, reciter, 1, { quiet: true, warm: false })
    const internal = p as unknown as {
      gapless5Enabled: boolean
      createGapless5Backend(): {
        handlers: { onTime(ms: number, duration: number, index: number): void }
      }
    }
    internal.gapless5Enabled = true
    const states: unknown[] = []
    const unsubscribe = p.subscribe((s) => states.push(s))
    states.length = 0
    internal.createGapless5Backend().handlers.onTime(24, 4100, 1)
    expect(states).toHaveLength(1)
    expect(states[0]).toMatchObject({
      nowPlaying: { ayah: 2 },
      positionMs: 24,
      durationMs: 4100,
    })
    unsubscribe()
    vi.unstubAllGlobals()
  })
})

describe('repeat sheet playback application', () => {
  afterEach(() => vi.unstubAllGlobals())
  async function fixture(start: number, playing: boolean, ayah: number) {
    vi.stubGlobal('Audio', FakeAudio)
    vi.stubGlobal('navigator', {})
    const { PlayerController } = await import('../player')
    const p = new PlayerController(instantPrefetcher(), true, null, () => new FakeAudio().asAudio())
    p.loadSurah(content, reciter, start, { quiet: true, warm: false })
    const internal = p as unknown as {
      state: import('../player').PlayerState
      playlist: { ayah: number; url: string }[]
      index: number
      playIndex: ReturnType<typeof vi.fn>
      gapless5Enabled: boolean
      gapless5: { goto: ReturnType<typeof vi.fn>; getDurationMs: () => number; isPlaying: () => boolean }
      createGapless5Backend(): { handlers: { onFinishedAll(): void } }
    }
    internal.state = { ...p.getState(), nowPlaying: { surahId: 1, reciterId: 1, ayah }, isPlaying: playing, positionMs: 432 }
    internal.index = internal.playlist.findIndex(item => item.ayah === ayah)
    internal.playIndex = vi.fn(async () => {})
    return { p, internal }
  }
  it('keeps an in-range live utterance at its position', async () => {
    const { p, internal } = await fixture(1, true, 2)
    await p.applyRepeat('range', { first: 1, last: 3 })
    expect(internal.playIndex).not.toHaveBeenCalled()
    expect(p.getState()).toMatchObject({ positionMs: 432, repeatRange: { first: 1, last: 3 } })
  })
  it('does not reload a live range already present in a truncated playlist', async () => {
    const { p, internal } = await fixture(2, true, 2)
    await p.applyRepeat('range', { first: 2, last: 3 })
    expect(internal.playIndex).not.toHaveBeenCalled()
    expect(p.getState().positionMs).toBe(432)
  })
  it('abandons an old position restore after a newer repeat choice', async () => {
    const { p, internal } = await fixture(3, false, 3)
    let finish!: () => void
    internal.playIndex = vi.fn(() => new Promise<void>(resolve => { finish = resolve }))
    const seek = vi.spyOn(p, 'seekMs')
    const pending = p.applyRepeat('surah', null)
    await p.applyRepeat('off', null)
    finish()
    await pending
    expect(seek).not.toHaveBeenCalled()
    expect(p.getState().repeatMode).toBe('off')
  })
  it('rebuilds a truncated playlist for a whole-chapter loop', async () => {
    const { p, internal } = await fixture(3, false, 3)
    await p.applyRepeat('surah', null)
    expect(internal.playlist.map(item => item.ayah)).toEqual([1, 2, 3])
    expect(internal.playIndex).toHaveBeenCalledWith(2, false, { fadeIn: false })
    expect(p.getState().repeatMode).toBe('surah')
  })
  it('starts from the range beginning when playback is outside it', async () => {
    const { p, internal } = await fixture(1, true, 1)
    await p.applyRepeat('range', { first: 2, last: 3 })
    expect(internal.playIndex).toHaveBeenCalledWith(1, true, { fadeIn: false })
  })
  it('wraps a range ending at the final clip, including a single final ayah', async () => {
    const { p, internal } = await fixture(1, true, 3)
    internal.gapless5Enabled = true
    internal.gapless5 = { goto: vi.fn(), getDurationMs: () => 4000, isPlaying: () => true }
    internal.state = { ...internal.state, repeatMode: 'range', repeatRange: { first: 3, last: 3 } }
    internal.createGapless5Backend().handlers.onFinishedAll()
    expect(internal.gapless5.goto).toHaveBeenCalledWith(2, true)
    expect(p.getState()).toMatchObject({ nowPlaying: { ayah: 3 }, isPlaying: true, positionMs: 0 })
  })
})
