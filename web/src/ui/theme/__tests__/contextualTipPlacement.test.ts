import { describe, expect, it } from 'vitest'
import {
  coercePoint,
  tipActionCenter,
  tipBodyCenter,
} from '../contextualTipPlacement'

const surface = { width: 400, height: 800 }

describe('contextualTipPlacement', () => {
  it('places the body on the rightward ray from a left-edge spotlight', () => {
    const spotlight = { x: 14, y: 400 }
    const body = tipBodyCenter({ bodyAngleDegrees: 0 }, spotlight, surface)
    expect(body.x).toBeGreaterThan(spotlight.x)
    expect(body.y).toBeCloseTo(400, 5)
    // 0.78 of the run to the right edge.
    expect(body.x).toBeCloseTo(14 + (400 - 14) * 0.78, 5)
  })

  it('places the body on the leftward ray from a right-edge spotlight', () => {
    const spotlight = { x: 386, y: 400 }
    const body = tipBodyCenter({ bodyAngleDegrees: 180 }, spotlight, surface)
    expect(body.x).toBeLessThan(spotlight.x)
    expect(body.y).toBeCloseTo(400, 5)
  })

  it('keeps the default action farther along the same ray than the body', () => {
    const spotlight = { x: 14, y: 400 }
    const body = tipBodyCenter({ bodyAngleDegrees: 0 }, spotlight, surface)
    const action = tipActionCenter({ bodyAngleDegrees: 0 }, spotlight, surface)
    expect(action.x).toBeGreaterThan(body.x)
  })

  it('coerces points into the surface', () => {
    expect(coercePoint({ x: -10, y: 900 }, surface)).toEqual({
      x: 0,
      y: 800,
    })
  })
})
