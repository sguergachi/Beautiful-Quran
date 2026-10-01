/** Both incoming leaves must be measured; the tighter page sets their hand. */
export function mushafFit(page: number, facing: boolean, fits: Readonly<Record<number, number>>): number | undefined {
  const right = fits[page]
  const left = facing ? fits[page + 1] : right
  return right == null || left == null ? undefined : Math.min(right, left)
}
