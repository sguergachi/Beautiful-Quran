import type { Segment } from '../data/models'
import { BASMALAH_UTHMANI } from './Basmalah'

/**
 * Port of Android `domain/BasmalahWash.kt` — where the ink wash across the
 * basmalah calligraphy stands at a moment of the lead-in clip.
 *
 * The clip is Al-Fatihah 1:1 (`001001.mp3`), so its four word timings are the
 * honest clock: the wash crosses ٱللَّهِ when the reciter says "-llāhi", not at a
 * fixed fraction of the file. The artwork is not proportional to time — its
 * elongated sīn gives بِسۡمِ over half the width for a ~0.5 s syllable, while
 * ٱلرَّحِيمِ holds most of the clip in the last quarter — so each word owns the
 * band of artwork its glyphs actually cover ([WORD_END_PROGRESS]).
 *
 * Android additionally paces the wash *inside* each band by tajweed (the madd
 * of ٱلرَّحۡمَٰنِ, the madd ʿāriḍ of the closer). That needs `TajweedPacing`,
 * which is not ported to the web yet (docs/TAJWEED_PACING.md), so here each
 * band is crossed at a constant rate — the same as Android with Ink Lab's
 * pacing toggle off. Progress stays monotone in `positionMs` either way.
 */

/** The four timed words of the basmalah, right to left (Al-Fatihah 1:1). */
export const BASMALAH_WASH_WORDS = BASMALAH_UTHMANI.split(' ')

/**
 * Left edge of each word's ink in the calligraphy, in artwork viewport units
 * (the SVG's `viewBox` width is 608; reading is right to left, so x 608 is
 * where بِسۡمِ starts and x 0 is where ٱلرَّحِيمِ ends).
 */
export const WORD_LEFT_EDGE_X = [260, 220, 140, 0]

/** Artwork width in the same viewport units. */
export const ARTWORK_WIDTH_X = 608

/** Wash progress (0 = right edge, 1 = left) at which each word's band ends. */
export const WORD_END_PROGRESS = WORD_LEFT_EDGE_X.map(
  (x) => (ARTWORK_WIDTH_X - x) / ARTWORK_WIDTH_X,
)

/**
 * Wash progress 0..1 at [positionMs] of the lead-in clip, or null when
 * [segments] cannot time the four words (missing / repeated / non-monotone) and
 * the caller should fall back to the plain clip ramp.
 */
export function basmalahWashProgress(
  positionMs: number,
  segments: Segment[] | null | undefined,
): number | null {
  if (!segments || !timesTheWholeBasmalah(segments)) return null
  const last = segments[segments.length - 1]
  if (positionMs >= last.endMs) return 1

  let index = -1
  for (let i = 0; i < segments.length; i++) {
    if (positionMs >= segments[i].startMs) index = i
  }
  // Before the voice: encoded opening silence, and any reciter whose basmalah
  // starts a second into the file.
  if (index < 0) return 0

  const segment = segments[index]
  const bandStart = index === 0 ? 0 : WORD_END_PROGRESS[index - 1]
  const bandEnd = WORD_END_PROGRESS[index]
  // Karaoke hold: a word owns the gap until the next word starts, so the ink
  // settles into its band rather than stalling short of it.
  const holdEndMs = segments[index + 1]?.startMs ?? segment.endMs
  const holdMs = Math.max(0, holdEndMs - segment.startMs)
  const phase =
    holdMs <= 0 ? 1 : Math.min(1, Math.max(0, (positionMs - segment.startMs) / holdMs))
  return bandStart + (bandEnd - bandStart) * phase
}

/**
 * Whether [segments] are the four basmalah words in order, once each, on a
 * non-decreasing clock — the shape [basmalahWashProgress] maps onto the
 * artwork.
 */
function timesTheWholeBasmalah(segments: Segment[]): boolean {
  if (segments.length !== BASMALAH_WASH_WORDS.length) return false
  let previousEnd = Number.NEGATIVE_INFINITY
  for (let i = 0; i < segments.length; i++) {
    const segment = segments[i]
    if (segment.position !== i + 1) return false
    if (segment.startMs < previousEnd || segment.endMs < segment.startMs) return false
    previousEnd = segment.endMs
  }
  return true
}
