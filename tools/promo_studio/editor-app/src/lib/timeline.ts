/** Fit the complete edit with room for its final trim handle and time label.
 * Manual zoom keeps its readability limits; Fit can go below that range for
 * long edits so that their end never escapes the viewport.
 */
export function fitTimelineScale(width: number, labelWidth: number, duration: number): number {
  return Math.min(500, Math.max(1, width - labelWidth - 60) / Math.max(1, duration));
}
