/**
 * The ornament-generating machine — web port.
 *
 * A seeded, pure generator of Islamic geometric compositions: {n/k} star
 * polygons (gcd(n, k) > 1 yields the interlaced polygons — the khatam is
 * exactly {8/2}) and the illuminator's rumi scroll for the border.
 *
 * This file mirrors, line for line, the Android original at
 * `app/src/main/java/com/beautifulquran/ui/theme/ornament/OrnamentGenerator.kt`;
 * both consume an identical mulberry32 stream. Keep the RNG call order in
 * sync when editing either.
 */

/** Fit whole, even repeats across the field without stretching its geometry. */
export function fittedFieldCellWidth(width: number, preferred: number): number {
  return width > 0 ? width / (2 * Math.max(1, Math.round(width / preferred / 2))) : preferred
}

export interface OrnamentPoint {
  x: number
  y: number
}

export type StrokeWeight = 'rule' | 'hairline'

/** An open or closed polyline plus its slot in the real-time build. */
export interface OrnamentStroke {
  points: OrnamentPoint[]
  closed: boolean
  weight: StrokeWeight
  birth: number
  span: number
}

export interface OrnamentDot {
  x: number
  y: number
  radius: number
  birth: number
}

/**
 * A rosette (medallion or corner seal) in the unit square, centre 0.5.
 * Corner seals carry a four-petal bezel whose tips lie on the compass axes
 * at `tipRadius` (0 for the medallion, which has no bezel); the border
 * band's runs start exactly at those tips, so a seal's petals may extend
 * past the unit box.
 */
export interface RosetteSpec {
  fold: number
  strokes: OrnamentStroke[]
  dots: OrnamentDot[]
  tipRadius: number
}

/** Curated repeat families shared with the Android generator and Lab. */
export const FIELD_PATTERNS = ['star-and-cross', 'octagonal garden', 'lozenge rosettes'] as const

/** One translational unit cell of a geometric field with filigree. */
export interface FieldSpec {
  pattern: string
  cellW: number
  cellH: number
  cellWidthDp: number
  strokes: OrnamentStroke[]
}

/** One period of the border frieze; x in [0, period], y in [0, 1]. */
export interface BorderSpec {
  period: number
  strokes: OrnamentStroke[]
  dots: OrnamentDot[]
}

export interface CoverOrnament {
  seed: number
  medallion: RosetteSpec
  cornerSeal: RosetteSpec
  border: BorderSpec
  field: FieldSpec
}

/**
 * A chapter's surah-header ornament: the rosette and the field pattern
 * tooled faintly behind it, grown from the same seed's stream — so the
 * backdrop is drawn from the same chapter fingerprint as the rosette
 * sitting on it, not the fixed weave every chapter used to share.
 */
export interface ChapterOrnament {
  seed: number
  rosette: RosetteSpec
  field: FieldSpec
}

/** mulberry32 — bit-for-bit identical to the Kotlin port. Do not "improve". */
export class Mulberry32 {
  private a: number

  constructor(seed: number) {
    this.a = seed | 0
  }

  nextUInt(): number {
    this.a = (this.a + 0x6d2b79f5) | 0
    let t = Math.imul(this.a ^ (this.a >>> 15), this.a | 1)
    t = (t + Math.imul(t ^ (t >>> 7), t | 61)) ^ t
    return (t ^ (t >>> 14)) >>> 0
  }

  next(): number {
    return this.nextUInt() / 4294967296
  }

  range(lo: number, hi: number): number {
    return lo + (hi - lo) * this.next()
  }

  int(bound: number): number {
    return Math.min(Math.floor(this.next() * bound), bound - 1)
  }

  chance(p: number): boolean {
    return this.next() < p
  }
}

const TAU = 2 * Math.PI

/** All rosette layers grow from a vertex pointing up. */
const ROT0 = -Math.PI / 2

/**
 * Construction radius in the unit box where the seal's bezel cusps sit.
 * The band's rails already are the inner/outer of the border; a drawn
 * circle here would be a third hoop around the star. The constant stays
 * so the bezel and the band still meet.
 */
export const SEAL_RING_RADIUS = 0.46

function gcd(a: number, b: number): number {
  return b === 0 ? a : gcd(b, a % b)
}

/**
 * Star indices whose {n/k} never yields occult compounds.
 *
 * A triangle decomposition (n / gcd(n, k) === 3, e.g. {12/4}) is
 * overlapped triangles — the hexagram. A pentagram decomposition
 * (component {5/2} or {5/3}, e.g. {10/4} → two interlaced pentagrams)
 * is the classic pentacle compound. Both are excluded. The seal fold
 * mapping below also avoids 6-fold stars for the hexagram reason.
 */
function allowedStarKs(n: number): number[] {
  const ks: number[] = []
  for (let k = 2; k <= n / 2 - 1; k++) {
    const g = gcd(n, k)
    const m = n / g
    const s = k / g
    // {3/*} components → hexagram when interlaced.
    if (m === 3) continue
    // {5/2} and {5/3} are the same pentagram; two of them stacked is occult.
    if (m === 5 && (s === 2 || s === 3)) continue
    ks.push(k)
  }
  return ks
}

function polar(angle: number, radius: number): OrnamentPoint {
  return { x: 0.5 + radius * Math.cos(angle), y: 0.5 + radius * Math.sin(angle) }
}

function circleStroke(radius: number, segments: number, weight: StrokeWeight): OrnamentStroke {
  const points: OrnamentPoint[] = []
  for (let i = 0; i < segments; i++) points.push(polar((i * TAU) / segments, radius))
  return { points, closed: true, weight, birth: 0, span: 1 }
}

/** The {n/k} star polygon: gcd(n, k) interlaced closed polylines. */
function starPolygons(
  n: number,
  k: number,
  radius: number,
  rotation: number,
  weight: StrokeWeight,
): OrnamentStroke[] {
  const g = gcd(n, k)
  const polys: OrnamentStroke[] = []
  for (let c = 0; c < g; c++) {
    const points: OrnamentPoint[] = []
    let v = c
    for (let i = 0; i < n / g; i++) {
      points.push(polar(rotation + (v * TAU) / n, radius))
      v = (v + k) % n
    }
    polys.push({ points, closed: true, weight, birth: 0, span: 1 })
  }
  return polys
}

function bezier(
  p0: OrnamentPoint,
  c1: OrnamentPoint,
  c2: OrnamentPoint,
  p1: OrnamentPoint,
  t: number,
): OrnamentPoint {
  const u = 1 - t
  const a = u * u * u
  const b = 3 * u * u * t
  const c = 3 * u * t * t
  const d = t * t * t
  return {
    x: a * p0.x + b * c1.x + c * c2.x + d * p1.x,
    y: a * p0.y + b * c1.y + c * c2.y + d * p1.y,
  }
}

const CUBIC_SAMPLES = 10

function sampleCubicInto(
  out: OrnamentPoint[],
  p0: OrnamentPoint,
  c1: OrnamentPoint,
  c2: OrnamentPoint,
  p1: OrnamentPoint,
): void {
  for (let s = 1; s <= CUBIC_SAMPLES; s++) out.push(bezier(p0, c1, c2, p1, s / CUBIC_SAMPLES))
}

/**
 * The mushaf corolla: n ogee petals as one closed polyline. `rotation` is
 * the first cusp's angle; tips sit half a step past cusps.
 */
function corollaStroke(
  n: number,
  cuspR: number,
  tipR: number,
  rotation: number,
  weight: StrokeWeight,
): OrnamentStroke {
  const step = TAU / n
  const reach = tipR - cuspR
  const points: OrnamentPoint[] = [polar(rotation, cuspR)]
  for (let k = 0; k < n; k++) {
    const cuspA = rotation + k * step
    const tipA = cuspA + step / 2
    const nextA = cuspA + step
    const cusp = polar(cuspA, cuspR)
    const tip = polar(tipA, tipR)
    const next = polar(nextA, cuspR)
    sampleCubicInto(points, cusp, polar(cuspA, cuspR + reach * 0.55), polar(tipA, tipR - reach * 0.45), tip)
    sampleCubicInto(points, tip, polar(tipA, tipR - reach * 0.45), polar(nextA, cuspR + reach * 0.55), next)
  }
  return { points, closed: true, weight, birth: 0, span: 1 }
}

function timed(s: OrnamentStroke, birth: number, span: number): OrnamentStroke {
  return { points: s.points, closed: s.closed, weight: s.weight, birth, span }
}

/** Spread strokes across the build: first ink at 4%, last begins at ~74%. */
function assignBirths(strokes: OrnamentStroke[]): OrnamentStroke[] {
  const n = strokes.length
  return strokes.map((s, i) => {
    const birth = 0.04 + (0.7 * i) / n
    return timed(s, birth, Math.min(0.3, 0.97 - birth))
  })
}

/**
 * The medallion — a shamsa read as four concentric zones, outside in: the
 * gilt rules with their pearl band, the {n/k} star, a secondary motif, and
 * the core. Every zone is sized from the one outside it (independent ranges
 * let neighbouring radii collide or nearly coincide), the core is never
 * empty, and weight thins inward so a dense fold stays legible.
 */
function generateMedallion(rng: Mulberry32, chapter?: number): RosetteSpec {
  const u = rng.next()
  const pairs = chapter === undefined ? [] : [8, 10, 12, 16].flatMap(n => allowedStarKs(n).filter(k => n !== 16 || k <= 4).map(k => [n, k] as const))
  const recipe = chapter === undefined ? undefined : (chapter * 37) % 120
  const pair = recipe === undefined ? undefined : pairs[recipe % 10]
  const fold = pair?.[0] ?? (u < 0.3 ? 8 : u < 0.55 ? 10 : u < 0.85 ? 12 : 16)
  const step = TAU / fold
  const seg = fold * 12
  const strokes: OrnamentStroke[] = []

  // Zone 1 — the doubled rule and the pearl band it carries.
  const r1 = 0.485
  const r2 = rng.range(0.34, 0.385)
  strokes.push(circleStroke(r1, seg, 'hairline'))
  strokes.push(circleStroke(r2, seg, 'hairline'))

  // Zone 2 — the star. Its tips stop a clear gap short of the inner rule
  // so the two never graze; the star owns the widest zone.
  const ks = allowedStarKs(fold)
  const drawnK = ks[rng.int(ks.length)]!
  const k = pair?.[1] ?? drawnK
  const rs = r2 - rng.range(0.03, 0.052)
  strokes.push(...starPolygons(fold, k, rs, ROT0, 'rule'))

  // Dense folds carry their inner zones as hairlines.
  const innerWeight: StrokeWeight = fold >= 12 ? 'hairline' : 'rule'

  // Zone 3 — the secondary motif, a fixed fraction of the star so the
  // annulus between them stays legible at every fold.
  const rMid = rs * rng.range(0.66, 0.74)
  const drawnVariant = rng.int(3)
  const variant = recipe === undefined ? drawnVariant : Math.floor(recipe / 10) % 3
  if (variant === 0) {
    // The woven double star: a second {n/k2} half a step out of phase. k2
    // must differ from k — the same star drawn smaller is an echo, not a
    // weave (at fold 8 the pair is the khatam itself).
    const i2 = rng.int(ks.length)
    const k2 = ks[i2] === k ? ks[(i2 + 1) % ks.length]! : ks[i2]!
    strokes.push(...starPolygons(fold, k2, rMid, ROT0 + Math.PI / fold, innerWeight))
  } else if (variant === 1) {
    const cusp = rMid * rng.range(0.6, 0.68)
    strokes.push(corollaStroke(fold, cusp, rMid, ROT0, innerWeight))
  } else {
    // A ring of kites in the star's interstices, points outward.
    const rBase = rMid * rng.range(0.42, 0.5)
    const rWaist = rBase + (rMid - rBase) * rng.range(0.45, 0.6)
    for (let i = 0; i < fold; i++) {
      const a = ROT0 + (i + 0.5) * step
      strokes.push({
        points: [
          polar(a, rMid),
          polar(a - step * 0.28, rWaist),
          polar(a, rBase),
          polar(a + step * 0.28, rWaist),
        ],
        closed: true,
        weight: innerWeight,
        birth: 0,
        span: 1,
      })
    }
  }

  // Zone 4 — the core, always inked: whatever room zone 3 leaves gets a
  // small rosette of its own rather than a bare field around the heart.
  const rCore = rMid * rng.range(0.5, 0.6)
  const drawnCore = rng.int(3)
  const coreRecipe = recipe === undefined ? drawnCore : Math.floor(recipe / 30)
  if (coreRecipe === 0) {
    const i3 = rng.int(ks.length)
    strokes.push(...starPolygons(fold, ks[i3]!, rCore, ROT0 + Math.PI / fold, 'hairline'))
  } else if (coreRecipe === 1) {
    strokes.push(corollaStroke(fold, rCore * rng.range(0.46, 0.56), rCore, ROT0, 'hairline'))
  } else if (coreRecipe === 3) {
    strokes.push({ points: Array.from({ length: 4 }, (_, i) => polar(ROT0 + i * Math.PI / 2, rCore)),
      closed: true, weight: 'hairline', birth: 0, span: 1 })
  } else {
    // A plain ring alone leaves the centre a bare target, so this one is
    // always pearled below.
    strokes.push(circleStroke(rCore, seg, 'hairline'))
  }

  // The heart: a hairline ring inside the core, its pearl at the centre.
  const heartR = rCore * rng.range(0.34, 0.44)
  strokes.push(circleStroke(heartR, seg, 'hairline'))

  const dots: OrnamentDot[] = []
  const pearlCount = rng.chance(0.5) ? fold * 2 : fold
  const band = (r1 + r2) / 2
  for (let i = 0; i < pearlCount; i++) {
    const p = polar(ROT0 + (i * TAU) / pearlCount, band)
    const radius = pearlCount > fold && i % 2 === 1 ? 0.009 : 0.016
    dots.push({ x: p.x, y: p.y, radius, birth: 0.58 + (0.34 * i) / pearlCount })
  }
  // A pearl in each of the core's own interstices, so the centre reads as
  // worked metal even when its rosette is a plain ring.
  if (rng.chance(0.5) || coreRecipe === 2) {
    const pearlR = (rCore + heartR) / 2
    for (let i = 0; i < fold; i++) {
      const p = polar(ROT0 + (i + 0.5) * step, pearlR)
      dots.push({ x: p.x, y: p.y, radius: 0.01, birth: 0.62 + (0.3 * i) / fold })
    }
  }
  dots.push({ x: 0.5, y: 0.5, radius: Math.min(0.028, heartR * 0.62), birth: 0.93 })

  return { fold, strokes: assignBirths(strokes), dots, tipRadius: 0 }
}

/**
 * The corner seal — a late-inking small star of the medallion's family
 * (never 6-fold: that would force the hexagram), wrapped in a four-petal
 * ogee bezel whose tips lie on the compass axes. Two of those tips aim
 * straight down the border band's two runs at each corner — the band's
 * channel tapers onto them — so seal and border are one piece of geometry,
 * not a stamp over a strip. No enclosing circle: the frame's two gilt
 * rules are already the band's inner and outer.
 */
function generateSeal(rng: Mulberry32, fold: number): RosetteSpec {
  let m = fold >= 12 ? fold / 2 : fold
  if (m === 6) m = 8
  const ks = allowedStarKs(m)
  const k = ks[rng.int(ks.length)]!
  const starR = rng.range(0.32, 0.37)
  const tip = rng.range(0.58, 0.66)

  const strokes: OrnamentStroke[] = []
  // Bezel cusps on the diagonals at SEAL_RING_RADIUS; tips on the axes.
  strokes.push(corollaStroke(4, SEAL_RING_RADIUS, tip, ROT0 - Math.PI / 4, 'hairline'))
  strokes.push(...starPolygons(m, k, starR, ROT0, 'hairline'))
  const n = strokes.length
  const stamped = strokes.map((s, i) => {
    const birth = 0.55 + (0.3 * i) / n
    return timed(s, birth, Math.min(0.25, 0.97 - birth))
  })
  return {
    fold: m,
    strokes: stamped,
    dots: [{ x: 0.5, y: 0.5, radius: 0.058, birth: 0.88 }],
    tipRadius: tip,
  }
}

/**
 * The border band: a rumi scroll, the running gold border of Ottoman and
 * Mamluk illuminated Qur'ans. One vine waves along the band's centre; under
 * every crest a split palmette (rumi) springs into the hollow and ends in a
 * beak curling back over its own body, vein down its middle, glide-mirrored
 * under each trough, with a pearl between each crest and its rail. The
 * seed only nudges proportions, and the draws match the five-grammar picker
 * this replaced, so the field grown after it is unchanged for every seed.
 * Mirrors Android's generateBorder line for line.
 */
function generateBorder(rng: Mulberry32): BorderSpec {
  const draws = BORDER_LEGACY_DRAWS[rng.int(BORDER_LEGACY_DRAWS.length)]!
  const v = Array.from({ length: draws }, () => rng.next())
  const period = 2.2 + 0.4 * v[0]!
  const leafWidth = 0.24 + 0.04 * v[1]!

  const strokes: OrnamentStroke[] = []
  const dots: OrnamentDot[] = []
  const hair = (points: OrnamentPoint[], closed: boolean): OrnamentStroke => ({
    points,
    closed,
    weight: 'hairline',
    birth: 0,
    span: 1,
  })

  // The vine crosses the centre at each period boundary, so every run
  // enters its corner seals at mid-band.
  const stemY = (x: number) => 0.5 - RUMI_AMP * Math.sin((TAU * x) / period)
  const stem: OrnamentPoint[] = []
  for (let i = 0; i <= RUMI_STEM_SAMPLES; i++) {
    const x = (period * i) / RUMI_STEM_SAMPLES
    stem.push({ x, y: stemY(x) })
  }
  strokes.push(hair(stem, false))

  const crest = period / 4
  const baseX = crest + 0.14 * period
  const base = { x: baseX, y: stemY(baseX) }
  const tip = { x: crest - 0.12 * period, y: 0.66 }
  const spine = rumiSpine(
    base,
    { x: base.x - 0.01 * period, y: base.y + 0.42 },
    { x: tip.x + 0.12 * period, y: tip.y + 0.2 },
    tip,
  )
  const outline = rumiOutline(spine, leafWidth)
  const vein = spine.slice(3, Math.round(spine.length * 0.55))
  const pearl = { x: crest, y: 0.14 }
  for (const glide of [false, true]) {
    const map = (p: OrnamentPoint): OrnamentPoint =>
      glide ? { x: p.x + period / 2, y: 1 - p.y } : p
    strokes.push(hair(outline.map(map), true))
    strokes.push(hair(vein.map(map), false))
    const d = map(pearl)
    dots.push({ x: d.x, y: d.y, radius: 0.055, birth: 0 })
  }
  // Rule-weight edge rails bound the scroll into one tooled channel.
  for (const y of [0, 1]) {
    strokes.push({
      points: [
        { x: 0, y },
        { x: period, y },
      ],
      closed: false,
      weight: 'rule',
      birth: 0,
      span: 1,
    })
  }
  return { period, strokes, dots }
}

/** Draws each of the replaced border grammars took after its pick, in pick order. */
const BORDER_LEGACY_DRAWS = [2, 3, 2, 2, 3]

/** The vine's swing either side of the band's centre, in band heights. */
const RUMI_AMP = 0.2

const RUMI_STEM_SAMPLES = 32

/** The rumi's spine: a cubic to `tip`, then the beak hooking back over the body. */
function rumiSpine(
  p0: OrnamentPoint,
  c1: OrnamentPoint,
  c2: OrnamentPoint,
  tip: OrnamentPoint,
): OrnamentPoint[] {
  const n = 24
  const pts: OrnamentPoint[] = []
  for (let i = 0; i <= n; i++) pts.push(bezier(p0, c1, c2, tip, i / n))
  const prev = pts[n - 1]!
  const len = Math.hypot(tip.x - prev.x, tip.y - prev.y)
  const tx = (tip.x - prev.x) / len
  const ty = (tip.y - prev.y) / len
  const r = 0.07
  const cx = tip.x - ty * r
  const cy = tip.y + tx * r
  const a0 = Math.atan2(tip.y - cy, tip.x - cx)
  const m = 12
  for (let i = 1; i <= m; i++) {
    const f = i / m
    const a = a0 + 0.55 * TAU * f
    const rr = r * (1 - 0.35 * f)
    pts.push({ x: cx + rr * Math.cos(a), y: cy + rr * Math.sin(a) })
  }
  return pts
}

/** Closed leaf outline round `spine`: stalk, swell at a third, taper to the beak. */
function rumiOutline(spine: OrnamentPoint[], width: number): OrnamentPoint[] {
  const n = spine.length - 1
  const left: OrnamentPoint[] = []
  const right: OrnamentPoint[] = []
  const peak = 0.3
  for (let i = 0; i <= n; i++) {
    const t = i / n
    const p = spine[i]!
    const q = spine[Math.min(n, i + 1)]!
    const r = spine[Math.max(0, i - 1)]!
    const len = Math.hypot(q.x - r.x, q.y - r.y)
    const tx = (q.x - r.x) / len
    const ty = (q.y - r.y) / len
    const swell =
      t < peak
        ? 0.3 + 0.7 * Math.sin(((t / peak) * Math.PI) / 2)
        : Math.pow(Math.max(Math.cos((((t - peak) / (1 - peak)) * Math.PI) / 2), 0), 1.2)
    const half = (width * swell) / 2
    left.push({ x: p.x - ty * half, y: p.y + tx * half })
    right.push({ x: p.x + ty * half, y: p.y - tx * half })
  }
  return [...left, ...right.slice(0, n).reverse()]
}

// ── Geometric fields with filigree ──────────────────────────────────────────────

/** A sampled cubic path; closed motifs explicitly return to their first point. */
function fieldCurve(coords: number[][], closed: boolean): OrnamentStroke {
  const nodes = coords.map(([x, y]) => ({ x: x!, y: y! }))
  const points = [nodes[0]!]
  for (let i = 1; i < nodes.length; i += 3) {
    sampleCubicInto(points, points[points.length - 1]!, nodes[i]!, nodes[i + 1]!, nodes[i + 2]!)
  }
  return { points, closed, weight: 'hairline', birth: 0, span: 1 }
}

/**
 * Composed cover families and chapter recipes with compartment-specific filigree.
 * Four RNG draws choose a family and its safe proportions. See docs/ORNAMENT_FIELDS.md.
 */
function generateField(rng: Mulberry32, chapter?: number): FieldSpec {
  const choice = rng.range(0, 3)
  const family = chapter === undefined ? Math.floor(choice) : chapter % 4
  const layout = chapter === undefined ? family : chapter % 7
  const corner = chapter === undefined ? (family === 2 ? 1 : 0) : chapter % 5
  const inset = 0.042 + (choice - Math.floor(choice)) * 0.01
  const curl = rng.range(0.265, 0.285)
  const tip = rng.range(0.392, 0.410)
  const spacing = rng.range(48, 56)
  const star = (radius: number, weight: StrokeWeight): OrnamentStroke => ({
    points: Array.from({ length: 16 }, (_, i) => polar(i * Math.PI / 8,
      radius * (i % 2 ? Math.cos(Math.PI / 4) / Math.cos(Math.PI / 8) : 1))),
    closed: true, weight, birth: 0, span: 1,
  })
  const frame = (radius: number): OrnamentStroke => family === 0 ? star(radius, 'rule') : family === 3 ? ({
    points: Array.from({ length: 4 }, (_, i) => [[1, 0], [0.94, 0.47], [0.47, 0.94]].map(([x, y]) => ({
      x: 0.5 + radius * (x! * Math.cos(i * Math.PI / 2) - y! * Math.sin(i * Math.PI / 2)),
      y: 0.5 + radius * (x! * Math.sin(i * Math.PI / 2) + y! * Math.cos(i * Math.PI / 2)),
    }))).flat(), closed: true, weight: 'rule', birth: 0, span: 1,
  }) : ({
    points: Array.from({ length: family === 1 ? 8 : 4 }, (_, i) =>
      polar(i * TAU / (family === 1 ? 8 : 4), radius)),
    closed: true, weight: 'rule', birth: 0, span: 1,
  })
  const strokes = [frame(0.5), frame(0.5 - inset), star(0.13, 'hairline')]
  const scroll = fieldCurve([
    [0.13, 0], [0.19, 0], [0.20, -0.075], [0.27, -0.075],
    [0.34, -0.075], [0.35, -0.01], [0.30, -0.012],
    [0.26, -0.001], [0.24, -0.045], [curl, -0.05],
  ], false)
  const leaf = fieldCurve([
    [0.35, 0], [0.37, -0.02], [tip - 0.02, -0.02], [tip, 0],
    [tip - 0.02, 0.02], [0.37, 0.02], [0.35, 0],
  ], true)
  const petal = fieldCurve([
    [0.13, 0], [0.19, -0.035], [0.29, -0.055], [tip, 0],
    [0.29, 0.055], [0.19, 0.035], [0.13, 0],
  ], true)
  const vein = fieldCurve([
    [0.13, 0], [0.20, 0], [0.25, -0.025], [curl + 0.04, 0],
    [0.25, 0.025], [0.20, 0], [0.13, 0],
  ], true)
  const spear: OrnamentStroke = { ...petal, points: [
    { x: 0.13, y: 0 }, { x: 0.21, y: -0.07 }, { x: 0.33, y: -0.05 },
    { x: tip, y: 0 }, { x: 0.33, y: 0.05 }, { x: 0.21, y: 0.07 },
  ] }
  const paired: [OrnamentStroke, number][] = [[scroll, -1], [scroll, 1], [leaf, 1]]
  const petals: [OrnamentStroke, number][] = [[petal, 1], [vein, 1]]
  const fold = layout === 2 || layout === 3 ? 4 : 8
  if (layout === 6) strokes.push(star(0.23, 'hairline'))
  for (let i = 0; i < fold; i++) {
    const a = i * TAU / fold
    const motifs = layout === 6 ? [[leaf, 1] as [OrnamentStroke, number]] :
      layout === 5 ? (i % 2 ? [[leaf, 1]] : [[spear, 1], [vein, 1]]) as [OrnamentStroke, number][] :
      layout === 1 || layout === 3 || (layout === 4 && i % 2 === 0) ? petals : paired
    // Narrow diamond compartments have less room along the diagonals.
    const scale = chapter !== undefined && (family === 2 || layout === 4) && fold === 8 && i % 2 === 1 ? 0.55 : 1
    for (const [motif, sign] of motifs) {
      strokes.push({ ...motif, points: motif.points.map((p) => {
        const x = 0.13 + (p.x - 0.13) * scale, y = p.y * scale * sign
        return { x: 0.5 + x * Math.cos(a) - y * Math.sin(a), y: 0.5 + x * Math.sin(a) + y * Math.cos(a) }
      }) })
    }
  }
  // Four quarters complete one floret at each shared cross-compartment centre.
  const quarter = fieldCurve([
    [0.04, 0], [0.068, 0], [0.045, 0.045], [0.064, 0.064],
    [0.045, 0.045], [0, 0.068], [0, 0.04],
  ], false)
  const cornerStar: OrnamentStroke = { ...star(0.18, 'hairline'), closed: false,
    points: star(0.18, 'hairline').points.slice(0, 5).map((p) => ({ x: p.x - 0.5, y: p.y - 0.5 })) }
  const bud = { ...petal, points: petal.points.map(p => ({ x: (p.x - p.y) * 0.16 / Math.SQRT2,
    y: (p.x + p.y) * 0.16 / Math.SQRT2 })) }
  const diamond = { ...cornerStar, points: [{ x: 0.14, y: 0 }, { x: 0.07, y: 0.07 }, { x: 0, y: 0.14 }] }
  const corners = corner === 1 ? [cornerStar, { ...quarter,
    points: quarter.points.map((p) => ({ x: p.x * (chapter === undefined ? 1.5 : 1.2), y: p.y * (chapter === undefined ? 1.5 : 1.2) })) }] :
    corner === 2 ? [{ ...bud, points: bud.points.map(p => ({ x: p.x * 1.45, y: p.y * 1.45 })) }] :
    corner === 3 ? [diamond, bud] : corner === 4 ? [{
      ...circleStroke(0.03, 16, 'hairline'), points: circleStroke(0.03, 16, 'hairline').points.map(p => ({ x: p.x - 0.5 + 0.07, y: p.y - 0.5 + 0.07 })),
    }] : [quarter]
  const cornerScale = chapter !== undefined && family === 3 ? 0.6 : 1
  for (const [cx, cy, sx, sy] of [[0, 0, 1, 1], [1, 0, -1, 1], [1, 1, -1, -1], [0, 1, 1, -1]]) {
    for (const motif of corners) strokes.push({ ...motif,
      points: motif.points.map((p) => ({ x: cx! + sx! * p.x * cornerScale, y: cy! + sy! * p.y * cornerScale })) })
  }
  const inkLength = strokes.reduce((total, s) => {
    const points = s.closed ? [...s.points, s.points[0]!] : s.points
    const length = points.slice(1).reduce((sum, p, i) => {
      const q = points[i]!
      return sum + Math.hypot(p.x - q.x, p.y - q.y)
    }, 0)
    return total + length * (s.weight === 'rule' ? 1 : 0.55)
  }, 0)
  return { pattern: [...FIELD_PATTERNS, 'chamfered lattice'][family]!, cellW: 1, cellH: 1,
    cellWidthDp: chapter === undefined ? spacing * inkLength / [11.5, 11, 9][family]! :
      Math.min(58, Math.max(spacing, spacing * inkLength / 11.5)), strokes }
}

/**
 * Grow the full cover ornament from a seed. RNG call order is part of the
 * cross-platform contract with the Android original; never reorder.
 */
export function generateCoverOrnament(seed: number): CoverOrnament {
  const rng = new Mulberry32(seed)
  const medallion = generateMedallion(rng)
  const cornerSeal = generateSeal(rng, medallion.fold)
  const border = generateBorder(rng)
  const field = generateField(rng)
  return { seed, medallion, cornerSeal, border, field }
}

/**
 * Chapter number fixes structural identity; verse count varies safe proportions.
 * The zero-based chapter is recovered by floorMod(seed - 1, 114).
 */
export function chapterOrnamentSeed(chapterNumber: number, ayahCount: number): number {
  return ayahCount * 114 + chapterNumber
}

/**
 * Grow a chapter's rosette and backing field — no corner seal or border,
 * which the header has no use for — from a seed. Chapter number assigns distinct structural recipes; ayah count
 * varies their proportions. Same star-polygon and
 * rosette/arabesque vocabulary and RNG rules as a full cover ornament.
 */
export function generateChapterOrnament(seed: number): ChapterOrnament {
  const rng = new Mulberry32(seed)
  // 4 frames x 7 interiors x 5 corners: coprime axes give 140 distinct recipes.
  const chapter = ((seed - 1) % 114 + 114) % 114
  const rosette = generateMedallion(rng, chapter)
  const field = generateField(rng, chapter)
  return { seed, rosette, field }
}
