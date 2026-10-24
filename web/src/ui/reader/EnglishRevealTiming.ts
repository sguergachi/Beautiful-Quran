/** English Scroll ends the reveal early without moving its next timed onset. */
export function englishRevealDurationMs(durationMs: number, minimumMs: number): number {
  const duration = Math.max(1, durationMs)
  return Math.max(duration - 100, Math.min(duration, Math.max(1, minimumMs)))
}
