/**
 * Silence between a word's voiced end and the next onset that counts as the
 * reciter pausing (media ms). Connected speech leaves under 20 ms between
 * timed words and real pauses mostly run past a second.
 */
export const ENGLISH_PAUSE_MS = 150

/**
 * English Scroll flows straight into the next word through connected speech;
 * only at a real pause does it finish by the voiced end and hold the silence.
 * Finishing 100 ms early before every word made each one stop.
 */
export function englishRevealDurationMs(
  durationMs: number,
  spokenMs?: number,
  speed = 1,
): number {
  const duration = Math.max(1, durationMs)
  if (spokenMs == null || !Number.isFinite(spokenMs) || spokenMs <= 0 || !Number.isFinite(speed) || speed <= 0) return duration
  if (duration * speed - spokenMs < ENGLISH_PAUSE_MS) return duration
  // The shared window keeps one media millisecond even for a sub-ms wall span.
  return Math.floor(spokenMs / speed)
}
