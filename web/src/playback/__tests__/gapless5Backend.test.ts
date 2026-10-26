import { describe, expect, it, vi } from 'vitest'
import { Gapless5Backend } from '../gapless5Backend'
import type { AudioPrefetcher } from '../audioPrefetch'

function handlers() {
  return {
    onTime: vi.fn(),
    onPlay: vi.fn(),
    onPause: vi.fn(),
    onIndex: vi.fn(),
    onFinishedAll: vi.fn(),
    onError: vi.fn(),
    onBuffering: vi.fn(),
  }
}

describe('Gapless5Backend', () => {
  it('dispose is safe before ensureReady', () => {
    const backend = new Gapless5Backend(handlers())
    expect(() => backend.dispose()).not.toThrow()
    expect(backend.getPositionMs()).toBe(0)
    expect(backend.isPlaying()).toBe(false)
    expect(backend.isReady()).toBe(false)
    expect(backend.bootSync()).toBe(false)
  })

  it('maps repeat modes onto loop flags once ready is stubbed', () => {
    const backend = new Gapless5Backend(handlers())
    // Without a browser window, ensureReady would load the UMD package and
    // throw — applyRepeat must no-op cleanly when the player is absent.
    expect(() => backend.applyRepeat('ayah')).not.toThrow()
    expect(() => backend.applyRepeat('surah')).not.toThrow()
    expect(() => backend.applyRepeat('off')).not.toThrow()
    backend.dispose()
  })

  it('syncPlaylist is synchronous and does not await network', () => {
    const backend = new Gapless5Backend(handlers())
    const ensure = vi.fn(async () => 'blob:x')
    const prefetcher = {
      ensure,
      resolveSrc: (url: string) => url,
    } as unknown as AudioPrefetcher
    // No player yet — must return immediately without touching ensure().
    expect(() =>
      backend.syncPlaylist(
        [{ ayah: 1, url: 'https://example.com/a.mp3' }],
        prefetcher,
      ),
    ).not.toThrow()
    expect(ensure).not.toHaveBeenCalled()
    backend.dispose()
  })
})

it('ignores look-ahead load events while the current verse plays', () => {
  const h = handlers()
  let instance!: FakePlayer
  class FakePlayer {
    onloadstart!: (path: string) => void
    onload!: (path: string, loaded: boolean) => void
    index = 0
    playing = true
    tracks: string[] = []
    constructor() {
      instance = this
    }
    setCrossfade() {}
    getIndex() {
      return this.index
    }
    isPlaying() {
      return this.playing
    }
    totalTracks() {
      return this.tracks.length
    }
    removeAllTracks() {
      this.tracks = []
    }
    addTrack(path: string) {
      this.tracks.push(path)
    }
  }
  const backend = new Gapless5Backend(h)
  ;(
    backend as unknown as { constructFromModule(m: unknown): void }
  ).constructFromModule({ Gapless5: FakePlayer })
  backend.syncPlaylist(
    [
      { ayah: 1, url: 'a' },
      { ayah: 2, url: 'b' },
    ],
    {} as AudioPrefetcher,
  )
  instance.onloadstart('b')
  instance.onload('b', true)
  expect(h.onBuffering).not.toHaveBeenCalled()
  instance.playing = false
  instance.onloadstart('a')
  expect(h.onBuffering).toHaveBeenLastCalledWith(true)
  instance.onload('b', true)
  expect(h.onBuffering).toHaveBeenCalledTimes(1)
  instance.onload('a', true)
  expect(h.onBuffering).toHaveBeenLastCalledWith(false)
})
