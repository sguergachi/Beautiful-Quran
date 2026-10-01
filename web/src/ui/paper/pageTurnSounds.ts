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
type Stem = (typeof STEMS)[number]

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
let buffers: Promise<Map<string, AudioBuffer>> | null = null
let lastFlip = -1

function audioContext(): AudioContext | null {
  if (context) return context
  const Ctor =
    window.AudioContext ??
    (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
  if (!Ctor) return null
  context = new Ctor()
  return context
}

/** Fetch and decode the stems ahead of the first turn. Safe to call often. */
export function warmPageTurnSounds(): void {
  const ctx = audioContext()
  if (!ctx || buffers) return
  buffers = (async () => {
    const loaded = new Map<string, AudioBuffer>()
    await Promise.all(
      FLIPS.flatMap((flip) =>
        STEMS.map(async (stem) => {
          try {
            const response = await fetch(assetUrl(`sounds/${flip}_${stem}.mp3`))
            if (!response.ok) return
            loaded.set(`${flip}_${stem}`, await ctx.decodeAudioData(await response.arrayBuffer()))
          } catch {
            /* a missing stem stays silent */
          }
        }),
      ),
    )
    return loaded
  })()
}

/**
 * Sound one flip against the audio clock. Silent when the browser has not
 * yet been given a gesture, when the stems are still loading, or when the
 * context could only start after the motion was well under way.
 */
export function playFlip(schedule: FlipSchedule): void {
  const ctx = audioContext()
  if (!ctx) return
  warmPageTurnSounds()
  const asked = performance.now()
  const flip = FLIPS[(lastFlip = nextFlipIndex(lastFlip, Math.random()))]!
  void Promise.all([ctx.resume(), buffers])
    .then(([, loaded]) => {
      const late = performance.now() - asked
      if (!loaded || ctx.state !== 'running' || late > LATE_MS) return
      const start = ctx.currentTime - late / 1000
      const gain = ctx.createGain()
      gain.gain.value = GAIN
      gain.connect(ctx.destination)
      const at = (stem: Stem, ms: number) => {
        const buffer = loaded.get(`${flip}_${stem}`)
        if (!buffer) return
        const source = ctx.createBufferSource()
        source.buffer = buffer
        source.playbackRate.value = schedule.rate
        source.connect(gain)
        source.start(Math.max(ctx.currentTime, start + ms / 1000))
      }
      at('lift', schedule.lift)
      at('sweep', schedule.sweep)
      at('drop', schedule.drop)
    })
    .catch(() => {
      /* autoplay refused: the turn is simply quiet */
    })
}
