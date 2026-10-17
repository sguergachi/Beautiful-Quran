/**
 * Calligraphic ink-check. Geometry + stroke knobs are lab-tunable.
 * Keep in lockstep with Android `inkBrushCheckPath` / `BrushCheckParams`.
 */
import { brushPressure, type BrushCircleParams } from './brushMark'

/** Check glyph + stroke. Centerline points are unit coords (0…1). */
export type BrushCheckParams = {
  /** Stem start X. */
  p0x: number
  p0y: number
  /** Valley corner X. */
  p1x: number
  p1y: number
  /** Arm tip X. */
  p2x: number
  p2y: number
  /** Glyph size in CSS px / Compose dp. */
  size: number
  /** Stroke half-width in the same units as [size] (like circle peakHalf). */
  peakHalf: number
  nibBias: number
  attack: number
  releaseStart: number
  bodyAmp: number
  bodyFreq: number
  paintMs: number
  alpha: number
}

export type BrushCheckKnobKey = keyof BrushCheckParams

export const BRUSH_CHECK_KNOB_KEYS = [
  'p0x',
  'p0y',
  'p1x',
  'p1y',
  'p2x',
  'p2y',
  'size',
  'peakHalf',
  'nibBias',
  'attack',
  'releaseStart',
  'bodyAmp',
  'bodyFreq',
  'paintMs',
  'alpha',
] as const satisfies readonly BrushCheckKnobKey[]

/** Shipped check — bump [SHIPPED_CHECK_REVISION] when this changes. */
export const SHIPPED_CHECK_REVISION = 2

export const SHIPPED_CHECK_PARAMS: BrushCheckParams = {
  p0x: 0.1,
  p0y: 0.49,
  p1x: 0.39,
  p1y: 0.8,
  p2x: 0.73,
  p2y: 0.11,
  size: 24,
  peakHalf: 1.68,
  nibBias: 0.56,
  attack: 0.184,
  releaseStart: 0.74,
  bodyAmp: 0.1,
  bodyFreq: 2.2,
  paintMs: 833,
  alpha: 0.75,
}

export const BRUSH_CHECK_KNOB_SLIDERS: {
  key: BrushCheckKnobKey
  label: string
  min: number
  max: number
  step: number
  format?: (v: number) => string
}[] = [
  { key: 'p0x', label: 'Stem X', min: 0.05, max: 0.45, step: 0.01, format: (v) => v.toFixed(2) },
  { key: 'p0y', label: 'Stem Y', min: 0.2, max: 0.8, step: 0.01, format: (v) => v.toFixed(2) },
  { key: 'p1x', label: 'Valley X', min: 0.2, max: 0.6, step: 0.01, format: (v) => v.toFixed(2) },
  { key: 'p1y', label: 'Valley Y', min: 0.5, max: 0.95, step: 0.01, format: (v) => v.toFixed(2) },
  { key: 'p2x', label: 'Tip X', min: 0.55, max: 0.98, step: 0.01, format: (v) => v.toFixed(2) },
  { key: 'p2y', label: 'Tip Y', min: 0.05, max: 0.5, step: 0.01, format: (v) => v.toFixed(2) },
  { key: 'size', label: 'Size', min: 14, max: 36, step: 1, format: (v) => `${Math.round(v)}` },
  {
    key: 'peakHalf',
    label: 'Stroke half',
    min: 0.6,
    max: 4.5,
    step: 0.05,
    format: (v) => v.toFixed(2),
  },
  {
    key: 'nibBias',
    label: 'Nib bias',
    min: 0,
    max: 0.8,
    step: 0.01,
    format: (v) => v.toFixed(2),
  },
  {
    key: 'attack',
    label: 'Attack',
    min: 0.02,
    max: 0.4,
    step: 0.005,
    format: (v) => v.toFixed(3),
  },
  {
    key: 'releaseStart',
    label: 'Release start',
    min: 0.4,
    max: 0.98,
    step: 0.01,
    format: (v) => v.toFixed(2),
  },
  {
    key: 'bodyAmp',
    label: 'Body amp',
    min: 0,
    max: 0.6,
    step: 0.01,
    format: (v) => String(Math.round(v * 100) / 100),
  },
  {
    key: 'bodyFreq',
    label: 'Body freq',
    min: 0.5,
    max: 12,
    step: 0.1,
    format: (v) => {
      const r = Math.round(v * 10) / 10
      return Number.isInteger(r) ? String(r) : r.toFixed(1)
    },
  },
  {
    key: 'paintMs',
    label: 'Paint ms',
    min: 200,
    max: 1200,
    step: 10,
    format: (v) => `${Math.round(v)}`,
  },
  {
    key: 'alpha',
    label: 'Alpha',
    min: 0.3,
    max: 1,
    step: 0.01,
    format: (v) => String(Math.round(v * 100) / 100),
  },
]

function pressureParams(p: BrushCheckParams): BrushCircleParams {
  return {
    label: 'check',
    padX: 0,
    padY: 0,
    peakHalf: p.peakHalf,
    startDeg: 0,
    startOvershoot: 0,
    endOvershoot: 0,
    bow: 0,
    bowSpan: 0.1,
    breath: 0,
    nibBias: p.nibBias,
    attack: p.attack,
    releaseStart: p.releaseStart,
    bodyAmp: p.bodyAmp,
    bodyFreq: p.bodyFreq,
    paintMs: p.paintMs,
    alpha: p.alpha,
  }
}

function samplesFor(p: BrushCheckParams): { x: number; y: number; t: number }[] {
  const center = [
    { x: p.p0x, y: p.p0y },
    { x: p.p1x, y: p.p1y },
    { x: p.p2x, y: p.p2y },
  ]
  const raw: { x: number; y: number }[] = []
  const segs = 24
  for (let s = 0; s < center.length - 1; s++) {
    const a = center[s]
    const b = center[s + 1]
    for (let i = 0; i < segs; i++) {
      const u = i / segs
      raw.push({ x: a.x + (b.x - a.x) * u, y: a.y + (b.y - a.y) * u })
    }
  }
  raw.push(center[center.length - 1])

  let total = 0
  const lens = [0]
  for (let i = 1; i < raw.length; i++) {
    total += Math.hypot(raw[i].x - raw[i - 1].x, raw[i].y - raw[i - 1].y)
    lens.push(total)
  }
  return raw.map((pt, i) => ({
    x: pt.x,
    y: pt.y,
    t: total > 0 ? lens[i] / total : 0,
  }))
}

/**
 * Filled brush check. [size] is the glyph box in px; defaults come from
 * [params] (peakHalf, geometry, pressure). Pass [params] from the lab.
 */
export function brushCheckPath(
  size: number,
  progress: number,
  params: BrushCheckParams = SHIPPED_CHECK_PARAMS,
): string {
  const prog = Math.min(1, Math.max(0.02, progress))
  const samples = samplesFor(params)
  const pts = samples.filter((p) => p.t <= prog)
  if (pts.length < 2) {
    const a = samples[0]
    const b = samples[1]
    pts.length = 0
    pts.push(a, {
      x: a.x + (b.x - a.x) * 0.08,
      y: a.y + (b.y - a.y) * 0.08,
      t: 0.02,
    })
  }

  const press = pressureParams(params)
  const tops: { x: number; y: number }[] = []
  const bots: { x: number; y: number }[] = []

  for (let i = 0; i < pts.length; i++) {
    const p = pts[i]
    const prev = pts[Math.max(0, i - 1)]
    const next = pts[Math.min(pts.length - 1, i + 1)]
    let tx = next.x - prev.x
    let ty = next.y - prev.y
    const tLen = Math.hypot(tx, ty) || 1
    tx /= tLen
    ty /= tLen
    let nx = -ty
    let ny = tx
    const bx = nx + -ny * params.nibBias
    const by = ny + nx * params.nibBias
    const nLen = Math.hypot(bx, by) || 1
    nx = bx / nLen
    ny = by / nLen
    const half = params.peakHalf * brushPressure(p.t, press)
    const x = p.x * size
    const y = p.y * size
    tops.push({ x: x + nx * half, y: y + ny * half })
    bots.push({ x: x - nx * half, y: y - ny * half })
  }

  const f = (n: number) => n.toFixed(2)
  let d = `M${f(tops[0].x)} ${f(tops[0].y)}`
  for (let i = 1; i < tops.length; i++) d += `L${f(tops[i].x)} ${f(tops[i].y)}`
  for (let i = bots.length - 1; i >= 0; i--) d += `L${f(bots[i].x)} ${f(bots[i].y)}`
  return d + 'Z'
}

export function formatBrushCheckCopy(p: BrushCheckParams): string {
  const f = (v: number, digits: number) => {
    const s = v.toFixed(digits)
    return s.replace(/\.?0+$/, '') || '0'
  }
  return `// Ink check — paste into the check lab or brushCheck.ts SHIPPED_CHECK_PARAMS
// TypeScript
{
  p0x: ${f(p.p0x, 2)},
  p0y: ${f(p.p0y, 2)},
  p1x: ${f(p.p1x, 2)},
  p1y: ${f(p.p1y, 2)},
  p2x: ${f(p.p2x, 2)},
  p2y: ${f(p.p2y, 2)},
  size: ${Math.round(p.size)},
  peakHalf: ${f(p.peakHalf, 2)},
  nibBias: ${f(p.nibBias, 2)},
  attack: ${f(p.attack, 3)},
  releaseStart: ${f(p.releaseStart, 2)},
  bodyAmp: ${f(p.bodyAmp, 2)},
  bodyFreq: ${f(p.bodyFreq, 1)},
  paintMs: ${Math.round(p.paintMs)},
  alpha: ${f(p.alpha, 2)},
}

// Kotlin
BrushCheckParams(
    p0x = ${f(p.p0x, 2)}f,
    p0y = ${f(p.p0y, 2)}f,
    p1x = ${f(p.p1x, 2)}f,
    p1y = ${f(p.p1y, 2)}f,
    p2x = ${f(p.p2x, 2)}f,
    p2y = ${f(p.p2y, 2)}f,
    sizeDp = ${Math.round(p.size)}f,
    peakHalfDp = ${f(p.peakHalf, 2)}f,
    nibBias = ${f(p.nibBias, 2)}f,
    attack = ${f(p.attack, 3)}f,
    releaseStart = ${f(p.releaseStart, 2)}f,
    bodyAmp = ${f(p.bodyAmp, 2)}f,
    bodyFreq = ${f(p.bodyFreq, 1)}f,
    paintMs = ${Math.round(p.paintMs)},
    alpha = ${f(p.alpha, 2)}f,
)
`
}

export function formatBrushCheckVerifyLines(p: BrushCheckParams): string {
  return BRUSH_CHECK_KNOB_SLIDERS.map(
    (s) => `${s.label} (${s.key}): ${p[s.key]}`,
  ).join('\n')
}

const PASTE_KEY_MAP: Record<string, BrushCheckKnobKey> = {
  p0x: 'p0x',
  p0y: 'p0y',
  p1x: 'p1x',
  p1y: 'p1y',
  p2x: 'p2x',
  p2y: 'p2y',
  size: 'size',
  sizeDp: 'size',
  peakHalf: 'peakHalf',
  peakHalfDp: 'peakHalf',
  nibBias: 'nibBias',
  attack: 'attack',
  releaseStart: 'releaseStart',
  bodyAmp: 'bodyAmp',
  bodyFreq: 'bodyFreq',
  paintMs: 'paintMs',
  alpha: 'alpha',
}

/** Parse a check-lab copy block (TS and/or Kotlin). */
export function parseBrushCheckFromText(
  text: string,
  base: BrushCheckParams = SHIPPED_CHECK_PARAMS,
): BrushCheckParams | null {
  const ts = text.match(/\{[^{}]*\}/)
  const kotlin = text.match(/BrushCheckParams\s*\([^{}]*?\)/)
  const source = ts?.[0] ?? kotlin?.[0] ?? text
  const re =
    /([A-Za-z_][A-Za-z0-9_]*)\s*[=:]\s*(-?\d+(?:\.\d+)?)(?:f\b|(?=\s*[,)}\n]|$))/g
  const next: BrushCheckParams = { ...base }
  let hits = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(source)) != null) {
    const field = PASTE_KEY_MAP[m[1]]
    if (!field) continue
    const n = Number(m[2])
    if (!Number.isFinite(n)) continue
    next[field] = field === 'paintMs' || field === 'size' ? Math.round(n) : n
    hits++
  }
  return hits > 0 ? next : null
}
