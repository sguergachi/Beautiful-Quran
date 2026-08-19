/**
 * The cover's centre mark — mirrors `CoverMonogram.kt`.
 *
 * A crescent (two offset open arcs: the one correct internal/external pair)
 * and a geometric الله in its opening. No enclosing circle: a hoop around
 * the mark reads as a badge, and a third concentric ring fights the moon.
 */
import type { OrnamentPoint, OrnamentStroke, RosetteSpec, StrokeWeight } from './ornamentGenerator'

export const coverMonogram: RosetteSpec = buildCoverMonogram()

function buildCoverMonogram(): RosetteSpec {
  const strokes: OrnamentStroke[] = [
    arc(0.47, 0.5, 0.355, 0.58, 5.7, 64, 'hairline'),
    arc(0.505, 0.5, 0.248, 0.78, 5.5, 52, 'hairline'),
    polyline(
      [
        [0.62, 0.318],
        [0.62, 0.668],
      ],
      'rule',
    ),
    polyline(
      [
        [0.62, 0.43],
        [0.768, 0.498],
        [0.62, 0.668],
      ],
      'rule',
    ),
    polyline(
      [
        [0.572, 0.668],
        [0.668, 0.668],
      ],
      'rule',
    ),
    polyline(
      [
        [0.498, 0.348],
        [0.62, 0.448],
      ],
      'rule',
    ),
    polyline(
      [
        [0.468, 0.548],
        [0.62, 0.668],
      ],
      'rule',
    ),
    polyline(
      [
        [0.538, 0.308],
        [0.612, 0.378],
      ],
      'hairline',
    ),
  ]
  return { fold: 1, strokes: assignBirths(strokes), dots: [], tipRadius: 0 }
}

function polyline(pairs: Array<[number, number]>, weight: StrokeWeight): OrnamentStroke {
  return {
    points: pairs.map(([x, y]) => ({ x, y })),
    closed: false,
    weight,
    birth: 0,
    span: 1,
  }
}

function arc(
  cx: number,
  cy: number,
  r: number,
  start: number,
  end: number,
  segments: number,
  weight: StrokeWeight,
): OrnamentStroke {
  const points: OrnamentPoint[] = []
  for (let i = 0; i <= segments; i++) {
    const a = start + ((end - start) * i) / segments
    points.push({ x: cx + r * Math.cos(a), y: cy + r * Math.sin(a) })
  }
  return { points, closed: false, weight, birth: 0, span: 1 }
}

function assignBirths(strokes: OrnamentStroke[]): OrnamentStroke[] {
  const n = strokes.length
  return strokes.map((s, i) => {
    const birth = 0.06 + (0.68 * i) / n
    return { ...s, birth, span: Math.min(0.28, 0.97 - birth) }
  })
}
