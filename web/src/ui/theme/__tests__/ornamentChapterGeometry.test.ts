import { describe, expect, it } from 'vitest'
import {
  chapterOrnamentSeed,
  generateChapterOrnament,
  type OrnamentPoint,
} from '../ornamentGenerator'

// Split from ornamentGenerator.test.ts so this sweep runs on its own worker.
describe('ornamentGenerator', () => {
  it('all chapters have distinct coarse field and medallion shapes with safe geometry', () => {
    const crosses = (a: OrnamentPoint, b: OrnamentPoint, c: OrnamentPoint, d: OrnamentPoint) => {
      const dx = b.x - a.x, dy = b.y - a.y, ex = d.x - c.x, ey = d.y - c.y, den = dx * ey - dy * ex
      if (Math.abs(den) < 1e-10) return false
      const t = ((c.x - a.x) * ey - (c.y - a.y) * ex) / den
      const u = ((c.x - a.x) * dy - (c.y - a.y) * dx) / den
      return t > 1e-6 && t < 1 - 1e-6 && u > 1e-6 && u < 1 - 1e-6
    }
    const shape = (strokes: ReturnType<typeof generateChapterOrnament>['field']['strokes']) =>
      JSON.stringify(strokes.map(s => [s.closed, s.points.map(p => [Math.round(p.x * 16), Math.round(p.y * 16)])]))
    const verseCounts = [7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110, 98, 135, 112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88, 75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78, 96, 29, 22, 24, 13, 14, 11, 11, 18, 12, 12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42, 29, 19, 36, 25, 22, 17, 19, 26, 30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6]
    for (const sample of [3, 11, 286, 0]) {
      const fields = new Set<string>(), rosettes = new Set<string>()
      for (let chapter = 1; chapter <= 114; chapter++) {
        const o = generateChapterOrnament(chapterOrnamentSeed(chapter, sample || verseCounts[chapter - 1]!)), f = o.field
        fields.add(shape(f.strokes)); rosettes.add(shape(o.rosette.strokes))
        for (const s of [...f.strokes, ...o.rosette.strokes]) {
          expect(s.closed && s.points.length === 3).toBe(false)
          if (s.closed && s.points.length === 5) {
            const a = Math.atan2(s.points[0]!.y - .5, s.points[0]!.x - .5)
            const b = Math.atan2(s.points[1]!.y - .5, s.points[1]!.x - .5)
            const delta = ((b - a) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI)
            expect(Math.abs(delta - 4 * Math.PI / 5)).toBeGreaterThan(.05)
            expect(Math.abs(delta - 6 * Math.PI / 5)).toBeGreaterThan(.05)
          }
        }
        const points = f.strokes.flatMap(s => s.points)
        expect(points.every(p => p.x >= -1e-9 && p.x <= 1 + 1e-9 && p.y >= -1e-9 && p.y <= 1 + 1e-9)).toBe(true)
        const key = (x: number, y: number) => `${Math.round(x * 1e9)}/${Math.round(y * 1e9)}`
        const balanced = new Set(points.map(p => key(p.x, p.y)))
        expect(points.every(p => balanced.has(key(1 - p.y, p.x)) && balanced.has(key(1 - p.x, p.y)))).toBe(true)
        const frames = f.strokes.slice(0, 2).map(s => s.points)
        let clearance = Infinity
        for (const s of f.strokes.slice(2)) {
          for (const p of s.points) for (const frame of frames) for (let j = 0; j < frame.length; j++) {
            const a = frame[j]!, b = frame[(j + 1) % frame.length]!, dx = b.x - a.x, dy = b.y - a.y
            const t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)))
            clearance = Math.min(clearance, Math.hypot(p.x - a.x - t * dx, p.y - a.y - t * dy))
          }
        }
        expect(clearance, `chapter ${chapter}: frame clearance`).toBeGreaterThan(.02)
        const edges = f.strokes.flatMap(s => (s.closed ? [...s.points, s.points[0]!] : s.points).slice(1).map((p, i) => [s.points[i]!, p] as const))
        let inkLength = 0, crossing = false
        for (let i = 0; i < edges.length; i++) {
          const [a, b] = edges[i]!
          for (let j = i + 1; j < edges.length; j++) {
            const [c, d] = edges[j]!
            if (Math.max(a.x, b.x) < Math.min(c.x, d.x) || Math.max(c.x, d.x) < Math.min(a.x, b.x) ||
                Math.max(a.y, b.y) < Math.min(c.y, d.y) || Math.max(c.y, d.y) < Math.min(a.y, b.y)) continue
            if (crosses(a, b, c, d)) crossing = true
          }
        }
        for (const s of f.strokes) {
          const p = s.closed ? [...s.points, s.points[0]!] : s.points
          inkLength += p.slice(1).reduce((sum, q, i) => sum + Math.hypot(q.x - p[i]!.x, q.y - p[i]!.y), 0) * (s.weight === 'rule' ? 1 : .55)
        }
        expect(crossing, `chapter ${chapter}: accidental crossing`).toBe(false)
        expect(inkLength / f.cellWidthDp).toBeLessThanOrEqual(11.5 / 48 + 1e-9)
        expect(f.cellWidthDp).toBeLessThanOrEqual(58)
      }
      expect(fields.size).toBe(114)
      expect(rosettes.size).toBe(114)
    }
  })
})
