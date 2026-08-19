import { describe, expect, it } from 'vitest'
import { coverMonogram } from '../coverMonogram'

describe('coverMonogram', () => {
  it('has no enclosing circle — every stroke is an open path', () => {
    expect(coverMonogram.strokes.length).toBeGreaterThan(0)
    for (const s of coverMonogram.strokes) {
      expect(s.closed).toBe(false)
    }
    expect(coverMonogram.dots).toEqual([])
  })

  it('is two offset open arcs, not concentric rings', () => {
    const arcs = coverMonogram.strokes.filter((s) => s.points.length >= 20)
    expect(arcs).toHaveLength(2)
    const c0 = arcCenter(arcs[0]!)
    const c1 = arcCenter(arcs[1]!)
    const offset = Math.hypot(c0.x - c1.x, c0.y - c1.y)
    expect(offset).toBeGreaterThan(0.02)
    for (const arc of arcs) {
      const sweep = arcSweep(arc)
      expect(sweep).toBeLessThan(2 * Math.PI - 0.4)
      expect(sweep).toBeGreaterThan(Math.PI)
    }
  })

  it('stays in the unit box', () => {
    for (const s of coverMonogram.strokes) {
      expect(s.points.length).toBeGreaterThanOrEqual(2)
      expect(s.birth).toBeGreaterThanOrEqual(0)
      expect(s.birth + s.span).toBeLessThanOrEqual(1.0001)
      for (const p of s.points) {
        expect(p.x).toBeGreaterThanOrEqual(-0.001)
        expect(p.x).toBeLessThanOrEqual(1.001)
        expect(p.y).toBeGreaterThanOrEqual(-0.001)
        expect(p.y).toBeLessThanOrEqual(1.001)
      }
    }
  })
})

function arcCenter(stroke: { points: Array<{ x: number; y: number }> }): { x: number; y: number } {
  const a = stroke.points[0]!
  const b = stroke.points[Math.floor(stroke.points.length / 2)]!
  const c = stroke.points[stroke.points.length - 1]!
  const d = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
  const a2 = a.x * a.x + a.y * a.y
  const b2 = b.x * b.x + b.y * b.y
  const c2 = c.x * c.x + c.y * c.y
  return {
    x: (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d,
    y: (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d,
  }
}

function arcSweep(stroke: { points: Array<{ x: number; y: number }> }): number {
  const c = arcCenter(stroke)
  const a0 = Math.atan2(stroke.points[0]!.y - c.y, stroke.points[0]!.x - c.x)
  const a1 = Math.atan2(
    stroke.points[stroke.points.length - 1]!.y - c.y,
    stroke.points[stroke.points.length - 1]!.x - c.x,
  )
  let d = a1 - a0
  if (d < 0) d += 2 * Math.PI
  return d
}
