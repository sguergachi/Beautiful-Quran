/**
 * Pure SVG serializers for generated ornaments — no React, no DOM.
 *
 * These turn the geometry from `ornamentGenerator.ts` into data-URI
 * backgrounds. They live apart from `GeneratedOrnament.tsx` because two very
 * different consumers need them: the reader/entrance components (which
 * re-export them, so existing imports keep working) and the Node script
 * `scripts/build-marketing-ornaments.mjs`, which pre-renders the product
 * page's ornaments into `docs/ornaments.css` and cannot import React.
 */
import type { BorderSpec, FieldSpec, OrnamentStroke } from './ornamentGenerator'

export function pathD(stroke: OrnamentStroke, scale: number, precision = 2): string {
  const parts = stroke.points.map(
    (p, i) => `${i === 0 ? 'M' : 'L'} ${(p.x * scale).toFixed(precision)} ${(p.y * scale).toFixed(precision)}`,
  )
  if (stroke.closed) parts.push('Z')
  return parts.join(' ')
}

export function svgDataUri(svg: string): string {
  return `url("data:image/svg+xml,${encodeURIComponent(svg)}")`
}

/**
 * The generated leather field as a tiling background image. The cell's
 * strokes are drawn once and reused in a 3 × 3 grid of offsets per ink
 * layer, so strokes straddling the cell boundary are completed by their
 * neighbours' copies — same trick as the old fixed weave, generalized.
 *
 * [ink] and [embossLight] default to the entrance cover's fixed gold-leaf
 * tint; the reader passes concrete colors resolved from its own theme
 * instead (a `var(--…)` reference won't resolve inside a data-URI SVG, so
 * callers must resolve their CSS custom property to a literal color first).
 */
export function fieldWeaveBackground(
  field: FieldSpec,
  ink = 'rgba(217,180,74,0.07)',
  embossLight = 'rgba(255,255,255,0.05)',
): {
  backgroundImage: string
  backgroundSize: string
} {
  const w = field.cellW
  const h = field.cellH
  // One CSS pixel expressed in cell units, at the suggested render size.
  const px = field.cellW / field.cellWidthDp
  const body = field.strokes.map((s) => `<path d='${pathD(s, 1, 4)}' stroke-width='${(px * (s.weight === 'rule' ? 1 : 0.55)).toFixed(4)}'/>`).join('')
  const uses: string[] = []
  for (const [stroke, off] of [
    [embossLight, -0.6 * px],
    [ink, 0],
  ] as const) {
    for (let dx = -1; dx <= 1; dx++) {
      for (let dy = -1; dy <= 1; dy++) {
        uses.push(
          `<use href='#c' x='${(dx * w + off).toFixed(4)}' y='${(dy * h + off).toFixed(4)}' stroke='${stroke}'/>`,
        )
      }
    }
  }
  const svg =
    `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 ${w} ${h}'>` +
    `<defs><g id='c' fill='none' stroke-width='${px.toFixed(4)}' stroke-linejoin='round' stroke-linecap='round'>${body}</g></defs>` +
    uses.join('') +
    `</svg>`
  const cssH = (field.cellWidthDp * h) / w
  return {
    backgroundImage: svgDataUri(svg),
    backgroundSize: `${field.cellWidthDp.toFixed(2)}px ${cssH.toFixed(2)}px`,
  }
}

/** Gold face and relief tints of a tooled band; defaults are the cover's. */
export interface BandInk {
  gold: string
  embossDark: string
  embossLight: string
}

const COVER_BAND_INK: BandInk = {
  gold: 'rgba(217,180,74,0.85)',
  embossDark: 'rgba(0,0,0,0.28)',
  embossLight: 'rgba(255,255,255,0.06)',
}

/**
 * One period of the border frieze as a tiling background — horizontal for
 * the top/bottom bands, coordinate-swapped for the left/right ones. The
 * caller sizes it (integer-period fit); mirrored sides use transforms.
 */
export function borderBandBackground(
  border: BorderSpec,
  vertical: boolean,
  ink: BandInk = COVER_BAND_INK,
): {
  backgroundImage: string
} {
  const p = border.period
  // Pattern strokes stay hairline; the rule-weight edge rails run heavier
  // so the band reads as a tooled channel (Android: 1dp vs 1.5dp).
  const hairW = 0.09
  const ruleW = 0.135
  const map = (x: number, y: number) =>
    vertical ? `${y.toFixed(4)} ${x.toFixed(4)}` : `${x.toFixed(4)} ${y.toFixed(4)}`
  const paths = border.strokes
    .map((s) => {
      const parts = s.points.map((pt, i) => `${i === 0 ? 'M' : 'L'} ${map(pt.x, pt.y)}`)
      if (s.closed) parts.push('Z')
      const w = s.weight === 'rule' ? ruleW : hairW
      return `<path d='${parts.join(' ')}' stroke-width='${w}'/>`
    })
    .join('')
  const dots = border.dots
    .map((d) => {
      const [cx, cy] = vertical ? [d.y, d.x] : [d.x, d.y]
      return `<circle cx='${cx.toFixed(4)}' cy='${cy.toFixed(4)}' r='${d.radius.toFixed(4)}' fill='${ink.gold}' stroke='none'/>`
    })
    .join('')
  const [w, h] = vertical ? [1, p] : [p, 1]
  const shifts = vertical ? ([[0, -p], [0, 0], [0, p]] as const) : ([[-p, 0], [0, 0], [p, 0]] as const)
  const uses: string[] = []
  for (const [stroke, off] of [
    [ink.embossDark, 0.055],
    [ink.embossLight, -0.055],
    [ink.gold, 0],
  ] as const) {
    for (const [dx, dy] of shifts) {
      uses.push(`<use href='#b' x='${dx + off}' y='${dy + off}' stroke='${stroke}'/>`)
    }
  }
  const svg =
    `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 ${w} ${h}'>` +
    `<defs><g id='b' fill='none' stroke-linejoin='round' stroke-linecap='round'>${paths}${dots}</g></defs>` +
    uses.join('') +
    `</svg>`
  return { backgroundImage: svgDataUri(svg) }
}
