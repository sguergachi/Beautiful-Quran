/**
 * Pure geometry for the web ayah selector rail — a cousin of Android's
 * `AyahSelectorRail`. Desktop blooms bars from a centered midline; mobile
 * (≤640px) grows them flush from the screen edge like Android, with numbers
 * hanging inward. Focus falls off with distance from the hovered / selected ayah.
 */

/** Matches the `@media (max-width: 640px)` rail breakpoint in styles.css. */
export const MOBILE_RAIL_MEDIA = '(max-width: 640px)'

/** Collapsed stack size: grows with surah length, sqrt-mapped like Android. */
export function symbolicAyahBarCount(ayahCount: number): number {
  return Math.min(18, Math.max(4, Math.round(Math.ceil(Math.sqrt(ayahCount)))))
}

/** Collapsed dash height (px) — matches Android 1.5.dp and paint constants. */
export const COLLAPSED_BAR_H_PX = 1.5

/**
 * Vertical span (px) of the collapsed dash stack.
 * Spacing is 72/count clamped 4–8 — same as Android / the paint path.
 */
export function collapsedStackSpanPx(ayahCount: number): number {
  const count = symbolicAyahBarCount(ayahCount)
  const spacing = Math.min(8, Math.max(4, 72 / count))
  return (count - 1) * (COLLAPSED_BAR_H_PX + spacing) + COLLAPSED_BAR_H_PX
}

/**
 * Collapsed hit-target height (px): visual stack + pad, min 48px.
 * Keeps expand-on-touch on the vertical center of the edge, not full height.
 */
export function collapsedRailHitHeightPx(ayahCount: number, padPx = 24): number {
  return Math.max(48, collapsedStackSpanPx(ayahCount) + padPx)
}

/** Soft overscroll past either end of the dial. */
export function rubberBandDialPosition(value: number, min: number, max: number): number {
  if (value >= min && value <= max) return value
  if (value < min) return min - (min - value) * 0.32
  return max + (value - max) * 0.32
}

/**
 * Map a Y coordinate inside the rail to an ayah index.
 * `topFrac` / `bottomFrac` are the unused insets at each end (0–1 of height).
 */
export function ayahFromTrackY(
  y: number,
  height: number,
  ayahCount: number,
  topFrac = 0.08,
  bottomFrac = 0.12,
): number {
  if (ayahCount <= 1) return 1
  const span = 1 - topFrac - bottomFrac
  const t = (y / Math.max(1, height) - topFrac) / span
  const clamped = Math.min(1, Math.max(0, t))
  return Math.min(ayahCount, Math.max(1, Math.round(clamped * (ayahCount - 1)) + 1))
}

/** Continuous dial position (fractional ayah) from a Y in the rail. */
export function dialFromTrackY(
  y: number,
  height: number,
  ayahCount: number,
  topFrac = 0.08,
  bottomFrac = 0.12,
): number {
  if (ayahCount <= 1) return 1
  const span = 1 - topFrac - bottomFrac
  const t = (y / Math.max(1, height) - topFrac) / span
  const clamped = Math.min(1, Math.max(0, t))
  return 1 + clamped * (ayahCount - 1)
}

/**
 * Pressing the collapsed dash stack must keep the reading ayah.
 *
 * The stack is vertically centered on the edge, so a press Y mapped through
 * [dialFromTrackY] is the rail midline — always near the middle verse of a
 * long surah. Opening pins [readingPos] to the finger; movement then scrubs.
 */
export function dialOnCollapsedActivate(readingPos: number, ayahCount: number): number {
  if (ayahCount <= 1) return 1
  return Math.min(ayahCount, Math.max(1, readingPos))
}

/** Y of a dial position inside the rail track (inverse of dialFromTrackY). */
export function trackYFromDial(
  dial: number,
  height: number,
  ayahCount: number,
  topFrac = 0.08,
  bottomFrac = 0.12,
): number {
  if (ayahCount <= 1) return height * (topFrac + (1 - topFrac - bottomFrac) / 2)
  const span = 1 - topFrac - bottomFrac
  const t = (dial - 1) / (ayahCount - 1)
  return height * (topFrac + Math.min(1, Math.max(0, t)) * span)
}

/**
 * Ayah delta from a pointer move — Android `AyahSelectorRail` wheel scrub.
 * Negative dy (finger/mouse up) advances the dial; one tickSpacingPx = 1 ayah,
 * matching the magnified tick spacing so the label under focus is what commits.
 */
export function dialDeltaFromPointerDy(dy: number, tickSpacingPx: number): number {
  return -dy / Math.max(1, tickSpacingPx)
}

/**
 * Dial value for the tick drawn under [y], given the wheel's current dial and
 * its track anchor. Used for a no-drag click/tap onto a visible tick label.
 */
export function dialFromTickY(
  y: number,
  currentDial: number,
  anchorY: number,
  tickSpacingPx: number,
): number {
  return currentDial + (y - anchorY) / Math.max(1, tickSpacingPx)
}

/** 1 at the focal ayah, 0 at `focusRadius` ticks away. */
export function tickFocus(offset: number, focusRadius: number): number {
  const r = Math.max(1, focusRadius)
  return Math.min(1, Math.max(0, 1 - Math.abs(offset) / r))
}

/** Horizontal bar length — quadratic falloff so the focal tick dominates. */
export function tickLength(
  focus: number,
  major: boolean,
  minLen: number,
  maxLen: number,
  majorBonus: number,
): number {
  const f = Math.min(1, Math.max(0, focus))
  return minLen + (maxLen - minLen) * f * f + (major ? majorBonus : 0)
}

export function isMajorAyah(ayah: number, ayahCount: number): boolean {
  return ayah === 1 || ayah === ayahCount || ayah % 5 === 0
}

/** How many ticks to draw on each side of the focus for a given rail height. */
export function focusRadiusForHeight(height: number, tickSpacing: number): number {
  return Math.max(8, Math.ceil(height / tickSpacing / 2) + 2)
}

const IDEAL_MAX_BAR_LEN = 44
const IDEAL_MAJOR_BONUS = 6
const MIN_BAR_LEN = 8
const LABEL_GAP = 6
const EDGE_PAD = 2

/** Desktop: centered midline. Mobile: Android-style edge flush. */
export type RailGrowFrom = 'center' | 'edge'

export type RailExpandedLayout = {
  growFrom: RailGrowFrom
  /**
   * `center`: horizontal center of each bar.
   * `edge`: outer screen-edge X (0 for left, railWidth for right).
   */
  originX: number
  maxBarLen: number
  majorBonus: number
  /** Gap between the inner bar end and the ayah number. */
  labelGap: number
}

/**
 * Horizontal layout for expanded rail ticks + labels.
 *
 * - `center` (desktop): prefers a centered midline; biases toward the outer
 *   edge only when the canvas would otherwise clip the ayah number.
 * - `edge` (mobile): Android parity — bars grow from the screen edge, numbers
 *   hang inward. Shortens bars only when the rail cannot fit peak + label.
 */
export function railExpandedLayout(
  railWidth: number,
  side: 'left' | 'right',
  labelWidth: number,
  growFrom: RailGrowFrom = 'center',
): RailExpandedLayout {
  const width = Math.max(1, railWidth)
  const labelBudget = LABEL_GAP + Math.max(0, labelWidth) + EDGE_PAD
  // Edge-anchored peak: bar grows from the outer edge; only the page side
  // must stay inside the canvas for the label.
  const maxPeakEdgeAnchored = Math.max(MIN_BAR_LEN, width - labelBudget)
  const idealPeak = IDEAL_MAX_BAR_LEN + IDEAL_MAJOR_BONUS
  const peakLen = Math.min(idealPeak, maxPeakEdgeAnchored)

  let maxBarLen = IDEAL_MAX_BAR_LEN
  let majorBonus = IDEAL_MAJOR_BONUS
  if (peakLen < idealPeak) {
    const scale = peakLen / idealPeak
    maxBarLen = Math.max(MIN_BAR_LEN, IDEAL_MAX_BAR_LEN * scale)
    majorBonus = Math.max(0, peakLen - maxBarLen)
  }

  if (growFrom === 'edge') {
    return {
      growFrom: 'edge',
      originX: side === 'left' ? 0 : width,
      maxBarLen,
      majorBonus,
      labelGap: LABEL_GAP,
    }
  }

  const half = peakLen / 2
  const centered = width / 2

  if (side === 'left') {
    // Numbers hang to the right (toward the page).
    const maxMid = width - half - labelBudget
    return {
      growFrom: 'center',
      originX: Math.min(centered, maxMid),
      maxBarLen,
      majorBonus,
      labelGap: LABEL_GAP,
    }
  }

  // Right rail: numbers hang to the left.
  const minMid = half + labelBudget
  return {
    growFrom: 'center',
    originX: Math.max(centered, minMid),
    maxBarLen,
    majorBonus,
    labelGap: LABEL_GAP,
  }
}

/** Left edge + width of an expanded tick bar. */
export function railTickBarRect(
  layout: RailExpandedLayout,
  side: 'left' | 'right',
  length: number,
  thickness: number,
): { x: number; width: number } {
  if (layout.growFrom === 'center') {
    return { x: layout.originX - length / 2, width: length }
  }
  // Hide the outer rounded cap behind the screen edge (Android).
  const full = length + thickness
  if (side === 'left') {
    return { x: layout.originX - thickness, width: full }
  }
  return { x: layout.originX - length, width: full }
}

/**
 * X anchor for an ayah label. Pair with `textAlign: left` on the left rail
 * and `textAlign: right` on the right rail.
 */
export function railTickLabelX(
  layout: RailExpandedLayout,
  side: 'left' | 'right',
  length: number,
): number {
  if (layout.growFrom === 'center') {
    return side === 'left'
      ? layout.originX + length / 2 + layout.labelGap
      : layout.originX - length / 2 - layout.labelGap
  }
  return side === 'left'
    ? layout.originX + length + layout.labelGap
    : layout.originX - length - layout.labelGap
}

/**
 * Collapsed stack bar rect. On expand, bars retract so the wheel can bloom
 * out of the minimized stack:
 * - `center` (desktop): dashes shrink toward the midline
 * - `edge` (mobile): hide the outer rounded cap and slip bars off-screen
 *   (Android parity)
 */
export function railCollapsedBarRect(
  railWidth: number,
  side: 'left' | 'right',
  growFrom: RailGrowFrom,
  barW: number,
  barH: number,
  exit: number,
): { x: number; width: number } {
  const t = Math.min(1, Math.max(0, exit))
  if (growFrom === 'center') {
    const midX = railWidth * 0.5
    const w = barW * t
    return { x: midX - w / 2, width: w }
  }
  // Extra width so the rounded outer cap sits past the edge; slip on exit.
  const fullW = barW + barH
  const slip = (1 - t) * 4
  if (side === 'left') {
    return { x: -barH - slip, width: fullW }
  }
  return { x: railWidth - barW + slip, width: fullW }
}
