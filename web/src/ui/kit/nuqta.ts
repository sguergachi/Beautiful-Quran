import { createContext, type CSSProperties } from 'react'

/**
 * Every knob of the nuqta radio dot — Android `NuqtaParams`, flattened. Keys
 * match Android's nuqta lab (`NuqtaLab.kt`) so a snippet copied on one
 * platform pastes on the other.
 */
export type NuqtaParams = {
  sizeDp: number
  bow: number
  strokeDp: number
  restingOutlineAlpha: number
  selectedOutlineAlpha: number
  spreadMs: number
  wetDryBack: number
  liftMs: number
  liftX1: number
  liftY1: number
  liftX2: number
  liftY2: number
} & Record<`${NuqtaLayer}${NuqtaLayerKnob}`, number>

export type NuqtaLayer = 'wet' | 'body' | 'pool'
type NuqtaLayerKnob = 'Start' | 'End' | 'X1' | 'Y1' | 'X2' | 'Y2' | 'Radius' | 'Alpha' | 'Dx' | 'Dy'
export type NuqtaKnobKey = keyof NuqtaParams

/** The shipped nuqta — Android `ShippedNuqtaParams`. */
export const SHIPPED_NUQTA: NuqtaParams = {
  sizeDp: 20,
  bow: 1,
  strokeDp: 1.15,
  restingOutlineAlpha: 0.46,
  selectedOutlineAlpha: 0.82,
  spreadMs: 640,
  wetDryBack: 0.33,
  liftMs: 220,
  liftX1: 0.4,
  liftY1: 0,
  liftX2: 1,
  liftY2: 1,
  wetStart: 0,
  wetEnd: 0.55,
  wetX1: 0.1,
  wetY1: 0.75,
  wetX2: 0.3,
  wetY2: 1,
  wetRadius: 1.12,
  wetAlpha: 0.24,
  wetDx: 0,
  wetDy: 0,
  bodyStart: 0.08,
  bodyEnd: 0.82,
  bodyX1: 0.25,
  bodyY1: 0.6,
  bodyX2: 0.3,
  bodyY2: 1,
  bodyRadius: 0.98,
  bodyAlpha: 0.55,
  bodyDx: 1.1,
  bodyDy: -0.7,
  poolStart: 0.18,
  poolEnd: 1,
  poolX1: 0.3,
  poolY1: 0.35,
  poolX2: 0.15,
  poolY2: 1,
  poolRadius: 0.84,
  poolAlpha: 0.96,
  poolDx: -0.6,
  poolDy: 0.8,
}

/** What every nuqta draws; only Settings → Developer's nuqta lab overrides it. */
export const NuqtaParamsContext = createContext<NuqtaParams>(SHIPPED_NUQTA)

type Knob = { key: NuqtaKnobKey; label: string; min: number; max: number; digits: number }

const curve = (p: 'lift' | NuqtaLayer): Knob[] => [
  { key: `${p}X1`, label: 'Curve x1', min: 0, max: 1, digits: 2 },
  { key: `${p}Y1`, label: 'Curve y1', min: -0.5, max: 1.5, digits: 2 },
  { key: `${p}X2`, label: 'Curve x2', min: 0, max: 1, digits: 2 },
  { key: `${p}Y2`, label: 'Curve y2', min: -0.5, max: 1.5, digits: 2 },
]

const layer = (p: NuqtaLayer): Knob[] => [
  { key: `${p}Start`, label: 'Starts at', min: 0, max: 0.9, digits: 2 },
  { key: `${p}End`, label: 'Arrives at', min: 0.1, max: 1, digits: 2 },
  ...curve(p),
  { key: `${p}Radius`, label: 'Reach', min: 0.2, max: 1.6, digits: 2 },
  { key: `${p}Alpha`, label: 'Alpha', min: 0, max: 1, digits: 2 },
  { key: `${p}Dx`, label: 'Pool x (dp)', min: -4, max: 4, digits: 1 },
  { key: `${p}Dy`, label: 'Pool y (dp)', min: -4, max: 4, digits: 1 },
]

/** Android `NuqtaKnobGroups`, same order, ranges and keys. */
export const NUQTA_KNOB_GROUPS: { title: string; knobs: Knob[] }[] = [
  {
    title: 'Cut',
    knobs: [
      { key: 'sizeDp', label: 'Size (dp)', min: 14, max: 32, digits: 0 },
      { key: 'bow', label: 'Side bow', min: 0, max: 2.5, digits: 2 },
      { key: 'strokeDp', label: 'Outline (dp)', min: 0.4, max: 2.5, digits: 2 },
      { key: 'restingOutlineAlpha', label: 'Resting ink', min: 0, max: 1, digits: 2 },
      { key: 'selectedOutlineAlpha', label: 'Chosen ink', min: 0, max: 1, digits: 2 },
    ],
  },
  {
    title: 'Spread & lift',
    knobs: [
      { key: 'spreadMs', label: 'Spread ms', min: 120, max: 2000, digits: 0 },
      { key: 'wetDryBack', label: 'Wet dry-back', min: 0, max: 1, digits: 2 },
      { key: 'liftMs', label: 'Lift ms', min: 0, max: 800, digits: 0 },
      ...curve('lift'),
    ],
  },
  { title: 'Wet edge', knobs: layer('wet') },
  { title: 'Body', knobs: layer('body') },
  { title: 'Pool', knobs: layer('pool') },
]

const KNOBS = new Map(NUQTA_KNOB_GROUPS.flatMap((g) => g.knobs).map((k) => [k.key, k]))

export function formatNuqtaKnob(key: NuqtaKnobKey, value: number): string {
  const digits = KNOBS.get(key)?.digits ?? 2
  if (digits === 0) return `${Math.round(value)}`
  const s = value.toFixed(digits).replace(/\.?0+$/, '')
  return s === '' || s === '-0' ? '0' : s
}

/** Copy text for the lab — the same flat object Android's lab writes. */
export function formatNuqtaCopy(p: NuqtaParams): string {
  const lines = [
    "// Nuqta radio dot — paste into either platform's nuqta lab,",
    '// then ship by editing NuqtaParams defaults / SHIPPED_NUQTA.',
    '{',
  ]
  for (const group of NUQTA_KNOB_GROUPS) {
    lines.push(`  // ${group.title}`)
    for (const k of group.knobs) lines.push(`  ${k.key}: ${formatNuqtaKnob(k.key, p[k.key])},`)
  }
  lines.push('}')
  return lines.join('\n')
}

/** Reads recognised `key: value` / `key = value` pairs over [base]; null if none. */
export function parseNuqtaFromText(text: string, base: NuqtaParams): NuqtaParams | null {
  const re = /([A-Za-z_][A-Za-z0-9_]*)\s*[=:]\s*(-?\d+(?:\.\d+)?)f?\b/g
  const next = { ...base }
  let hits = 0
  for (const m of text.matchAll(re)) {
    const key = m[1] as NuqtaKnobKey
    if (!KNOBS.has(key)) continue
    const n = Number(m[2])
    if (!Number.isFinite(n)) continue
    next[key] = key === 'spreadMs' || key === 'liftMs' ? Math.round(n) : n
    hits++
  }
  return hits > 0 ? next : null
}

const CORNERS: [number, number][] = [
  [0.49, 0.09],
  [0.91, 0.43],
  [0.53, 0.91],
  [0.09, 0.58],
]
const BULGES: [number, number][] = [
  [0.69, 0.16],
  [0.86, 0.66],
  [0.31, 0.84],
  [0.14, 0.33],
]

/** Android `inkNuqtaPath(size, bow)`: the bowed rhombus of one qalam touch. */
export function nuqtaPath(size: number, bow: number): string {
  const n = (v: number) => `${+(v * size).toFixed(3)}`
  let d = `M${n(CORNERS[0][0])} ${n(CORNERS[0][1])}`
  for (let i = 0; i < CORNERS.length; i++) {
    const a = CORNERS[i]
    const b = CORNERS[(i + 1) % CORNERS.length]
    const mx = (a[0] + b[0]) / 2
    const my = (a[1] + b[1]) / 2
    const cx = mx + (BULGES[i][0] - mx) * bow
    const cy = my + (BULGES[i][1] - my) * bow
    d += `Q${n(cx)} ${n(cy)} ${n(b[0])} ${n(b[1])}`
  }
  return `${d}Z`
}

/**
 * The CSS custom properties `.ink-nuqta` in styles.css animates by. Android
 * eases each layer over its own [start, end] share of one spread clock; CSS
 * gets the same thing as a delay plus a duration on that layer's transition.
 */
export function nuqtaStyleVars(p: NuqtaParams): CSSProperties {
  const vars: Record<string, string> = {
    '--nq-size': `${p.sizeDp / 16}rem`,
    '--nq-rest': `${Math.round(p.restingOutlineAlpha * 65)}%`,
    '--nq-chosen': `${Math.round(p.selectedOutlineAlpha * 100)}%`,
    '--nq-spread': `${p.spreadMs}ms`,
    '--nq-lift': `${p.liftMs}ms`,
    '--nq-lift-ease': `cubic-bezier(${p.liftX1}, ${p.liftY1}, ${p.liftX2}, ${p.liftY2})`,
  }
  for (const l of ['wet', 'body', 'pool'] as const) {
    const start = p[`${l}Start`]
    const span = Math.max(p[`${l}End`] - start, 0.001)
    vars[`--nq-${l}-delay`] = `${Math.round(start * p.spreadMs)}ms`
    vars[`--nq-${l}-dur`] = `${Math.round(span * p.spreadMs)}ms`
    vars[`--nq-${l}-ease`] =
      `cubic-bezier(${p[`${l}X1`]}, ${p[`${l}Y1`]}, ${p[`${l}X2`]}, ${p[`${l}Y2`]})`
    // The wet edge settles at its dried-back strength (Android fades it live).
    const alpha = p[`${l}Alpha`] * (l === 'wet' ? 1 - p.wetDryBack : 1)
    vars[`--nq-${l}-alpha`] = `${alpha}`
  }
  return vars as CSSProperties
}
