import { describe, expect, it } from 'vitest'
import {
  FIELD_PATTERNS,
  fittedFieldCellWidth,
  chapterOrnamentSeed,
  generateChapterOrnament,
  generateCoverOrnament,
  Mulberry32,
  SEAL_RING_RADIUS,
  type OrnamentPoint,
} from '../ornamentGenerator'

describe('ornamentGenerator', () => {
  it('fits complete even repeats on narrow, phone and wide fields', () => {
    for (const [width, count] of [[20, 2], [320, 6], [390, 8], [450, 10], [768, 16]]) {
      expect(fittedFieldCellWidth(width!, 50)).toBeCloseTo(width! / count!, 9)
    }
    expect(fittedFieldCellWidth(0, 50)).toBe(50)
  })

  it('prng matches the reference mulberry32 stream (Android parity)', () => {
    // Same known-answer values asserted by OrnamentGeneratorTest.kt — the
    // cross-platform contract that keeps both covers drawing from one stream.
    const expected: Array<[number, number[]]> = [
      [1, [2693262067, 11749833, 2265367787, 4213581821]],
      [123456789, [1107202814, 4169434471, 3372958138, 885470128]],
      [-7, [1860010037, 1397564179, 2337619704, 2062400319]],
    ]
    for (const [seed, values] of expected) {
      const rng = new Mulberry32(seed)
      for (const v of values) expect(rng.nextUInt()).toBe(v)
    }
  })

  it('grows the same ornament from the same seed', () => {
    for (const seed of [1, 42, -913, 2000000011]) {
      expect(generateCoverOrnament(seed)).toEqual(generateCoverOrnament(seed))
    }
  })

  it('grows different ornaments from different seeds', () => {
    const a = generateCoverOrnament(1)
    let anyDiffer = false
    for (let seed = 2; seed <= 12; seed++) {
      try {
        expect(generateCoverOrnament(seed)).not.toEqual(a)
        anyDiffer = true
      } catch {
        /* one collision is tolerable; all colliding is not */
      }
    }
    expect(anyDiffer).toBe(true)
  })

  it('generates a sane ornament for a wide seed sample', () => {
    // Plain checks collected into a violation list (per-point expect() calls
    // are far too slow at this volume); a single assert reports the first few.
    const violations: string[] = []
    const check = (ok: boolean, label: string) => {
      if (!ok && violations.length < 5) violations.push(label)
    }
    for (let seed = 0; seed < 300; seed++) {
      const o = generateCoverOrnament(seed * 7919 + seed)
      const at = `seed ${seed * 7919 + seed}`
      check(o.medallion.strokes.length >= 4, `${at}: medallion strokes`)
      check(o.medallion.dots.length > 0, `${at}: medallion dots`)
      check(o.cornerSeal.strokes.length > 0, `${at}: seal strokes`)
      check(o.border.strokes.length > 0, `${at}: border strokes`)
      check(o.border.period > 0.5, `${at}: border period`)
      check(o.field.strokes.length >= 2, `${at}: field strokes`)
      check(o.field.cellW > 0 && o.field.cellH > 0, `${at}: field cell`)

      // The medallion has no bezel; the seal's petal tips must aim down
      // the band axes just past the ring, and its bezel may reach past
      // the unit box by at most (tip − 0.5).
      check(o.medallion.tipRadius === 0, `${at}: medallion tipRadius`)
      check(
        o.cornerSeal.tipRadius > SEAL_RING_RADIUS && o.cornerSeal.tipRadius <= 0.7,
        `${at}: seal tipRadius ${o.cornerSeal.tipRadius}`,
      )
      for (const s of o.cornerSeal.strokes) {
        if (!s.closed) continue
        const ring = s.points.every((p) => {
          const r = Math.hypot(p.x - 0.5, p.y - 0.5)
          return Math.abs(r - SEAL_RING_RADIUS) < 0.02
        })
        check(!ring, `${at}: seal enclosing ring`)
      }
      for (const s of o.medallion.strokes) {
        check(s.points.length >= 2, `${at}: short stroke`)
        check(s.birth >= 0 && s.birth + s.span <= 1.0001, `${at}: build window`)
        for (const p of s.points) {
          check(
            p.x >= -0.001 && p.x <= 1.001 && p.y >= -0.001 && p.y <= 1.001,
            `${at}: medallion point outside unit box (${p.x}, ${p.y})`,
          )
        }
      }
      for (const s of o.cornerSeal.strokes) {
        check(s.points.length >= 2, `${at}: short stroke`)
        check(s.birth >= 0 && s.birth + s.span <= 1.0001, `${at}: build window`)
        for (const p of s.points) {
          check(
            p.x >= -0.2 && p.x <= 1.2 && p.y >= -0.2 && p.y <= 1.2,
            `${at}: seal point too far out (${p.x}, ${p.y})`,
          )
        }
      }
      // The khatam chain's link diamond straddles the period boundary by
      // design (its far half is completed by the neighbouring tile when
      // the band repeats), so the x margin allows for it; y always stays
      // inside the band.
      for (const s of o.border.strokes) {
        for (const p of s.points) {
          check(
            p.x >= -0.2 && p.x <= o.border.period + 0.2 && p.y >= -0.001 && p.y <= 1.001,
            `${at}: border point outside band (${p.x}, ${p.y})`,
          )
        }
      }
    }
    expect(violations).toEqual([])
  })

  it('medallion has full n-fold rotational symmetry', () => {
    for (const seed of [3, 17, 99, 1234, -55, 777777]) {
      const m = generateCoverOrnament(seed).medallion
      const angle = (2 * Math.PI) / m.fold
      const pts: OrnamentPoint[] = m.strokes.flatMap((s) => s.points)
      for (const p of pts) {
        const rx = 0.5 + (p.x - 0.5) * Math.cos(angle) - (p.y - 0.5) * Math.sin(angle)
        const ry = 0.5 + (p.x - 0.5) * Math.sin(angle) + (p.y - 0.5) * Math.cos(angle)
        const hit = pts.some((q) => Math.abs(q.x - rx) < 1e-6 && Math.abs(q.y - ry) < 1e-6)
        expect(hit, `seed ${seed} fold ${m.fold}: rotated point unmatched`).toBe(true)
      }
    }
  })

  it('medallion zones never graze, and never leave a bare core', () => {
    // Mirrors the Android suite. Two motifs a hair apart read as a
    // misprint, and a small motif floating in a bare field is the ugliest
    // thing this generator can produce — both are structural, so both are
    // pinned rather than left to the eye.
    for (let seed = 0; seed < 400; seed++) {
      const m = generateCoverOrnament(seed * 104729 + 13).medallion
      const at = `seed ${seed}`
      const radii = [
        ...new Set(
          m.strokes.map((s) =>
            Math.round(Math.max(...s.points.map((p) => Math.hypot(p.x - 0.5, p.y - 0.5))) * 1e6),
          ),
        ),
      ]
        .map((r) => r / 1e6)
        .sort((a, b) => b - a)

      expect(radii[0]!).toBeCloseTo(0.485, 6)
      expect(radii[1]! - radii[2]!, `${at}: star grazes the rule`).toBeGreaterThanOrEqual(0.028)
      expect(radii.length, `${at}: too few zones`).toBeGreaterThanOrEqual(5)
      expect(radii[radii.length - 1]!, `${at}: hollow core`).toBeLessThanOrEqual(0.1)
      for (let i = 0; i + 1 < radii.length; i++) {
        const outer = radii[i]!
        const inner = radii[i + 1]!
        expect(outer - inner, `${at}: zones nearly coincide`).toBeGreaterThanOrEqual(0.02)
        // Below the two rules, every zone is sized from its parent.
        if (i >= 1) expect(inner / outer, `${at}: speck in a bare zone`).toBeGreaterThanOrEqual(0.32)
      }
    }
  })

  it('star-and-cross field kisses at every cell-edge midpoint', () => {
    // The star's four cardinal points must land exactly on the cell-edge
    // midpoints, so when the cell tiles each star meets its orthogonal
    // neighbour tip-to-tip — the seam-free continuity of the weave.
    for (const seed of [5, 8, 21, 100, 4242]) {
      const f = generateCoverOrnament(seed).field
      const verts = f.strokes.flatMap((s) => s.points)
      const midpoints = [
        { x: f.cellW / 2, y: 0 },
        { x: f.cellW / 2, y: f.cellH },
        { x: 0, y: f.cellH / 2 },
        { x: f.cellW, y: f.cellH / 2 },
      ]
      for (const m of midpoints) {
        const hit = verts.some((v) => Math.abs(v.x - m.x) < 1e-9 && Math.abs(v.y - m.y) < 1e-9)
        expect(hit, `seed ${seed}: no star point at edge midpoint (${m.x}, ${m.y})`).toBe(true)
      }
    }
  })

  it('never draws a hexagram — no triangles, no 6-fold stars, anywhere', () => {
    const violations: string[] = []
    for (let seed = 0; seed < 400; seed++) {
      const o = generateCoverOrnament(seed * 104729 + 13)
      if (o.cornerSeal.fold === 6) violations.push(`seed ${seed}: 6-fold seal`)
      const everyStroke = [
        ...o.medallion.strokes,
        ...o.cornerSeal.strokes,
        ...o.border.strokes,
        ...o.field.strokes,
      ]
      for (const s of everyStroke) {
        if (s.closed && s.points.length === 3) violations.push(`seed ${seed}: triangle`)
      }
    }
    expect(violations).toEqual([])
  })

  it('never draws pentagrams — no {5/2} compounds stacked into occult seals', () => {
    // A pentagram ({5/2}) is a closed 5-gon whose consecutive vertices skip
    // one angular neighbour (~144° on the circle). Two of those interlaced
    // is the pentacle compound ({10/4}); a convex pentagon (~72°) is fine.
    const violations: string[] = []
    const pentagramStep = (2 * Math.PI * 2) / 5
    for (let seed = 0; seed < 400; seed++) {
      const o = generateCoverOrnament(seed * 104729 + 13)
      const everyStroke = [
        ...o.medallion.strokes,
        ...o.cornerSeal.strokes,
        ...o.border.strokes,
        ...o.field.strokes,
      ]
      for (const s of everyStroke) {
        if (!s.closed || s.points.length !== 5) continue
        const p0 = s.points[0]!
        const p1 = s.points[1]!
        const a0 = Math.atan2(p0.y - 0.5, p0.x - 0.5)
        const a1 = Math.atan2(p1.y - 0.5, p1.x - 0.5)
        let d = Math.abs(a1 - a0)
        if (d > Math.PI) d = 2 * Math.PI - d
        if (Math.abs(d - pentagramStep) < 0.05) {
          violations.push(`seed ${seed}: pentagram 5-gon`)
        }
      }
    }
    expect(violations).toEqual([])
  })

  it('a seed sample uses every fold, varied filigree geometry, and border', () => {
    const folds = new Set<number>()
    const fieldStyles = new Set<string>()
    const coverPatterns = new Set<string>(), chapterPatterns = new Set<string>(), frames = new Set<number>()
    const borders = new Set<string>()
    for (let seed = 0; seed < 200; seed++) {
      const o = generateCoverOrnament(seed)
      folds.add(o.medallion.fold)
      fieldStyles.add(JSON.stringify(o.field.strokes[1]!.points))
      coverPatterns.add(o.field.pattern)
      chapterPatterns.add(generateChapterOrnament(seed).field.pattern)
      frames.add(o.field.strokes[0]!.points.length)
      borders.add(`${o.border.strokes.length}/${o.border.dots.length}`)
    }
    expect([...folds].sort((a, b) => a - b)).toEqual([8, 10, 12, 16])
    expect(fieldStyles.size).toBeGreaterThanOrEqual(3)
    expect([...coverPatterns].sort()).toEqual([...FIELD_PATTERNS].sort())
    expect([...chapterPatterns].sort()).toEqual([...FIELD_PATTERNS].sort())
    expect([...frames].sort((a, b) => a - b)).toEqual([4, 8, 16])
    expect(borders.size).toBeGreaterThanOrEqual(3)
  })

  it('geometric fields keep filigree contained, balanced and free of crossings', () => {
    const crosses = (a: OrnamentPoint, b: OrnamentPoint, c: OrnamentPoint, d: OrnamentPoint) => {
      if (Math.max(a.x, b.x) < Math.min(c.x, d.x) || Math.max(c.x, d.x) < Math.min(a.x, b.x) ||
        Math.max(a.y, b.y) < Math.min(c.y, d.y) || Math.max(c.y, d.y) < Math.min(a.y, b.y)) return false
      const dx = b.x - a.x, dy = b.y - a.y, ex = d.x - c.x, ey = d.y - c.y
      const den = dx * ey - dy * ex
      if (Math.abs(den) < 1e-10) return false
      const t = ((c.x - a.x) * ey - (c.y - a.y) * ex) / den
      const u = ((c.x - a.x) * dy - (c.y - a.y) * dx) / den
      return t > 1e-6 && t < 1 - 1e-6 && u > 1e-6 && u < 1 - 1e-6
    }
    for (let seed = 0; seed < 400; seed++) {
      const f = generateCoverOrnament(seed * 104729 + 13).field
      const family = FIELD_PATTERNS.indexOf(f.pattern)
      const density = [11.5, 11, 9][family]!
      const detailEnd = [27, 19, 15][family]!
      expect(f.strokes.length).toBe(family === 0 ? 31 : 23)
      expect(f.strokes.filter((s) => s.weight === 'rule').length).toBe(2)
      const pts = f.strokes.flatMap((s) => s.points)
      const edges = f.strokes.flatMap((s) => {
        const p = s.closed ? [...s.points, s.points[0]!] : s.points
        return p.slice(1).map((q, i) => [p[i]!, q] as const)
      })
      const inkLength = f.strokes.reduce((sum, s) => {
        const p = s.closed ? [...s.points, s.points[0]!] : s.points
        const length = p.slice(1).reduce((n, q, i) => n + Math.hypot(q.x - p[i]!.x, q.y - p[i]!.y), 0)
        return sum + length * (s.weight === 'rule' ? 1 : 0.55)
      }, 0)
      expect(inkLength / f.cellWidthDp).toBeGreaterThanOrEqual(density / 56 - 1e-9)
      expect(inkLength / f.cellWidthDp).toBeLessThanOrEqual(density / 48 + 1e-9)
      expect(pts.every((p) => p.x >= -1e-9 && p.x <= 1 + 1e-9 && p.y >= -1e-9 && p.y <= 1 + 1e-9)).toBe(true)
      const key = (x: number, y: number) => `${Math.round(x * 1e9)}/${Math.round(y * 1e9)}`
      const coordinates = new Set(pts.map((p) => key(p.x, p.y)))
      expect(pts.every((p) => coordinates.has(key(1 - p.y, p.x)))).toBe(true)
      expect(pts.every((p) => coordinates.has(key(1 - p.x, p.y)))).toBe(true)
      const frame = f.strokes[1]!.points
      const heart = f.strokes[2]!.points
      const distanceToEdge = (p: OrnamentPoint, a: OrnamentPoint, b: OrnamentPoint) => {
        const dx = b.x - a.x, dy = b.y - a.y
        const t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)))
        return Math.hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
      }
      let maxRadius = 0, clearance = Infinity
      f.strokes.slice(3, detailEnd).forEach((s, i) => {
        if (family === 1 || i % 3 !== 2) expect(heart.some((p) => Math.hypot(p.x - s.points[0]!.x, p.y - s.points[0]!.y) < 1e-9)).toBe(true)
        for (const p of s.points) {
          maxRadius = Math.max(maxRadius, Math.hypot(p.x - 0.5, p.y - 0.5))
          clearance = Math.min(clearance, ...frame.map((a, j) => distanceToEdge(p, a, frame[(j + 1) % frame.length]!)))
        }
      })
      expect(maxRadius).toBeLessThan(0.411)
      expect(clearance).toBeGreaterThan(0.02)
      let crossing = false
      for (let i = 0; i < edges.length; i++) for (let j = i + 1; j < edges.length; j++) {
        if (crosses(edges[i]![0], edges[i]![1], edges[j]![0], edges[j]![1])) crossing = true
      }
      expect(crossing, `seed ${seed}: accidental crossing`).toBe(false)
    }
  })

  it('field known answers match Android including the reported bare-grid seed', () => {
    const star = [16, 16, 16, ...Array.from({ length: 8 }, () => [31, 31, 21]).flat(), 21, 21, 21, 21]
    const garden = [8, 8, 16, ...Array(20).fill(21)]
    const lozenge = [4, 4, 16, ...Array.from({ length: 4 }, () => [31, 31, 21]).flat(),
      ...Array.from({ length: 4 }, () => [5, 21]).flat()]
    for (const [seed, pattern, signature, width] of [
      [1, 'lozenge rosettes', lozenge, 47.73055739685947],
      [8, 'star-and-cross', star, 51.29965216862327],
      [21, 'octagonal garden', garden, 50.4501521020009],
      [132614421, 'lozenge rosettes', lozenge, 50.61806257444628],
    ] as const) {
      const f = generateCoverOrnament(seed).field
      expect(f.pattern).toBe(pattern)
      expect(f.strokes.map((s) => s.points.length)).toEqual(signature)
      expect(f.cellWidthDp).toBeCloseTo(width, 9)
    }
  })

  it('chapter seed recovers the chapter number regardless of ayah count', () => {
    // Chapters are numbered 1..114 (not 0-indexed), so the recovered digit
    // is ((seed - 1) mod 114) + 1, not a plain mod 114.
    for (let chapter = 1; chapter <= 114; chapter++) {
      for (const ayahCount of [3, 6, 11, 88, 286]) {
        const seed = chapterOrnamentSeed(chapter, ayahCount)
        expect(((seed - 1) % 114) + 1).toBe(chapter)
      }
    }
  })

  it('chapter seed is unique across all 114 chapters even at a shared ayah count', () => {
    // A real duplicate: 62, 63, 93, 100, 101 all have exactly 11 ayahs.
    const seeds = new Set<number>()
    for (let chapter = 1; chapter <= 114; chapter++) seeds.add(chapterOrnamentSeed(chapter, 11))
    expect(seeds.size).toBe(114)
  })

  it('reproduces the same ornament for the same chapter and ayah count', () => {
    const seed = chapterOrnamentSeed(2, 286)
    expect(generateChapterOrnament(seed)).toEqual(generateChapterOrnament(seed))
  })

  it('renders different rosettes and fields for chapters that share an ayah count', () => {
    const elevenAyahChapters = [62, 63, 93, 100, 101]
    const ornaments = elevenAyahChapters.map((c) => generateChapterOrnament(chapterOrnamentSeed(c, 11)))
    const uniqueRosettes = new Set(ornaments.map((o) => JSON.stringify(o.rosette)))
    const uniqueFields = new Set(ornaments.map((o) => JSON.stringify(o.field)))
    expect(uniqueRosettes.size).toBe(elevenAyahChapters.length)
    expect(uniqueFields.size).toBe(elevenAyahChapters.length)
  })

  it('chapter ornament never draws a hexagram', () => {
    const violations: string[] = []
    for (let chapter = 1; chapter <= 114; chapter++) {
      for (const ayahCount of [3, 6, 11, 88, 286]) {
        const ornament = generateChapterOrnament(chapterOrnamentSeed(chapter, ayahCount))
        for (const s of [...ornament.rosette.strokes, ...ornament.field.strokes]) {
          if (s.closed && s.points.length === 3) {
            violations.push(`chapter ${chapter}, ${ayahCount} ayahs: triangle`)
          }
        }
      }
    }
    expect(violations).toEqual([])
  })
})
