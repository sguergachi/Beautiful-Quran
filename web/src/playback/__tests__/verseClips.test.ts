import { createHash } from 'node:crypto'
import { afterEach, describe, expect, it } from 'vitest'
import { audioUrl, type Reciter } from '../../data/models'
import { parseEveryayahUrl } from '../recitationDownloads'
import {
  dropStaleVerseClips,
  fetchVerseClip,
  installVerseClipFetch,
  isVerseClipUrl,
  mp3FrameLength,
  parseVerseClipUrl,
  resetVerseClipFetchForTests,
  silenceLead,
  verseClipFingerprint,
  verseClipHeadMs,
  type VerseClip,
} from '../verseClips'
import { AUDIO_CACHE, AUDIO_KEEP_CACHE } from '../audioPrefetch'

/** One synthetic MPEG-1 Layer III stereo frame, no CRC, 128 kbps (same as the Android test). */
function frame(pad: number, seed: number): Uint8Array {
  const length = 417 + pad
  const out = new Uint8Array(length)
  out.set([0xff, 0xfb, 0x90 | (pad << 1), 0])
  for (let i = 4; i < length; i++) out[i] = i < 36 ? (((seed * 31 + i * 7) & 0xff) | 0x81) : (seed + i) & 0xff
  return out
}

function leadAndOne(): { data: Uint8Array; lead: number } {
  const parts: Uint8Array[] = []
  let size = 0
  for (const pad of [0, 1, 0]) {
    const f = frame(pad, size % 251)
    parts.push(f)
    size += f.length
  }
  parts.push(frame(0, 9))
  const data = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const p of parts) {
    data.set(p, at)
    at += p.length
  }
  return { data, lead: 417 + 418 + 417 }
}

const sha = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex')

describe('silenceLead', () => {
  it('matches the build tool and the Android transform byte for byte', () => {
    const { data, lead } = leadAndOne()
    expect(lead).toBe(1252)
    expect(sha(data)).toBe('81944a7dd6b99dabcd6b668373cf105b8a02b25618cf55e931f13ab954e4e40c')
    const out = data.slice()
    silenceLead(out, lead)
    // Digests from tools/build_tawfeek_verse_index.py silence_lead on the same bytes.
    expect(sha(out)).toBe('2c4fe229aebe82d3ec614cf42b26b6ba9ae7d537fd7673ac170e69dc52a49a01')
    expect(out.subarray(lead)).toEqual(data.subarray(lead))
  })

  it('refuses frames it cannot make silent', () => {
    const { data, lead } = leadAndOne()
    expect(() => silenceLead(data.slice(0, lead - 1), lead - 1)).toThrow()
    const mono = data.slice(0, lead)
    mono[3]! |= 0xc0
    expect(() => silenceLead(mono, lead)).toThrow()
    const crc = data.slice(0, lead)
    crc[1]! &= 0xfe
    expect(() => silenceLead(crc, lead)).toThrow()
    expect(mp3FrameLength(data, 0)).toBe(417)
    expect(mp3FrameLength(data, 417)).toBe(418)
  })
})

describe('verse-clip URLs', () => {
  const range: Reciter = { id: 20, slug: 'Tawfeek_As-Sawaigh_128kbps', name: 'T', style: 'Murattal', hasTimings: true, rangeSourced: true }
  const plain: Reciter = { id: 1, slug: 'Alafasy_128kbps', name: 'A', style: 'Murattal', hasTimings: true }

  it('addresses range-sourced verses on the verse-clip host and everyone else on EveryAyah', () => {
    expect(audioUrl(range, 2, 5)).toBe('https://verse-clips.invalid/data/Tawfeek_As-Sawaigh_128kbps/002005.mp3')
    expect(audioUrl(plain, 2, 5)).toBe('https://everyayah.com/data/Alafasy_128kbps/002005.mp3')
    expect(isVerseClipUrl(audioUrl(range, 2, 5))).toBe(true)
    expect(isVerseClipUrl(audioUrl(plain, 2, 5))).toBe(false)
    expect(parseVerseClipUrl(audioUrl(range, 114, 6))).toEqual({ slug: range.slug, surah: 114, ayah: 6 })
  })

  it('downloads recognise them like any EveryAyah verse', () => {
    expect(parseEveryayahUrl(audioUrl(range, 114, 6))).toEqual({ slug: range.slug, surah: 114, ayah: 6 })
  })
})

describe('fetchVerseClip', () => {
  const { data: slice, lead } = leadAndOne()
  const file = new Uint8Array(5000 + slice.length + 300).fill(7)
  file.set(slice, 5000)
  const clip: VerseClip = {
    sourceUrl: 'https://cdn.example.invalid/002.mp3',
    sourceBytes: file.length,
    startByte: 5000,
    endByte: 5000 + slice.length,
    // Two reservoir frames, then the warm-up frame: the browser silences all three.
    leadBytes: 417 + 418,
    trimSamples: 3 * 1152,
  }
  const lookup = () => clip
  const expected = slice.slice()
  silenceLead(expected, lead)

  const serving = (opts: { total?: number; status?: number; length?: number } = {}) => {
    const calls: { url: string; range: string | undefined }[] = []
    const upstream = async (url: string, init?: RequestInit) => {
      const range = (init?.headers as Record<string, string> | undefined)?.Range
      calls.push({ url, range })
      const [, a, b] = /bytes=(\d+)-(\d+)/.exec(range ?? '')!
      return new Response(file.slice(Number(a), Number(b) + 1).slice(0, opts.length), {
        status: opts.status ?? 206,
        headers: { 'Content-Range': `bytes ${a}-${b}/${opts.total ?? file.length}` },
      })
    }
    return { calls, upstream }
  }

  it('returns the verse as one ordinary response with everything before it silenced', async () => {
    const { calls, upstream } = serving()
    const response = await fetchVerseClip('https://verse-clips.invalid/data/x/002005.mp3', lookup, upstream)
    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toBe('audio/mpeg')
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(expected)
    expect(calls).toEqual([{ url: clip.sourceUrl, range: `bytes=5000-${5000 + slice.length - 1}` }])
  })

  it('refuses a recording of another size, a non-range reply, or a short body', async () => {
    const url = 'https://verse-clips.invalid/data/x/002005.mp3'
    await expect(fetchVerseClip(url, lookup, serving({ total: file.length + 1 }).upstream)).rejects.toThrow(/expected/)
    await expect(fetchVerseClip(url, lookup, serving({ status: 200 }).upstream)).rejects.toThrow(/HTTP 200/)
    await expect(fetchVerseClip(url, lookup, serving({ length: 100 }).upstream)).rejects.toThrow(/bytes, expected/)
    await expect(fetchVerseClip(url, () => undefined, serving().upstream)).rejects.toThrow(/No audio range/)
  })

  describe('the global fetch shim', () => {
    afterEach(() => resetVerseClipFetchForTests())

    it('serves verse-clip URLs and leaves every other request alone', async () => {
      const seen: string[] = []
      const { upstream } = serving()
      const target = {
        fetch: async (input: RequestInfo | URL, init?: RequestInit) => {
          seen.push(typeof input === 'string' ? input : String(input))
          return upstream(typeof input === 'string' ? input : String(input), init)
        },
      } as unknown as typeof globalThis
      installVerseClipFetch(lookup, target)
      const verse = await target.fetch('https://verse-clips.invalid/data/x/002005.mp3')
      expect(new Uint8Array(await verse.arrayBuffer())).toEqual(expected)
      expect(seen).toEqual([clip.sourceUrl])
      await target.fetch('https://everyayah.com/data/Alafasy_128kbps/001001.mp3', { headers: { Range: 'bytes=0-9' } }).catch(() => {})
      expect(seen[1]).toBe('https://everyayah.com/data/Alafasy_128kbps/001001.mp3')
    })
  })
})

describe('stale verse clips', () => {
  const clip: VerseClip = {
    sourceUrl: 'https://server.example/023/112.mp3',
    sourceBytes: 1000,
    startByte: 100,
    endByte: 500,
    leadBytes: 835,
    trimSamples: 3 * 1152,
  }

  it('the browser hears a verse after its whole silenced head', () => {
    expect(verseClipHeadMs(clip)).toBeCloseTo((3 * 1152 * 1000) / 44_100, 6)
  })

  it('any change to a range or its source changes the fingerprint', () => {
    const base = verseClipFingerprint([['T/112:1', clip]])
    expect(verseClipFingerprint([['T/112:1', { ...clip }]])).toBe(base)
    for (const changed of [
      { ...clip, endByte: 501 },
      { ...clip, trimSamples: 2 * 1152 },
      { ...clip, sourceUrl: 'https://mirror.example/023/112.mp3' },
    ]) {
      expect(verseClipFingerprint([['T/112:1', changed]])).not.toBe(base)
    }
  })

  function fakeCaches(urls: Record<string, string[]>) {
    const stores = new Map(Object.entries(urls).map(([name, list]) => [name, new Set(list)]))
    return {
      stores,
      caches: {
        open: async (name: string) => {
          const set = stores.get(name) ?? new Set<string>()
          stores.set(name, set)
          return {
            keys: async () => [...set].map((url) => ({ url }) as Request),
            delete: async (request: Request) => set.delete(request.url),
          }
        },
      } as unknown as CacheStorage,
    }
  }

  it('drops only that reciter\'s cached verses, once per change', async () => {
    const own = 'https://verse-clips.invalid/data/T/112001.mp3'
    const other = 'https://verse-clips.invalid/data/U/112001.mp3'
    const everyayah = 'https://everyayah.com/data/A/112001.mp3'
    const { stores, caches } = fakeCaches({ [AUDIO_CACHE]: [own, other, everyayah], [AUDIO_KEEP_CACHE]: [own] })
    const stamps = new Map<string, string>()
    const store = { getItem: (k: string) => stamps.get(k) ?? null, setItem: (k: string, v: string) => void stamps.set(k, v) }
    await dropStaleVerseClips(new Map([['T', 'one']]), store, caches)
    expect([...stores.get(AUDIO_CACHE)!]).toEqual([other, everyayah])
    expect([...stores.get(AUDIO_KEEP_CACHE)!]).toEqual([])

    stores.get(AUDIO_KEEP_CACHE)!.add(own)
    await dropStaleVerseClips(new Map([['T', 'one']]), store, caches)
    expect([...stores.get(AUDIO_KEEP_CACHE)!]).toEqual([own])
    await dropStaleVerseClips(new Map([['T', 'two']]), store, caches)
    expect([...stores.get(AUDIO_KEEP_CACHE)!]).toEqual([])
  })
})
