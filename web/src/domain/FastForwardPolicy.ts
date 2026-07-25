/**
 * Pure transport skip rules for the reader fast-forward control.
 * Port of Android `ui/reader/FastForwardPolicy.kt`.
 *
 * Long ayahs (≥ [LONG_AYAH_MIN_WORDS] segments) offer a one-shot midpoint skip.
 * The midpoint is consumed by **intent** (we already issued that seek), not by
 * waiting for positionMs to catch up — seeks can be async, so a second tap
 * while position is still pre-midpoint must advance to the next ayah rather
 * than re-seeking the same midpoint forever.
 */

export type FastForwardAction =
  | { kind: 'midpoint'; ayah: number; positionMs: number }
  | { kind: 'ayah'; ayah: number }
  | { kind: 'none' }

export const LONG_AYAH_MIN_WORDS = 20
export const MIDPOINT_SEEK_GRACE_MS = 1_000

export function fastForwardAction(args: {
  ayah: number
  positionMs: number
  ayahCount: number
  midpointMs: number | null
  /** Ayah number that already received a midpoint skip, or 0. */
  midpointConsumedForAyah: number
  graceMs?: number
}): FastForwardAction {
  const {
    ayah,
    positionMs,
    ayahCount,
    midpointMs,
    midpointConsumedForAyah,
    graceMs = MIDPOINT_SEEK_GRACE_MS,
  } = args
  if (ayah < 1) return { kind: 'none' }
  const canMidSkip =
    midpointMs != null &&
    midpointConsumedForAyah !== ayah &&
    positionMs < midpointMs - graceMs
  if (canMidSkip) {
    return { kind: 'midpoint', ayah, positionMs: midpointMs }
  }
  if (ayah < ayahCount) return { kind: 'ayah', ayah: ayah + 1 }
  return { kind: 'none' }
}

/** After [action], the ayah marked as midpoint-consumed (0 if cleared). */
export function nextConsumedAyah(action: FastForwardAction): number {
  return action.kind === 'midpoint' ? action.ayah : 0
}

/**
 * Start of the first segment at or after half the ayah's timed span, or null
 * when the ayah is too short for a mid-skip.
 */
export function midpointMs(
  segments: ReadonlyArray<{ startMs: number; endMs: number }>,
): number | null {
  if (segments.length < LONG_AYAH_MIN_WORDS) return null
  let end = 0
  for (const s of segments) if (s.endMs > end) end = s.endMs
  if (end <= 0) return segments[Math.floor(segments.length / 2)]!.startMs
  const half = end / 2
  for (const s of segments) if (s.startMs >= half) return s.startMs
  return segments[segments.length - 1]!.startMs
}
