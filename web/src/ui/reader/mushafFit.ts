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

/**
 * The scale at which a page's widest line fits the width it is given. Only
 * ever shrinks: a page wider than its lines need is handled in CSS, where
 * the text block is drawn in to its widest line rather than the type grown.
 */
export function mushafLeafFit(available: number, widest: number): number {
  if (available <= 0 || widest <= available) return 1
  // A hair under, so rounding never leaves the last word a pixel over.
  return (available / widest) * 0.99
}
