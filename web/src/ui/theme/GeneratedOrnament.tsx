/**
 * Renderers for the generated ornament (ornamentGenerator.ts) — the web
 * twins of Android's GeneratedOrnament.kt. Same material language
 * (embossed relief copies under a gold-leaf face) but the geometry arrives
 * from the generator. [GeneratedRosette] is shared by the entrance cover's
 * medallion/corner-seals and the reader's per-chapter surah header; the
 * border/field pieces below it (cover-only) pull in the cover's layout type.
 */
import { useEffect, useId, useMemo, useState } from 'react'
import type { BorderSpec, OrnamentStroke, RosetteSpec } from './ornamentGenerator'
import { borderBandBackground, pathD, svgDataUri } from './ornamentSvg'
import type { CoverLayout } from '../entrance/coverLayout'

// The pure serializers live in ornamentSvg.ts (Node can import them without
// React); re-exported here so existing call sites keep their import path.
export { borderBandBackground, fieldWeaveBackground } from './ornamentSvg'

/** One build clock for the whole cover; Android uses the same schedule. */
export const ORNAMENT_BUILD_MS = 3_400

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    !!window.matchMedia &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}

/**
 * False on first paint, true one frame later — flipping it starts every CSS
 * transition of the build. Reduced motion starts (and stays) fully built.
 */
export function useOrnamentBuilt(): boolean {
  const [built, setBuilt] = useState(prefersReducedMotion)
  useEffect(() => {
    if (built) return
    // Double rAF guarantees the pre-build styles hit the DOM before the
    // flip, so every transition actually runs; the timeout covers hidden
    // tabs, where rAF never fires but the cover must still finish building.
    let raf2 = 0
    const raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => setBuilt(true))
    })
    const fallback = window.setTimeout(() => setBuilt(true), 150)
    return () => {
      cancelAnimationFrame(raf1)
      cancelAnimationFrame(raf2)
      window.clearTimeout(fallback)
    }
  }, [built])
  return built
}

interface RosetteProps {
  spec: RosetteSpec
  className: string
  /**
   * Ink-in the medallion stroke by stroke as `built` flips (the ceremony's
   * illumination). Corner seals and the reader's per-chapter rosette are
   * part of fixed tooling/typography instead — pass `animated={false}` to
   * render them complete from the first frame, matching Android's static
   * `GeneratedCornerSeals` / `GeneratedChapterRosette`.
   */
  built: boolean
  animated?: boolean
  /** Stroke widths in viewBox (200) units — seals render small, so thicker. */
  ruleWidth?: number
  hairWidth?: number
  /**
   * Leaf gradient stops and relief shadow colors. Default to the entrance
   * cover's fixed leather-gold values; the reader passes the theme-aware
   * `--gold-*` / `--emboss-*` custom properties instead, since the surah
   * header sits on the page background, which changes with the app theme.
   */
  brightGold?: string
  deepGold?: string
  embossDark?: string
  embossLight?: string
}

/**
 * A generated rosette (medallion, corner seal, or chapter header ornament)
 * as an SVG. When [animated] (the default), each stroke is a unit-dash path
 * whose offset transitions 1 → 0 inside its own [birth, birth+span] window
 * of the build, and pearls pop in on opacity at their birth — the
 * medallion's illumination. Corner seals and chapter rosettes render with
 * `animated={false}`: fully formed, no transition. Emboss copies ride the
 * same reveal either way.
 */
export function GeneratedRosette({
  spec,
  className,
  built,
  animated = true,
  ruleWidth = 2.2,
  hairWidth = 1,
  brightGold = '#edd188',
  deepGold = '#9a7b2a',
  embossDark = 'rgba(0, 0, 0, 0.4)',
  embossLight = 'rgba(255, 255, 255, 0.12)',
}: RosetteProps) {
  const gradId = useId()
  const reduced = prefersReducedMotion() || !animated

  const strokeStyle = (s: OrnamentStroke): React.CSSProperties => ({
    strokeDasharray: 1,
    strokeDashoffset: !animated || built ? 0 : 1,
    transition: reduced
      ? undefined
      : `stroke-dashoffset ${Math.round(s.span * ORNAMENT_BUILD_MS)}ms linear ${Math.round(
          s.birth * ORNAMENT_BUILD_MS,
        )}ms`,
  })

  const layer = (stroke: string, dx: number) => (
    <g
      fill="none"
      stroke={stroke}
      strokeLinecap="round"
      strokeLinejoin="round"
      transform={dx === 0 ? undefined : `translate(${dx} ${dx})`}
    >
      {spec.strokes.map((s, i) => (
        <path
          key={i}
          d={pathD(s, 200)}
          pathLength={1}
          strokeWidth={s.weight === 'rule' ? ruleWidth : hairWidth}
          style={strokeStyle(s)}
        />
      ))}
    </g>
  )

  return (
    <svg className={className} viewBox="0 0 200 200" aria-hidden="true">
      <defs>
        <linearGradient id={gradId} x1="0%" y1="22%" x2="100%" y2="78%">
          <stop offset="0%" stopColor={deepGold} />
          <stop offset="50%" stopColor={brightGold} />
          <stop offset="100%" stopColor={deepGold} />
        </linearGradient>
      </defs>
      {/* Relief first, face last — pressed into the page/leather. */}
      {layer(embossDark, 0.9)}
      {layer(embossLight, -0.9)}
      {layer(`url(#${gradId})`, 0)}
      {spec.dots.map((d, i) => (
        <circle
          key={i}
          cx={d.x * 200}
          cy={d.y * 200}
          r={d.radius * 200}
          fill={`url(#${gradId})`}
          style={{
            opacity: !animated || built ? 1 : 0,
            transition: reduced
              ? undefined
              : `opacity 200ms linear ${Math.round(d.birth * ORNAMENT_BUILD_MS)}ms`,
          }}
        />
      ))}
    </svg>
  )
}

/** Channel-mouth taper: both rails converging onto the petal tip. */
function chamferBackground(taper: number, bandH: number, vertical: boolean): string {
  const [w, h] = vertical ? [bandH, taper] : [taper, bandH]
  // Horizontal mouth converges left onto (0, mid); vertical converges up.
  const d = vertical
    ? `M 0 ${taper.toFixed(2)} L ${(bandH / 2).toFixed(2)} 0 L ${bandH.toFixed(2)} ${taper.toFixed(2)}`
    : `M ${taper.toFixed(2)} 0 L 0 ${(bandH / 2).toFixed(2)} L ${taper.toFixed(2)} ${bandH.toFixed(2)}`
  const svg =
    `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 ${w.toFixed(2)} ${h.toFixed(2)}'>` +
    `<path d='${d}' fill='none' stroke='rgba(217,180,74,0.85)' stroke-width='1.4' stroke-linejoin='round' stroke-linecap='round'/>` +
    `</svg>`
  return svgDataUri(svg)
}

/**
 * The border frieze between the frame's two gilt rules: each side's band
 * is a railed channel whose mouth tapers onto the corner seal's petal tip
 * — the seal's bezel points down the band's axis and the rails converge
 * onto that point, so border and corner ornament are one continuous piece
 * of geometry. Each run fits a whole number of periods (the period
 * stretches a little), so the pattern arrives at every corner at the same
 * phase. Far sides are mirrored so the pattern faces inward. The band is
 * the binding's tooling, not illumination — it is complete from the first
 * frame, never animated (matches Android's static `GeneratedBorderBand`).
 */
export function GeneratedBorder({
  border,
  sealTip,
  layout,
  width,
  height,
}: {
  border: BorderSpec
  /** Petal-tip radius of the corner seal (RosetteSpec.tipRadius). */
  sealTip: number
  layout: CoverLayout
  width: number
  height: number
}) {
  const horizontal = useMemo(() => borderBandBackground(border, false), [border])
  const verticalBg = useMemo(() => borderBandBackground(border, true), [border])

  const bandH = layout.bandHeight
  const tipU = layout.bandCenter + layout.starSize * sealTip
  const taper = bandH * 0.8
  const bandStart = tipU + taper
  const periodPx = border.period * bandH
  const fitted = (side: number): number | null => {
    const run = side - 2 * bandStart
    if (run < periodPx / 2) return null
    return run / Math.max(1, Math.round(run / periodPx))
  }
  const hPeriod = fitted(width)
  const vPeriod = fitted(height)
  const start = `${bandStart.toFixed(2)}px`

  const hChamfer = chamferBackground(taper, bandH, false)
  const vChamfer = chamferBackground(taper, bandH, true)
  const across = `${(layout.bandCenter - bandH / 2).toFixed(2)}px`
  const atTip = `${tipU.toFixed(2)}px`
  const mouths: Array<{ style: React.CSSProperties; flip?: string }> = []
  for (const ax of ['top', 'bottom'] as const) {
    mouths.push({
      style: { [ax]: across, left: atTip, width: taper, height: bandH, backgroundImage: hChamfer },
    })
    mouths.push({
      style: { [ax]: across, right: atTip, width: taper, height: bandH, backgroundImage: hChamfer },
      flip: 'scaleX(-1)',
    })
  }
  for (const ax of ['left', 'right'] as const) {
    mouths.push({
      style: { [ax]: across, top: atTip, width: bandH, height: taper, backgroundImage: vChamfer },
    })
    mouths.push({
      style: { [ax]: across, bottom: atTip, width: bandH, height: taper, backgroundImage: vChamfer },
      flip: 'scaleY(-1)',
    })
  }

  return (
    <div className="entrance-borderband" aria-hidden="true">
      {hPeriod != null && (
        <>
          {(['top', 'bottom'] as const).map((side) => (
            <div
              key={side}
              className={`entrance-border entrance-border--${side}`}
              style={{
                ...horizontal,
                left: start,
                right: start,
                backgroundSize: `${hPeriod.toFixed(2)}px ${bandH}px`,
              }}
            />
          ))}
        </>
      )}
      {vPeriod != null && (
        <>
          {(['left', 'right'] as const).map((side) => (
            <div
              key={side}
              className={`entrance-border entrance-border--${side}`}
              style={{
                ...verticalBg,
                top: start,
                bottom: start,
                backgroundSize: `${bandH}px ${vPeriod.toFixed(2)}px`,
              }}
            />
          ))}
        </>
      )}
      {mouths.map(({ style, flip }, i) => (
        <div
          key={i}
          className="entrance-chamfer"
          style={flip ? { ...style, transform: flip } : style}
        />
      ))}
    </div>
  )
}
