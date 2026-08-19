/**
 * Cover layout geometry — a modular grid derived from the board's width and
 * height so the gilt frame, corner seals, medallion, and type stay in
 * proportion across phone, tablet, and the sheet-column desktop board.
 *
 * Mirrors the spirit of Android `coverFrameGeometry`: margins and ornaments
 * scale with the surface, never fixed pinprick pixels. Web has no display
 * corner-radius API, so the design radius is invented from the short side.
 */

export interface CoverLayout {
  /** Modular unit = short side / 48. */
  unit: number
  outerInset: number
  innerInset: number
  outerRadius: number
  innerRadius: number
  /** Corner seal diameter (Android uses radius ≈ 0.70 × outer inset). */
  starSize: number
  /** Centre line of the border band, measured in from each edge. */
  bandCenter: number
  /** Cross-section height of the border band between the two rules. */
  bandHeight: number
  medallion: number
  titleAr: number
  titleEn: number
  duaAr: number
  duaEn: number
  loadLabel: number
  padX: number
  padY: number
  gapMedallionTitle: number
  gapTitlePair: number
  /** Max width for dua / load copy inside the inner rule. */
  copyMax: number
  /** Radius of the leather's radial shading (0.75 × board height). */
  leatherRadius: number
  /** Flex weights for the three air bands (top / mid / bot). */
  airTop: number
  airMid: number
  airBot: number
}

function clamp(n: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, n))
}

/**
 * Derive a cover layout from the board's CSS pixel size.
 *
 * Grid: 48 units on the short side. Frame margin ≈ 5.5–7% of the short
 * side; corner seals ≈ 1.4× the outer margin (diameter); medallion capped
 * by both axes so a tall phone does not crush the titles beneath it.
 */
export function coverLayout(width: number, height: number): CoverLayout {
  const w = Math.max(1, width)
  const h = Math.max(1, height)
  const short = Math.min(w, h)
  const unit = short / 48

  // Frame — generous gilt margin that grows with the board, clamped so a
  // phone still keeps readable leather outside the rule.
  const outerInset = clamp(short * 0.058, unit * 2.2, short * 0.078)
  // The border zone between the two rules carries the generated frieze — a
  // real mushaf border band, not a pinstripe — so the gap is generous
  // (mirrors Android's ruleGap = 26dp).
  const ruleGap = clamp(unit * 1.95, 18, outerInset * 0.85)
  const innerInset = outerInset + ruleGap

  // innerRadius = outerRadius − gap (Cloud Four / iOS concentric).
  // Budget the invented corner so the inner fillet stays a curve.
  const minInnerR = 20
  const designR = Math.max(short * 0.12, innerInset + minInnerR)
  const outerRadius = Math.max(0, designR - outerInset)
  const innerRadius = Math.max(0, outerRadius - ruleGap)

  // Corner seals: diameter ≈ 1.35–1.5× outer inset — pressed into the margin,
  // not pinpricks. Floor so small boards still read as seals.
  const starSize = clamp(outerInset * 1.42, unit * 2.6, outerInset * 1.65)

  // The generated border frieze runs between the two rules; the seals sit
  // on its corners (bandCenter) covering the miter joints. Mirrors
  // Android's 0.72 factor and 10–20dp clamp for a fuller tooled channel.
  const bandCenter = (outerInset + innerInset) / 2
  const bandHeight = clamp((innerInset - outerInset) * 0.72, 10, 20)

  // Medallion: Android's ceremonial scale — 52% of board width, capped by
  // height (~30%) so the vertical stack (medal → titles → dua) keeps air,
  // and at 240px absolute (Android caps at 240dp).
  const medallion = clamp(
    Math.min(w * 0.52, h * 0.3, 240),
    unit * 12,
    short * 0.6,
  )

  // Type scale from board width, meeting Android's 40sp title on a phone
  // board and capped there so the sheet-column board does not shout.
  // English / dua track the Arabic size (Android: 22sp / 24sp / 14sp).
  const titleAr = clamp(w * 0.095, unit * 2.6, 40)
  const titleEn = clamp(titleAr * 0.55, 12, 22)
  const duaAr = clamp(titleAr * 0.6, 14, 24)
  const duaEn = clamp(titleAr * 0.35, 11, 14)
  const loadLabel = clamp(titleAr * 0.34, 11, 14)

  const padX = innerInset + unit * 0.85
  const padY = innerInset + unit * 0.55
  const gapMedallionTitle = clamp(unit * 3.4, 20, 30)
  const gapTitlePair = clamp(unit * 0.35, 4, 10)
  // Wide enough for the isti'adha on one line at dua size (Android gives
  // the same copy the full board minus its 40dp side padding).
  const copyMax = Math.min(w - padX * 2, 480)

  // Leather shading — Android's radial: centred a little above the middle,
  // radius 0.75 × board height, so the glow reads as one lit board rather
  // than a hotspot ellipse.
  const leatherRadius = h * 0.75

  // Portrait boards get more top air (medal sits high); squat boards
  // compress mid air so dua/load stay visible above the fold. The tall
  // weights are Android's column weights (0.9 / 0.55 / 0.5).
  const aspect = h / w
  const airTop = aspect >= 1.35 ? 0.9 : aspect >= 1.05 ? 0.75 : 0.55
  const airMid = aspect >= 1.35 ? 0.55 : aspect >= 1.05 ? 0.5 : 0.4
  const airBot = aspect >= 1.35 ? 0.5 : 0.45

  return {
    unit,
    outerInset,
    innerInset,
    outerRadius,
    innerRadius,
    starSize,
    bandCenter,
    bandHeight,
    medallion,
    titleAr,
    titleEn,
    duaAr,
    duaEn,
    loadLabel,
    padX,
    padY,
    gapMedallionTitle,
    gapTitlePair,
    copyMax,
    leatherRadius,
    airTop,
    airMid,
    airBot,
  }
}

/** CSS custom properties applied to `.entrance-board`. */
export function coverLayoutCssVars(layout: CoverLayout): Record<string, string> {
  const px = (n: number) => `${n.toFixed(2)}px`
  return {
    '--cover-unit': px(layout.unit),
    '--cover-outer-inset': px(layout.outerInset),
    '--cover-inner-inset': px(layout.innerInset),
    '--cover-outer-radius': px(layout.outerRadius),
    '--cover-inner-radius': px(layout.innerRadius),
    '--cover-star': px(layout.starSize),
    '--cover-band-c': px(layout.bandCenter),
    '--cover-band-h': px(layout.bandHeight),
    '--cover-medallion': px(layout.medallion),
    '--cover-title-ar': px(layout.titleAr),
    '--cover-title-en': px(layout.titleEn),
    '--cover-dua-ar': px(layout.duaAr),
    '--cover-dua-en': px(layout.duaEn),
    '--cover-load-label': px(layout.loadLabel),
    '--cover-pad-x': px(layout.padX),
    '--cover-pad-y': px(layout.padY),
    '--cover-gap-medal': px(layout.gapMedallionTitle),
    '--cover-gap-titles': px(layout.gapTitlePair),
    '--cover-copy-max': px(layout.copyMax),
    '--cover-leather-r': px(layout.leatherRadius),
    '--cover-air-top': String(layout.airTop),
    '--cover-air-mid': String(layout.airMid),
    '--cover-air-bot': String(layout.airBot),
  }
}
