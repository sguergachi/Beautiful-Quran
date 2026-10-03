import { describe, expect, it } from 'vitest'
import {
  FIELD_PATTERNS,
  generateCoverOrnament,
  type OrnamentPoint,
} from '../ornamentGenerator'

// Split from ornamentGenerator.test.ts so this sweep runs on its own worker.
describe('ornamentGenerator', () => {
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
      const family = FIELD_PATTERNS.findIndex(pattern => pattern === f.pattern)
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
})
