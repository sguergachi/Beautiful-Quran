import { createContext } from 'react'

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
  spreadSharpness: number
  liftMs: number
  liftX1: number
  liftY1: number
  liftX2: number
  liftY2: number
  originDx: number
  originDy: number
  reach: number
  fingers: number
  grain: number
  seed: number
  wetAlpha: number
  inkAlpha: number
  soakDelay: number
  feather: number
  fringeAlpha: number
}

export type NuqtaKnobKey = keyof NuqtaParams

/** The shipped nuqta — Android `ShippedNuqtaParams`. */
export const SHIPPED_NUQTA: NuqtaParams = {
  sizeDp: 23,
  bow: 1,
  strokeDp: 1.15,
  restingOutlineAlpha: 0.63,
  selectedOutlineAlpha: 1,
  spreadMs: 405,
  spreadSharpness: 1.6,
  liftMs: 220,
  liftX1: 0.4,
  liftY1: 0,
  liftX2: 1,
  liftY2: 1,
  originDx: -0.6,
  originDy: 0.5,
  reach: 0.76,
  fingers: 0.42,
  grain: 0.06,
  seed: 7,
  wetAlpha: 0.28,
  inkAlpha: 0.9,
  soakDelay: 0.22,
  feather: 0.3,
  fringeAlpha: 0.2,
}

/** What every nuqta draws; only Settings → Developer's nuqta lab overrides it. */
export const NuqtaParamsContext = createContext<NuqtaParams>(SHIPPED_NUQTA)

type Knob = { key: NuqtaKnobKey; label: string; min: number; max: number; digits: number }

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
    title: 'Clock',
    knobs: [
      { key: 'spreadMs', label: 'Spread ms', min: 120, max: 2000, digits: 0 },
      { key: 'spreadSharpness', label: 'Run-out', min: 1, max: 8, digits: 1 },
      { key: 'liftMs', label: 'Lift ms', min: 0, max: 800, digits: 0 },
      { key: 'liftX1', label: 'Curve x1', min: 0, max: 1, digits: 2 },
      { key: 'liftY1', label: 'Curve y1', min: -0.5, max: 1.5, digits: 2 },
      { key: 'liftX2', label: 'Curve x2', min: 0, max: 1, digits: 2 },
      { key: 'liftY2', label: 'Curve y2', min: -0.5, max: 1.5, digits: 2 },
    ],
  },
  {
    title: 'Drop',
    knobs: [
      { key: 'originDx', label: 'Lands x (dp)', min: -4, max: 4, digits: 1 },
      { key: 'originDy', label: 'Lands y (dp)', min: -4, max: 4, digits: 1 },
      { key: 'reach', label: 'Reach', min: 0.6, max: 1.8, digits: 2 },
      { key: 'fingers', label: 'Fibre runs', min: 0, max: 1, digits: 2 },
      { key: 'grain', label: 'Edge grain', min: 0, max: 0.3, digits: 2 },
      { key: 'seed', label: 'Paper seed', min: 0, max: 64, digits: 0 },
    ],
  },
  {
    title: 'Ink',
    knobs: [
      { key: 'wetAlpha', label: 'Lands at', min: 0, max: 1, digits: 2 },
      { key: 'inkAlpha', label: 'Soaks to', min: 0, max: 1, digits: 2 },
      { key: 'soakDelay', label: 'Soak delay', min: 0, max: 0.9, digits: 2 },
      { key: 'feather', label: 'Wet fringe', min: 0, max: 0.9, digits: 2 },
      { key: 'fringeAlpha', label: 'Fringe ink', min: 0, max: 1, digits: 2 },
    ],
  },
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
    next[key] = KNOBS.get(key)?.digits === 0 && key !== 'sizeDp' ? Math.round(n) : n
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

/** Directions the drop's edge is sampled in — Android `NuqtaDropSamples`. */
export const NUQTA_DROP_SAMPLES = 40

/** How far the front has run, 0..1, at clock [t]: `1 − (1 − t)^sharpness`. */
export function nuqtaFront(t: number, sharpness: number): number {
  return 1 - (1 - Math.min(Math.max(t, 0), 1)) ** sharpness
}

/** The wet fringe's share of the drop's radius at [t]; it dries as the drop settles. */
export function nuqtaFeather(t: number, p: NuqtaParams): number {
  return Math.min(Math.max(p.feather * (1 - 0.85 * nuqtaFront(t, p.spreadSharpness)), 0), 1)
}

/** Gradient stops in [nuqtaInkStops]: landing point, core, fringe, tip. */
export const NUQTA_INK_STOP_COUNT = 4

/**
 * Android `nuqtaInkStops`: ink density across the drop at [t], as
 * (offset, alpha) pairs from where it landed out to its edge. A dense core
 * soaks outward `soakDelay` behind a pale running front whose tip is a
 * feathered fringe; the front darkens too as the drop settles, so the nuqta
 * ends solid. [edge] is the drop's farthest reach, as a share of full.
 */
export function nuqtaInkStops(t: number, p: NuqtaParams, edge: number): number[] {
  const clamp = (x: number, lo = 0, hi = 1) => Math.min(Math.max(x, lo), hi)
  const smooth = (x: number) => {
    const c = clamp(x)
    return c * c * (3 - 2 * c)
  }
  const landed = clamp(t / 0.05)
  const lagT = clamp((t - p.soakDelay) / Math.max(1 - p.soakDelay, 1e-3))
  const feather = nuqtaFeather(t, p)
  const core = clamp(nuqtaFront(lagT, p.spreadSharpness) / Math.max(edge, 1e-3), 0, 1 - feather)
  const front = p.wetAlpha + (p.inkAlpha - p.wetAlpha) * smooth(lagT)
  const centre = p.wetAlpha + (p.inkAlpha - p.wetAlpha) * smooth(t / Math.max(p.soakDelay, 1e-3))
  return [
    0, landed * centre,
    core, landed * centre,
    1 - feather, landed * front,
    1, landed * front * p.fringeAlpha,
  ]
}

/**
 * Android `nuqtaFingers`: per direction, a coarse run-speed bias in −1..1
 * (first half) and a fine grain in −1..1 (second half). Integer hashing
 * matches Kotlin's 32-bit overflow so both platforms draw the same paper.
 */
export function nuqtaFingers(seed: number): Float32Array {
  const phase = (k: number) => {
    let h = (Math.imul(seed, 374761393) + Math.imul(k, 668265263)) | 0
    h = Math.imul(h ^ (h >>> 13), 1274126177)
    return (((h ^ (h >>> 16)) & 0xffff) / 65535) * 2 * Math.PI
  }
  const n = NUQTA_DROP_SAMPLES
  const out = new Float32Array(n * 2)
  for (const layer of [0, 1]) {
    const ks = layer === 0 ? [2, 3, 4, 5] : [9, 10, 11, 12, 13]
    const values = Array.from({ length: n }, (_, i) => {
      const a = (i * 2 * Math.PI) / n
      return ks.reduce((sum, k) => sum + Math.sin(k * a + phase(k + layer * 31)) / k, 0)
    })
    const peak = Math.max(...values.map(Math.abs), 1e-6)
    values.forEach((v, i) => (out[layer * n + i] = v / peak))
  }
  return out
}

/** The drop's edge at [t], per direction, as a share of its full radius. */
export function nuqtaDropRadii(t: number, p: NuqtaParams, fingers: Float32Array): number[] {
  const n = NUQTA_DROP_SAMPLES
  const front = nuqtaFront(t, p.spreadSharpness)
  return Array.from({ length: n }, (_, i) => {
    // Fast fibres lead and slow ones lag by a bounded share of the drop,
    // and every direction arrives at the settle together.
    const dir = front * (1 + Math.min(Math.max(p.fingers, 0), 1) * fingers[i] * (1 - front))
    return dir * (1 + p.grain * fingers[n + i] * (1 - dir))
  })
}

/** A smooth closed curve through the drop's edge samples. */
export function nuqtaDropPath(ox: number, oy: number, full: number, radii: number[]): string {
  const n = radii.length
  const pts = radii.map((r, i) => {
    const a = (i * 2 * Math.PI) / n
    return [ox + Math.cos(a) * r * full, oy + Math.sin(a) * r * full]
  })
  const f = (v: number) => v.toFixed(3)
  const mid = (a: number[], b: number[]) => `${f((a[0] + b[0]) / 2)} ${f((a[1] + b[1]) / 2)}`
  let d = `M${mid(pts[n - 1], pts[0])}`
  for (let i = 0; i < n; i++) {
    d += `Q${f(pts[i][0])} ${f(pts[i][1])} ${mid(pts[i], pts[(i + 1) % n])}`
  }
  return `${d}Z`
}
