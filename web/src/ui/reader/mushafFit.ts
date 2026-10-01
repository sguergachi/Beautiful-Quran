/** Both incoming leaves must be measured; the tighter page sets their hand. */
export function mushafFit(page: number, facing: boolean, fits: Readonly<Record<number, number>>): number | undefined {
  const right = fits[page]
  const left = facing ? fits[page + 1] : right
  return right == null || left == null ? undefined : Math.min(right, left)
}

/** A line's width with its word spaces at their minimum. */
export function naturalLineWidth(itemWidths: readonly number[], gap: number): number {
  if (itemWidths.length === 0) return 0
  return itemWidths.reduce((sum, width) => sum + width, 0) + gap * (itemWidths.length - 1)
}

/** Fifteen lines are never set closer than this many times the type size. */
export const MUSHAF_MIN_LEADING = 1.75

/**
 * The scale at which a page's widest line, set with minimum word spaces,
 * exactly fills the width it is given. It shrinks the type when the line
 * overruns, and grows it when there is room — but never past [maxGrow], the
 * point where the lines would crowd each other vertically.
 */
export function mushafLeafFit(available: number, widest: number, maxGrow = 1): number {
  if (available <= 0 || widest <= 0) return 1
  const ratio = available / widest
  // A hair under, so rounding never leaves the last word a pixel over.
  if (ratio < 1) return ratio * 0.99
  return Math.max(1, Math.min(ratio * 0.99, maxGrow))
}

/** How one line is set once the page's hand and measure are known. */
export interface LineSetting {
  /** Horizontal scale on the line's letters, 1 … 1 + maxWiden. */
  widen: number
  /** Too short to justify: centre it at an ordinary word space. */
  short: boolean
}

/** The word space a line is widened toward, in em. */
export const MUSHAF_TARGET_GAP = 0.42
/** A line that fills less of the measure than this is centred, not justified. */
export const MUSHAF_SHORT_FILL = 0.62

/**
 * Solve one line. [inkWidth] is the summed width of its words and marks,
 * [gaps] the number of spaces between them, [measure] the block's width and
 * [em] the type size, all in the same pixels. Letters are widened only as far
 * as brings the word space down to the target, never past [maxWiden].
 */
export function solveLine(
  inkWidth: number,
  gaps: number,
  measure: number,
  em: number,
  maxWiden: number,
): LineSetting {
  if (inkWidth <= 0 || measure <= 0 || gaps <= 0) return { widen: 1, short: false }
  const target = gaps * MUSHAF_TARGET_GAP * em
  if ((inkWidth + target) / measure < MUSHAF_SHORT_FILL) return { widen: 1, short: true }
  const wanted = (measure - target) / inkWidth
  return { widen: Math.max(1, Math.min(wanted, 1 + Math.max(0, maxWiden))), short: false }
}
