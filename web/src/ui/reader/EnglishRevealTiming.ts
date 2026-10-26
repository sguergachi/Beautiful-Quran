/** Finish English Scroll by the voiced end without moving its next timed onset. */
export function englishRevealDurationMs(
  durationMs: number,
  minimumMs: number,
  spokenMs?: number,
  speed = 1,
): number {
  const duration = Math.max(1, durationMs)
  const earlier = Math.max(duration - 100, Math.min(duration, Math.max(1, minimumMs)))
  if (spokenMs == null || !Number.isFinite(spokenMs) || spokenMs <= 0 || !Number.isFinite(speed) || speed <= 0) return earlier
  // The shared window keeps one media millisecond even for a sub-ms wall span.
  return Math.min(earlier, Math.floor(spokenMs / speed))
}
