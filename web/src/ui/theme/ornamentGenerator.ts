/**
 * The ornament-generating machine — web port.
 *
 * A seeded, pure generator of Islamic geometric compositions built from the
 * two classical construction methods: {n/k} star polygons (gcd(n, k) > 1
 * yields the interlaced polygons — the khatam is exactly {8/2}) and
 * Hankin's "polygons in contact" method (rays from tile-edge midpoints at a
 * contact angle θ), plus the five frieze grammars of tooled binding borders.
 *
 * This file mirrors, line for line, the Android original at
 * `app/src/main/java/com/beautifulquran/ui/theme/ornament/OrnamentGenerator.kt`;
 * both consume an identical mulberry32 stream. Keep the RNG call order in
 * sync when editing either.
 */

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

/** One translational unit cell of a periodic Hankin field. */
export interface FieldSpec {
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
 * Corner-seal ring radius in the unit box. Renderers terminate the border
 * band's runs against this ring (rim = radius × seal diameter), so it is
 * part of the seal ↔ border contract on both platforms.
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
function generateMedallion(rng: Mulberry32): RosetteSpec {
  const u = rng.next()
  const fold = u < 0.3 ? 8 : u < 0.55 ? 10 : u < 0.85 ? 12 : 16
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
  const k = ks[rng.int(ks.length)]!
  const rs = r2 - rng.range(0.03, 0.052)
  strokes.push(...starPolygons(fold, k, rs, ROT0, 'rule'))

  // Dense folds carry their inner zones as hairlines.
  const innerWeight: StrokeWeight = fold >= 12 ? 'hairline' : 'rule'

  // Zone 3 — the secondary motif, a fixed fraction of the star so the
  // annulus between them stays legible at every fold.
  const rMid = rs * rng.range(0.66, 0.74)
  const variant = rng.int(3)
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
  const coreRecipe = rng.int(3)
  if (coreRecipe === 0) {
    const i3 = rng.int(ks.length)
    strokes.push(...starPolygons(fold, ks[i3]!, rCore, ROT0 + Math.PI / fold, 'hairline'))
  } else if (coreRecipe === 1) {
    strokes.push(corollaStroke(fold, rCore * rng.range(0.46, 0.56), rCore, ROT0, 'hairline'))
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
 * (never 6-fold: that would force the hexagram) inside a mandatory ring,
 * wrapped in a four-petal ogee bezel whose tips lie on the compass axes.
 * Two of those tips aim straight down the border band's two runs at each
 * corner — the band's channel tapers onto them — so seal and border are
 * one piece of geometry, not a stamp over a strip.
 */
function generateSeal(rng: Mulberry32, fold: number): RosetteSpec {
  let m = fold >= 12 ? fold / 2 : fold
  if (m === 6) m = 8
  const ks = allowedStarKs(m)
  const k = ks[rng.int(ks.length)]!
  const starR = rng.range(0.32, 0.37)
  const tip = rng.range(0.58, 0.66)

  const strokes: OrnamentStroke[] = []
  strokes.push(circleStroke(SEAL_RING_RADIUS, m * 12, 'hairline'))
  // Bezel cusps on the diagonals, on the ring itself; tips on the axes.
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
 * The border band — one of five frieze grammars found on tooled mushaf
 * bindings: a zigzag lattice with pearls in its diamonds, an interlaced
 * two-strand cable with pearl eyes (guilloche), a Hankin star-and-cross
 * strip, a nested lozenge chain, or a khatam chain — small eight-fold
 * stars linked by diamonds. Historical bindings run exactly these between
 * their gilt fillets ("bordered by geometric braiding"). Every recipe is
 * bounded by rule-weight edge rails so the band reads as one tooled
 * channel; renderers taper the channel's mouth onto the corner seals'
 * petal tips.
 */
function generateBorder(rng: Mulberry32): BorderSpec {
  const strokes: OrnamentStroke[] = []
  const dots: OrnamentDot[] = []
  let period: number
  const recipe = rng.int(5)
  if (recipe === 0) {
    // Zigzag lattice — two zigzags half a period out of phase make an
    // X-crossing diamond trellis; a pearl rests in each diamond.
    period = rng.range(1.2, 1.7)
    const zig = (a: number, b: number): OrnamentStroke => ({
      points: [
        { x: 0, y: a },
        { x: period / 2, y: b },
        { x: period, y: a },
      ],
      closed: false,
      weight: 'hairline',
      birth: 0,
      span: 1,
    })
    strokes.push(zig(0.86, 0.14))
    strokes.push(zig(0.14, 0.86))
    const pearl = rng.range(0.05, 0.075)
    dots.push({ x: period / 4, y: 0.5, radius: pearl, birth: 0 })
    dots.push({ x: (3 * period) / 4, y: 0.5, radius: pearl, birth: 0 })
  } else if (recipe === 1) {
    // Cable: two phase-opposed strands, a pearl in each eye.
    period = rng.range(1.8, 2.6)
    const amp = rng.range(0.26, 0.34)
    const samples = 24
    const strand = (sign: number): OrnamentStroke => {
      const points: OrnamentPoint[] = []
      for (let i = 0; i <= samples; i++) {
        const x = (i * period) / samples
        points.push({ x, y: 0.5 + sign * amp * Math.cos((TAU * x) / period) })
      }
      return { points, closed: false, weight: 'hairline', birth: 0, span: 1 }
    }
    strokes.push(strand(1))
    strokes.push(strand(-1))
    const eye = rng.range(0.06, 0.09)
    dots.push({ x: 0, y: 0.5, radius: eye, birth: 0 })
    dots.push({ x: period / 2, y: 0.5, radius: eye, birth: 0 })
  } else if (recipe === 2) {
    // Star-and-cross strip: Hankin's method on a row of squares.
    period = 1
    const deg = rng.chance(0.5) ? rng.range(28, 42) : rng.range(50, 68)
    strokes.push(
      ...hankinStrokes(
        [
          { x: 0, y: 0 },
          { x: 1, y: 0 },
          { x: 1, y: 1 },
          { x: 0, y: 1 },
        ],
        (deg * Math.PI) / 180,
      ),
    )
  } else if (recipe === 3) {
    // Nested lozenge chain — a diamond in a diamond, tip-to-tip, a pearl
    // at each heart.
    period = rng.range(1.7, 2.3)
    const diamond = (halfW: number, halfH: number): OrnamentStroke => ({
      points: [
        { x: period / 2 - halfW, y: 0.5 },
        { x: period / 2, y: 0.5 - halfH },
        { x: period / 2 + halfW, y: 0.5 },
        { x: period / 2, y: 0.5 + halfH },
      ],
      closed: true,
      weight: 'hairline',
      birth: 0,
      span: 1,
    })
    strokes.push(diamond(period / 2, 0.38))
    strokes.push(diamond(period / 4, 0.19))
    dots.push({ x: period / 2, y: 0.5, radius: rng.range(0.05, 0.08), birth: 0 })
  } else {
    // Khatam chain — small eight-fold stars (two overlapped squares)
    // linked by diamonds at the period boundaries.
    period = rng.range(1.35, 1.75)
    const r = rng.range(0.26, 0.31)
    const a = r * 0.7071
    strokes.push({
      points: [
        { x: period / 2 - a, y: 0.5 - a },
        { x: period / 2 + a, y: 0.5 - a },
        { x: period / 2 + a, y: 0.5 + a },
        { x: period / 2 - a, y: 0.5 + a },
      ],
      closed: true,
      weight: 'hairline',
      birth: 0,
      span: 1,
    })
    strokes.push({
      points: [
        { x: period / 2 - r, y: 0.5 },
        { x: period / 2, y: 0.5 - r },
        { x: period / 2 + r, y: 0.5 },
        { x: period / 2, y: 0.5 + r },
      ],
      closed: true,
      weight: 'hairline',
      birth: 0,
      span: 1,
    })
    // Link diamond straddling the period boundary; when tiled, the half
    // past x = 0 is completed by the neighbouring tile.
    const cw = rng.range(0.1, 0.14)
    strokes.push({
      points: [
        { x: -cw, y: 0.5 },
        { x: 0, y: 0.5 - cw * 0.85 },
        { x: cw, y: 0.5 },
        { x: 0, y: 0.5 + cw * 0.85 },
      ],
      closed: true,
      weight: 'hairline',
      birth: 0,
      span: 1,
    })
    dots.push({ x: period / 2, y: 0.5, radius: 0.05, birth: 0 })
  }
  // Rule-weight edge rails bound every recipe into one tooled channel;
  // renderers taper the channel's mouth onto the corner seals' petal tips.
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

// ── Hankin fields ────────────────────────────────────────────────────────

interface Ray {
  ox: number
  oy: number
  dx: number
  dy: number
}

/**
 * Hankin's method on one convex polygon: two rays leave each edge midpoint
 * at ±θ, aimed inward; each ray is kept up to its nearest crossing with
 * another ray. Shared edge midpoints make the pattern continuous across
 * the whole tiling.
 */
function hankinStrokes(vertices: OrnamentPoint[], theta: number): OrnamentStroke[] {
  const n = vertices.length
  let cx = 0
  let cy = 0
  for (const v of vertices) {
    cx += v.x
    cy += v.y
  }
  cx /= n
  cy /= n

  const rays: Ray[] = []
  for (let i = 0; i < n; i++) {
    const a = vertices[i]!
    const b = vertices[(i + 1) % n]!
    const mx = (a.x + b.x) / 2
    const my = (a.y + b.y) / 2
    let dx = b.x - a.x
    let dy = b.y - a.y
    const len = Math.sqrt(dx * dx + dy * dy)
    dx /= len
    dy /= len
    const c = Math.cos(theta)
    const s = Math.sin(theta)
    // rot(d, +θ) and rot(−d, −θ), mirrored if facing away from the centroid.
    let ax = dx * c - dy * s
    let ay = dx * s + dy * c
    let bx = -dx * c - dy * s
    let by = dx * s - dy * c
    if ((ax + bx) * (cx - mx) + (ay + by) * (cy - my) < 0) {
      ax = dx * c + dy * s
      ay = -dx * s + dy * c
      bx = -dx * c + dy * s
      by = -dx * s - dy * c
    }
    rays.push({ ox: mx, oy: my, dx: ax, dy: ay })
    rays.push({ ox: mx, oy: my, dx: bx, dy: by })
  }

  const strokes: OrnamentStroke[] = []
  for (const r of rays) {
    let bestT = Number.MAX_VALUE
    for (const o of rays) {
      if (o === r) continue
      const denom = r.dx * o.dy - r.dy * o.dx
      if (Math.abs(denom) < 1e-9) continue
      const qpx = o.ox - r.ox
      const qpy = o.oy - r.oy
      const t = (qpx * o.dy - qpy * o.dx) / denom
      const s = (qpx * r.dy - qpy * r.dx) / denom
      if (t > 1e-6 && s > 1e-6 && t < bestT) {
        const px = r.ox + t * r.dx
        const py = r.oy + t * r.dy
        if (pointInConvex(vertices, px, py)) bestT = t
      }
    }
    if (bestT === Number.MAX_VALUE) continue
    strokes.push({
      points: [
        { x: r.ox, y: r.oy },
        { x: r.ox + bestT * r.dx, y: r.oy + bestT * r.dy },
      ],
      closed: false,
      weight: 'hairline',
      birth: 0,
      span: 1,
    })
  }
  return strokes
}

/** Inside test tolerant of boundary points (midpoints sit on edges). */
function pointInConvex(vertices: OrnamentPoint[], px: number, py: number): boolean {
  const n = vertices.length
  let sign = 0
  for (let i = 0; i < n; i++) {
    const a = vertices[i]!
    const b = vertices[(i + 1) % n]!
    const cross = (b.x - a.x) * (py - a.y) - (b.y - a.y) * (px - a.x)
    if (Math.abs(cross) < 1e-9) continue
    const s = cross > 0 ? 1 : -1
    if (sign === 0) sign = s
    else if (s !== sign) return false
  }
  return true
}

// ── Star-and-cross field ───────────────────────────────────────────────────
//
// The all-over background of illuminated pages and tooled bindings: a
// continuous star-and-cross tessellation, drawn as complete interlocking
// motifs (not ray fragments, which read as scattered marks at whisper ink).
// Eight-pointed khatam stars sit at the centre of a unit cell; their four
// cardinal points reach exactly to the cell-edge midpoints, so each star
// kisses its orthogonal neighbours tip-to-tip. Their diagonal points are
// tied together by a small knot at each cell corner — one knot per lattice
// point when the cell tiles — closing the weave into one unbroken net.

/** Star outer radius: cardinal points land on the cell-edge midpoints. */
const FIELD_STAR_R = 0.5

/** Diagonal half-extent of the star's points (R/√2). */
const FIELD_S = FIELD_STAR_R / Math.sqrt(2)

/** Knot half-extent at a corner: reaches the four nearest diagonal tips. */
const FIELD_G = FIELD_STAR_R - FIELD_S

function fieldStroke(points: OrnamentPoint[]): OrnamentStroke {
  return { points, closed: true, weight: 'hairline', birth: 0, span: 1 }
}

/**
 * Eight-pointed khatam star at (cx, cy): a diamond through the cardinal
 * points and an axis-aligned square through the diagonal points, both
 * reaching FIELD_STAR_R — two overlapped squares, the classic khatam.
 */
function khatamStar(cx: number, cy: number): OrnamentStroke[] {
  const r = FIELD_STAR_R
  const s = FIELD_S
  return [
    fieldStroke([
      { x: cx + r, y: cy },
      { x: cx, y: cy + r },
      { x: cx - r, y: cy },
      { x: cx, y: cy - r },
    ]),
    fieldStroke([
      { x: cx + s, y: cy + s },
      { x: cx - s, y: cy + s },
      { x: cx - s, y: cy - s },
      { x: cx + s, y: cy - s },
    ]),
  ]
}

/**
 * The {8/3} octagram at (cx, cy): one interlaced closed polyline through the
 * same eight points as the khatam, for a more woven star.
 */
function octagramStar(cx: number, cy: number): OrnamentStroke {
  const r = FIELD_STAR_R
  const points: OrnamentPoint[] = []
  let k = 0
  for (let i = 0; i < 8; i++) {
    const a = (k * Math.PI) / 4
    points.push({ x: cx + r * Math.cos(a), y: cy + r * Math.sin(a) })
    k = (k + 3) % 8
  }
  return fieldStroke(points)
}

/** Square knot at (cx, cy): corners on the four nearest star diagonal tips. */
function squareKnot(cx: number, cy: number): OrnamentStroke {
  const g = FIELD_G
  return fieldStroke([
    { x: cx + g, y: cy + g },
    { x: cx - g, y: cy + g },
    { x: cx - g, y: cy - g },
    { x: cx + g, y: cy - g },
  ])
}

/** Octagon knot at (cx, cy): a regular octagon through the same diagonal tips. */
function octagonKnot(cx: number, cy: number): OrnamentStroke {
  const rr = FIELD_G * Math.sqrt(2)
  const points: OrnamentPoint[] = []
  for (let i = 0; i < 8; i++) {
    const a = (i * Math.PI) / 4
    points.push({ x: cx + rr * Math.cos(a), y: cy + rr * Math.sin(a) })
  }
  return fieldStroke(points)
}

/** A small diamond centre-mark inside the star. */
function centreMark(cx: number, cy: number, h: number): OrnamentStroke {
  return fieldStroke([
    { x: cx + h, y: cy },
    { x: cx, y: cy + h },
    { x: cx - h, y: cy },
    { x: cx, y: cy - h },
  ])
}

/**
 * A star-and-cross field: an eight-pointed star (khatam or {8/3} octagram)
 * at the cell centre, a knot (square or octagon) at the cell corner, and an
 * optional centre-mark. One unit cell tiles the plane into the continuous
 * pattern; density comes from the rendered cell width. Everything is in the
 * 4/8-fold khatam family, so nothing can read as a hexagram.
 */
function generateField(rng: Mulberry32): FieldSpec {
  const octagram = rng.chance(0.5)
  const useOctagonKnot = rng.chance(0.5)
  const withCentre = rng.chance(0.5)
  const cellWidthDp = rng.range(48, 74)

  const strokes: OrnamentStroke[] = []
  if (octagram) strokes.push(octagramStar(0.5, 0.5))
  else strokes.push(...khatamStar(0.5, 0.5))
  strokes.push(useOctagonKnot ? octagonKnot(0, 0) : squareKnot(0, 0))
  if (withCentre) strokes.push(centreMark(0.5, 0.5, 0.08))

  return { cellW: 1, cellH: 1, cellWidthDp, strokes }
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
 * Seed for a chapter's surah-header rosette. Ayah count is the dominant
 * term — chapters of similar length grow kin-looking rosettes, so length
 * reads as the ornament's "fingerprint" — folded with the chapter number
 * (always < 114) so it acts as a low digit the multiply-by-114 term never
 * touches: `seed % 114` always recovers the chapter number, so all 114
 * chapters get distinct rosettes even though only 77 of them have a
 * distinct ayah count (37 chapters share a count with another chapter).
 */
export function chapterOrnamentSeed(chapterNumber: number, ayahCount: number): number {
  return ayahCount * 114 + chapterNumber
}

/**
 * Grow a chapter's rosette and backing field — no corner seal or border,
 * which the header has no use for — from a seed. Same star-polygon and
 * Hankin-field vocabulary and RNG rules (never a hexagram) as the medallion
 * and field inside a full cover ornament.
 */
export function generateChapterOrnament(seed: number): ChapterOrnament {
  const rng = new Mulberry32(seed)
  const rosette = generateMedallion(rng)
  const field = generateField(rng)
  return { seed, rosette, field }
}
