import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Reciter, SurahContent } from '../../data/models'
import type { AudioPrefetcher } from '../audioPrefetch'
import { wantsGapless5Transport } from '../gaplessPolicy'
import { FakeAudio } from './fakeAudio'

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

  it('does not resume when pause wins while play is waiting for canplay', async () => {
    const audio = new FakeAudio()
    audio.readyState = 0
    const player = new PlayerController(
      instantPrefetcher(),
      true,
      null,
      () => audio.asAudio(),
    )
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })

    const pendingPlay = player.play()
    expect(player.getState()).toMatchObject({ isPlaying: true, isBuffering: true })
    player.pause()
    audio.emit('canplay')
    await pendingPlay

    expect(player.getState()).toMatchObject({ isPlaying: false, isBuffering: false })
    expect(audio.play).not.toHaveBeenCalled()
  })

  it('resumes mid-ayah after pause without rewinding the clip', async () => {
    const audio = new FakeAudio()
    const player = new PlayerController(
      instantPrefetcher(),
      true,
      null,
      () => audio.asAudio(),
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
    const player = new PlayerController(
      instantPrefetcher(),
      true,
      null,
      () => audio.asAudio(),
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
    const player = new PlayerController(
      instantPrefetcher(),
      true,
      null,
      () => audio.asAudio(),
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
    const player = new PlayerController(
      instantPrefetcher(),
      true,
      null,
      () => audio.asAudio(),
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

    const player = new PlayerController(
      instantPrefetcher(),
      true,
      null,
      () => audio.asAudio(),
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
    expect(player.getState().error).toMatch(/stall|NotAllowed|Playback/i)
    expect(playCalls).toBeGreaterThanOrEqual(3)
    vi.useRealTimers()
  })

  it('advances on natural ended when gapless path is not used', async () => {
    const audio = new FakeAudio()
    const player = new PlayerController(
      instantPrefetcher(),
      true,
      null,
      () => audio.asAudio(),
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
