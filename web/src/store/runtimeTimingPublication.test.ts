import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { PreparedTimings } from '../domain/HighlightEngine'
import type { Segment } from '../data/models'

let publish!: (id: number | null) => void
let app: typeof import('./appStore').appStore
const corpora = new Map<string, Map<number, Segment[]>>()
const revokeWords = vi.fn(async () => undefined)
const playback = {
  nowPlaying: { surahId: 2, ayah: 1, reciterId: 1 },
  isPlaying: true, isBuffering: false, positionMs: 45, durationMs: 100, repeatRange: null,
}
const firstPass: Segment[] = [
  { position: 1, startMs: 10, endMs: 20 },
  { position: 2, startMs: 20, endMs: 80 },
]
const repeat: Segment[] = [
  { position: 1, startMs: 10, endMs: 20 },
  { position: 2, startMs: 20, endMs: 30 },
  { position: 1, startMs: 40, endMs: 50 },
  { position: 2, startMs: 50, endMs: 80 },
]

type TimingState = {
  timingSegments: Map<number, Segment[]>
  prepared: Map<number, PreparedTimings>
  forcedHighlight: { ayah: number; seekMs: number } | null
}
function timingState() { return app as unknown as TimingState }

beforeAll(async () => {
  vi.stubGlobal('window', { addEventListener: vi.fn() })
  vi.doMock('../data/runtimeTimings', () => ({
    QF_TIMING_RECITERS: { 1: [7, 'Alafasy_128kbps'], 2: [6, 'Husary_64kbps'] },
    runtimeTimingsCache: {
      subscribe: (listener: typeof publish) => { publish = listener },
      refreshLoaded: vi.fn(), revoke: vi.fn(async () => undefined),
    },
  }))
  vi.doMock('../data/runtimeMushaf', () => ({ runtimeMushafCache: {
    subscribe: vi.fn(), subscribeDiagnostics: vi.fn(), revoke: revokeWords,
  } }))
  vi.doMock('../data/repository', () => ({ QuranRepository: {
    timings: (id: number, surah: number) => corpora.get(`${id}:${surah}`) ?? new Map(),
  } }))
  vi.doMock('../playback/player', () => ({ player: {
    getState: () => playback, subscribe: vi.fn(), pause: vi.fn(),
  } }))
  app = (await import('./appStore')).appStore
})

afterAll(() => {
  for (const name of ['../data/runtimeTimings', '../data/runtimeMushaf', '../data/repository', '../playback/player']) {
    vi.doUnmock(name)
  }
  vi.unstubAllGlobals()
  vi.resetModules()
})

beforeEach(() => {
  revokeWords.mockClear()
  corpora.clear()
  corpora.set('1:1', new Map([[1, firstPass]]))
  corpora.set('1:2', new Map([[1, firstPass]]))
  app.state = {
    ...app.state,
    reciters: [{ id: 1, slug: 'Alafasy_128kbps', name: 'Alafasy', style: 'Murattal', hasTimings: true }],
    settings: { ...app.state.settings, reciterId: 1 },
    content: {
      surah: { id: 2, nameArabic: '', nameTransliteration: '', nameTranslation: '', revelationPlace: '', ayahCount: 1 },
      ayahs: [],
    },
    player: playback as typeof app.state.player,
    activeWord: null,
    rootViewer: null,
  }
  timingState().timingSegments = new Map([[1, firstPass]])
  timingState().prepared = new Map([[1, PreparedTimings.prepare(firstPass)]])
  timingState().forcedHighlight = null
})

describe('live timing publication', () => {
  it('replaces active prepared timings and basmalah without restarting chapter audio', () => {
    corpora.set('1:2', new Map([[1, repeat]]))
    publish(1)
    expect(timingState().prepared.get(1)?.segments).toEqual(repeat)
    expect(app.state.activeWord?.isRepeat).toBe(true)
    expect(timingState().timingSegments.get(0)).toEqual(firstPass)
    expect(app.state.hasTimings).toBe(true)
  })

  it('clears active ink, cached preparations, and pending old clock marks on revocation', () => {
    corpora.clear()
    timingState().forcedHighlight = { ayah: 1, seekMs: 40 }
    publish(null)
    expect(revokeWords).toHaveBeenCalledTimes(1)
    expect(timingState().prepared.size).toBe(0)
    expect(timingState().timingSegments.size).toBe(0)
    expect(timingState().forcedHighlight).toBeNull()
    expect(app.state.activeWord).toBeNull()
    expect(app.state.hasTimings).toBe(false)
  })

  it('does not rebuild active preparations or emit for an unrelated reciter', () => {
    const prepared = timingState().prepared
    const emitted = vi.fn()
    const unsubscribe = app.subscribe(emitted)
    publish(2)
    expect(timingState().prepared).toBe(prepared)
    expect(emitted).not.toHaveBeenCalled()
    unsubscribe()
  })
})
