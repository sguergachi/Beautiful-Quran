import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Reciter, SurahContent } from '../../data/models'
import type { AudioPrefetcher } from '../audioPrefetch'
import { normalizeSettings } from '../../data/settings'
import { playbackSummary, verseGapLabel } from '../../data/playbackPolicy'
import { wantsGapless5Transport } from '../gaplessPolicy'
import { verseGapFollows } from '../verseGap'
import { FakeAudio } from './fakeAudio'

let PlayerController: typeof import('../player').PlayerController

const reciter: Reciter = {
  id: 1,
  slug: 'Alafasy_128kbps',
  name: 'Mishary Rashid Alafasy',
  style: 'Murattal',
  hasTimings: true,
}

function chapter(id: number, ayahCount: number): SurahContent {
  return {
    surah: {
      id,
      nameArabic: '',
      nameTransliteration: `Surah ${id}`,
      nameTranslation: '',
      revelationPlace: '',
      ayahCount,
    },
    ayahs: Array.from({ length: ayahCount }, (_, i) => ({
      surahId: id,
      number: i + 1,
      text: '',
      translation: '',
      page: 1,
      words: [],
    })),
  }
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

describe('verse gap rules', () => {
  it('follows a verse that something comes after, never the basmalah', () => {
    expect(verseGapFollows(1, true)).toBe(true)
    expect(verseGapFollows(7, false)).toBe(false)
    expect(verseGapFollows(0, true)).toBe(false)
    expect(verseGapFollows(undefined, true)).toBe(false)
  })

  it('keeps the setting to whole seconds within the choices', () => {
    expect(normalizeSettings().verseGapSeconds).toBe(0)
    expect(normalizeSettings({ verseGapSeconds: 3 }).verseGapSeconds).toBe(3)
    expect(normalizeSettings({ verseGapSeconds: 60 }).verseGapSeconds).toBe(5)
    expect(normalizeSettings({ verseGapSeconds: -1 }).verseGapSeconds).toBe(0)
    expect(normalizeSettings({ verseGapSeconds: 'x' as unknown as number }).verseGapSeconds).toBe(0)
  })

  it('labels the choices and the Settings row', () => {
    expect([0, 1, 2, 3, 5].map(verseGapLabel)).toEqual(['None', '1 s', '2 s', '3 s', '5 s'])
    expect(playbackSummary(0)).toBe('Verses follow one another')
    expect(playbackSummary(2)).toBe('2 s between verses')
  })

  it('holds the gap on the media-element path, not Gapless-5', () => {
    expect(wantsGapless5Transport(true, 1, 0)).toBe(true)
    expect(wantsGapless5Transport(true, 1, 2000)).toBe(false)
  })
})

describe('PlayerController pause between verses', () => {
  let audio: FakeAudio

  beforeAll(async () => {
    vi.stubGlobal('Audio', FakeAudio)
    vi.stubGlobal('navigator', {})
    PlayerController = (await import('../player')).PlayerController
  })

  beforeEach(() => {
    vi.useFakeTimers()
    const raf = vi.fn(() => 1)
    const caf = vi.fn()
    vi.stubGlobal('window', {
      requestAnimationFrame: raf,
      cancelAnimationFrame: caf,
      setTimeout: globalThis.setTimeout,
      clearTimeout: globalThis.clearTimeout,
    })
    vi.stubGlobal('requestAnimationFrame', raf)
    vi.stubGlobal('cancelAnimationFrame', caf)
    audio = new FakeAudio()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.stubGlobal('Audio', FakeAudio)
    vi.stubGlobal('navigator', {})
    vi.restoreAllMocks()
  })

  async function playing(content: SurahContent, gapMs: number, session?: { playbackState: string }) {
    const player = new PlayerController(
      instantPrefetcher(),
      true,
      (session ?? null) as unknown as MediaSession | null,
      () => audio.asAudio(),
    )
    await player.setVerseGap(gapMs)
    player.loadSurah(content, reciter, 1, { quiet: true, warm: false })
    await player.play()
    return player
  }

  async function endClip() {
    audio.paused = true
    audio.ended = true
    audio.emit('pause')
    audio.emit('ended')
    await vi.advanceTimersByTimeAsync(0)
  }

  it('holds the finished verse in silence, still playing, then plays the next', async () => {
    const player = await playing(chapter(1, 3), 2000)
    expect(player.getState().nowPlaying?.ayah).toBe(1)
    await endClip()

    expect(player.getState().nowPlaying?.ayah).toBe(1)
    expect(player.getState().isPlaying).toBe(true)
    expect(audio.paused).toBe(true)

    await vi.advanceTimersByTimeAsync(1999)
    expect(player.getState().nowPlaying?.ayah).toBe(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(player.getState().nowPlaying?.ayah).toBe(2)
    expect(player.getState().isPlaying).toBe(true)
    expect(audio.paused).toBe(false)
  })

  it('plays straight on without a gap when none is chosen', async () => {
    const player = await playing(chapter(1, 3), 0)
    await endClip()
    expect(player.getState().nowPlaying?.ayah).toBe(2)
  })

  it('takes no gap after the basmalah', async () => {
    const player = await playing(chapter(2, 3), 2000)
    expect(player.getState().nowPlaying?.ayah).toBe(0)
    await endClip()
    expect(player.getState().nowPlaying?.ayah).toBe(1)
  })

  it('stops at the chapter end with no silence after the last verse', async () => {
    const player = await playing(chapter(1, 1), 2000)
    await endClip()
    expect(player.getState().isPlaying).toBe(false)
  })

  it('stays paused when paused in the silence, and Play goes on to the next verse', async () => {
    const player = await playing(chapter(1, 3), 2000)
    await endClip()
    player.pause()
    expect(player.getState().isPlaying).toBe(false)

    await vi.advanceTimersByTimeAsync(5000)
    expect(player.getState().isPlaying).toBe(false)
    expect(player.getState().nowPlaying?.ayah).toBe(1)

    await player.play()
    expect(player.getState().nowPlaying?.ayah).toBe(2)
    expect(player.getState().isPlaying).toBe(true)
  })

  it('takes the gap before each pass of a repeated verse', async () => {
    const player = await playing(chapter(1, 3), 2000)
    player.setRepeatMode('ayah')
    expect(audio.loop).toBe(false)
    await endClip()
    expect(player.getState().nowPlaying?.ayah).toBe(1)
    await vi.advanceTimersByTimeAsync(2000)
    expect(player.getState().nowPlaying?.ayah).toBe(1)
    expect(audio.paused).toBe(false)
    expect(audio.play).toHaveBeenCalledTimes(2)
  })

  it('plays on from a seek within the finished verse', async () => {
    const player = await playing(chapter(1, 3), 2000)
    await endClip()
    player.seekMs(1000)
    await vi.advanceTimersByTimeAsync(0)
    expect(audio.paused).toBe(false)
    expect(audio.currentTime).toBe(1)
    await vi.advanceTimersByTimeAsync(5000)
    expect(player.getState().nowPlaying?.ayah).toBe(1)
  })

  it('keeps the lock screen on Pause through the silence', async () => {
    vi.stubGlobal('MediaMetadata', class { constructor(readonly track: unknown) {} })
    const session = { playbackState: 'none', metadata: null, setActionHandler: vi.fn() }
    const player = await playing(chapter(1, 3), 2000, session)
    await endClip()
    expect(session.playbackState).toBe('playing')
    await vi.advanceTimersByTimeAsync(2000)
    expect(session.playbackState).toBe('none')
    expect(player.getState().nowPlaying?.ayah).toBe(2)
  })
})
