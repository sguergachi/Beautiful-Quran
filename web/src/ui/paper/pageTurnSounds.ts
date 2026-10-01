import { assetUrl } from '../../assetUrl'

/**
 * Paper page-turn audio — the web side of Android `PageTurnSounds`.
 *
 * A flip is three stems: lift (the sheet peeling up), sweep (the arc through
 * the air) and drop (landing). Android releases them as a dragged sheet
 * crosses each phase; a web turn is a fixed animation, so the stems are put
 * on the audio clock at the moments that animation reaches the same phases.
 *
 * Stems are the Android ones (CC0 "Book Flip Sounds" by Voltiment555,
 * opengameart.org, flips 2/8/9), transcoded from `app/src/main/res/raw` to
 * MP3 because Safari's Web Audio does not decode Ogg Vorbis everywhere.
 */

const FLIPS = ['flip2', 'flip8', 'flip9'] as const
const STEMS = ['lift', 'sweep', 'drop'] as const

/** When each stem starts, in ms from the start of the motion, and its pitch. */
export interface FlipSchedule {
  lift: number
  sweep: number
  drop: number
  rate: number
}

/**
 * One leaf, 760 ms (`mushaf-leaf-turn`): the free edge is picked up at once,
 * the sheet is through the vertical a little before half-time, and it is
 * laid flat as the furl lets go.
 */
export const PAGE_TURN_SCHEDULE: FlipSchedule = { lift: 0, sweep: 230, drop: 540, rate: 1 }

/** Phone cover, 1150 ms hinge — Android `playCoverOpen`, verbatim. */
export const COVER_OPEN_SCHEDULE: FlipSchedule = { lift: 0, sweep: 260, drop: 820, rate: 0.92 }

/**
 * Desktop cover, 1700 ms: the closed book slides for the first 510 ms and
 * only then leaves the page block; the board lies flat as the ease runs out.
 */
export const BOOK_OPEN_SCHEDULE: FlipSchedule = { lift: 500, sweep: 860, drop: 1440, rate: 0.92 }

/** Under the recitation, never over it. The stems already sit 6 dB down. */
const GAIN = 0.32
/** A turn whose audio could not start this soon after it began plays nothing. */
const LATE_MS = 160

/** Never the same recording twice running. */
export function nextFlipIndex(last: number, random: number, count: number = FLIPS.length): number {
  const index = Math.min(count - 1, Math.floor(random * count))
  return index === last ? (index + 1) % count : index
}

let context: AudioContext | null = null
const bytes = new Map<string, ArrayBuffer>()
const buffers = new Map<string, AudioBuffer>()
const fetching = new Map<string, Promise<void>>()
const decoding = new Map<string, Promise<void>>()
let lastFlip = -1

/** Fetch separately from audio activation; failed stems remain retryable. */
function fetchStems(): Promise<void[]> {
  return Promise.all(FLIPS.flatMap((flip) => STEMS.map((stem) => {
    const key = `${flip}_${stem}`
    if (bytes.has(key)) return Promise.resolve()
    const pending = fetching.get(key)
    if (pending) return pending
    const load = (async () => {
      try {
        const response = await fetch(assetUrl(`sounds/${key}.mp3`))
        if (response.ok) bytes.set(key, await response.arrayBuffer())
      } catch {
        /* a missing stem stays silent until the next warm */
      } finally {
        fetching.delete(key)
      }
    })()
    fetching.set(key, load)
    return load
  })))
}

function decodeStems(ctx: AudioContext): Promise<void[]> {
  return Promise.all(Array.from(bytes, ([key, data]) => {
    if (buffers.has(key)) return Promise.resolve()
    const pending = decoding.get(key)
    if (pending) return pending
    const load = ctx.decodeAudioData(data.slice(0)).then((buffer) => {
      buffers.set(key, buffer)
    }).catch(() => {
      bytes.delete(key)
    }).finally(() => decoding.delete(key))
    decoding.set(key, load)
    return load
  }))
}

/** Fetch ahead of the first turn, without creating an AudioContext. */
export function warmPageTurnSounds(): void {
  void fetchStems().then(() => context ? decodeStems(context) : undefined)
}

/** Call directly from pointerdown/keydown so strict autoplay policies allow sound. */
export function unlockPageTurnSounds(): void {
  try {
    if (!context) {
      const Ctor = window.AudioContext ??
        (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
      if (!Ctor) return
      context = new Ctor()
    }
    void context.resume().catch(() => {})
    warmPageTurnSounds()
  } catch {
    /* audio unavailable: the book still opens */
  }
}

/** Schedule against motion initiation, including time spent preparing its DOM. */
export function playFlip(schedule: FlipSchedule, initiatedAt = performance.now()): () => void {
  const ctx = context
  const sources: AudioBufferSourceNode[] = []
  let gain: GainNode | null = null
  let cancelled = false
  const cancel = () => {
    cancelled = true
    for (const source of sources) {
      source.stop()
      source.disconnect()
    }
    sources.length = 0
    gain?.disconnect()
  }
  if (!ctx) return cancel
  const flip = FLIPS[(lastFlip = nextFlipIndex(lastFlip, Math.random()))]!
  void fetchStems().then(() => decodeStems(ctx)).then(() => {
    const late = performance.now() - initiatedAt
    if (cancelled || ctx.state !== 'running' || late > LATE_MS) return
    const start = ctx.currentTime - late / 1000
    gain = ctx.createGain()
    gain.gain.value = GAIN
    gain.connect(ctx.destination)
    for (const stem of STEMS) {
      const buffer = buffers.get(`${flip}_${stem}`)
      if (!buffer) continue
      const source = ctx.createBufferSource()
      source.buffer = buffer
      source.playbackRate.value = schedule.rate
      source.connect(gain)
      sources.push(source)
      source.onended = () => {
        source.disconnect()
        const index = sources.indexOf(source)
        if (index >= 0) sources.splice(index, 1)
        if (sources.length === 0) gain?.disconnect()
      }
      source.start(Math.max(ctx.currentTime, start + schedule[stem] / 1000))
    }
  }).catch(() => {
    /* autoplay refused: the turn is simply quiet */
  })
  return cancel
}
