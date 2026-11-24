/**
 * Verses of a reciter who exists only as whole-surah recordings.
 *
 * Mirrors Android `VerseClipDataSource`/`Mp3LeadSilencer`. Such a reciter's
 * verses are addressed like everyone else's — one URL per verse, on a host that
 * never resolves — so the prefetcher, download manager and player key on a
 * per-verse URL. A `fetch` shim installed once the database is open turns each
 * such URL into a byte range of the real surah MP3 and returns the verse as an
 * ordinary 200 response; nothing else crosses the wire.
 *
 *  - A verse's first `leadBytes` hold the bit reservoir the frames after them
 *    need, and one warm-up frame follows (Android decodes it so the verse opens
 *    sample-exact, then trims both; see `trimSamples`). The browser has no such
 *    trim, so every frame before the verse is silenced ([silenceLead]) and
 *    kept at the front: the verse never opens on a replay of the one before.
 *    Its word clock then runs `trimSamples` early, about 50 ms.
 *  - The surah file's total size must be what the range was measured on
 *    (`Content-Range`); a re-encoded upstream file would shift every range, so
 *    the shim fails instead of serving the wrong words.
 */

import { VERSE_CLIP_HOST } from '../data/models'

export { VERSE_CLIP_HOST }

export interface VerseClip {
  sourceUrl: string
  sourceBytes: number
  startByte: number
  endByte: number
  leadBytes: number
  /** Decoded samples ahead of the verse itself: whole frames, reservoir lead and warm-up. */
  trimSamples: number
}

export interface VerseClipRef {
  slug: string
  surah: number
  ayah: number
}

const VERSE_CLIP_URL = new RegExp(
  `^https://${VERSE_CLIP_HOST.replace(/\./g, '\\.')}/data/([^/]+)/(\\d{3})(\\d{3})\\.mp3$`,
)

export function parseVerseClipUrl(url: string): VerseClipRef | null {
  const match = VERSE_CLIP_URL.exec(url)
  if (!match) return null
  return { slug: match[1]!, surah: Number(match[2]), ayah: Number(match[3]) }
}

export const isVerseClipUrl = (url: string) => parseVerseClipUrl(url) != null

const BITRATES_KBPS = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0]
const SAMPLE_RATE = 44_100
const SIDE_INFO_FIRST_GRANULE_BIT = 20
const GRANULE_BITS = 59
const CLEARED_BITS = 21

/** Length of the MPEG-1 Layer III stereo, CRC-less frame starting at [offset], or -1. */
export function mp3FrameLength(data: Uint8Array, offset: number): number {
  if (offset + 4 > data.length) return -1
  const header =
    ((data[offset]! << 24) | (data[offset + 1]! << 16) | (data[offset + 2]! << 8) | data[offset + 3]!) >>> 0
  const sync = header >>> 21
  const version = (header >>> 19) & 3
  const layer = (header >>> 17) & 3
  const protectionBit = (header >>> 16) & 1
  const bitrate = BITRATES_KBPS[(header >>> 12) & 15]!
  const rate = (header >>> 10) & 3
  const padding = (header >>> 9) & 1
  const mono = ((header >>> 6) & 3) === 3
  if (sync !== 0x7ff || version !== 3 || layer !== 1 || rate !== 0 || bitrate === 0 || protectionBit === 0 || mono) {
    return -1
  }
  return Math.floor((144 * bitrate * 1000) / SAMPLE_RATE) + padding
}

/**
 * Zeros the granule data of every frame in `data[0, leadBytes)`, in place, so
 * those frames decode as silence while their bytes still feed the bit
 * reservoir. Same transform as `silence_lead` in
 * tools/build_tawfeek_verse_index.py and `Mp3LeadSilencer` on Android.
 */
export function silenceLead(data: Uint8Array, leadBytes: number): Uint8Array {
  let pos = 0
  while (pos < leadBytes) {
    const length = mp3FrameLength(data, pos)
    if (length <= 0 || pos + length > leadBytes) {
      throw new Error(`lead of ${leadBytes} bytes is not whole MP3 frames`)
    }
    for (let granule = 0; granule < 4; granule++) {
      const first = SIDE_INFO_FIRST_GRANULE_BIT + granule * GRANULE_BITS
      for (let bit = first; bit < first + CLEARED_BITS; bit++) {
        data[pos + 4 + (bit >> 3)]! &= ~(1 << (7 - (bit & 7))) & 0xff
      }
    }
    pos += length
  }
  return data
}

const SAMPLES_PER_FRAME = 1_152

/** Bytes of the whole frames that decode to [samples] samples at the front of [data]. */
export function headBytes(data: Uint8Array, samples: number): number {
  let pos = 0
  for (let frame = 0; frame < samples / SAMPLES_PER_FRAME; frame++) {
    const length = mp3FrameLength(data, pos)
    if (length <= 0) throw new Error(`no MP3 frame at byte ${pos} of the verse's head`)
    pos += length
  }
  return pos
}

export type VerseClipLookup = (ref: VerseClipRef) => VerseClip | undefined
type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

/**
 * One verse as a standalone `Response`: the recording's byte range, lead
 * silenced. Rejects if the recording is not the one the range was measured on.
 */
export async function fetchVerseClip(
  url: string,
  lookup: VerseClipLookup,
  upstream: FetchLike,
  init?: RequestInit,
): Promise<Response> {
  const ref = parseVerseClipUrl(url)
  const clip = ref && lookup(ref)
  if (!clip) throw new TypeError(`No audio range for ${url}`)
  const response = await upstream(clip.sourceUrl, {
    mode: 'cors',
    credentials: 'omit',
    signal: init?.signal,
    headers: { Range: `bytes=${clip.startByte}-${clip.endByte - 1}` },
  })
  if (response.status !== 206) {
    throw new Error(`HTTP ${response.status} for a byte range of ${clip.sourceUrl}`)
  }
  const total = Number(/\/(\d+)\s*$/.exec(response.headers.get('content-range') ?? '')?.[1])
  if (total !== clip.sourceBytes) {
    throw new Error(
      `Surah recording is ${Number.isFinite(total) ? total : 'of unknown size'} bytes, expected ${clip.sourceBytes}`,
    )
  }
  const bytes = new Uint8Array(await response.arrayBuffer())
  if (bytes.length !== clip.endByte - clip.startByte) {
    throw new Error(`Verse is ${bytes.length} bytes, expected ${clip.endByte - clip.startByte}`)
  }
  silenceLead(bytes, headBytes(bytes, clip.trimSamples))
  return new Response(bytes, {
    status: 200,
    headers: { 'Content-Type': 'audio/mpeg', 'Content-Length': String(bytes.length) },
  })
}

let installed = false

/** Routes verse-clip URLs through [fetchVerseClip]; every other request is untouched. */
export function installVerseClipFetch(lookup: VerseClipLookup, target: typeof globalThis = globalThis): void {
  if (installed || typeof target.fetch !== 'function') return
  installed = true
  const original = target.fetch.bind(target)
  target.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    if (!isVerseClipUrl(url)) return original(input, init)
    return fetchVerseClip(url, lookup, (u, i) => original(u, i), init)
  }) as typeof fetch
}

/** For tests: forget that the shim was installed. */
export function resetVerseClipFetchForTests(): void {
  installed = false
}
