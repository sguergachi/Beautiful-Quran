/**
 * Places teaching copy on a ray from the feature spotlight.
 *
 * Zero degrees points right; positive angles turn clockwise with screen Y —
 * Android `ContextualTipPlacement` parity.
 */

export type Point = { x: number; y: number }
export type Size = { width: number; height: number }

export type ContextualTipPlacement = {
  bodyAngleDegrees: number
  bodyDistanceFraction?: number
  actionDistanceFraction?: number
}

const EPSILON = 1e-4

function pointOnRay(
  spotlight: Point,
  surface: Size,
  angleDegrees: number,
  fraction: number,
): Point {
  const radians = (angleDegrees * Math.PI) / 180
  const dx = Math.cos(radians)
  const dy = Math.sin(radians)
  const xLimit =
    dx > EPSILON
      ? (surface.width - spotlight.x) / dx
      : dx < -EPSILON
        ? -spotlight.x / dx
        : Number.POSITIVE_INFINITY
  const yLimit =
    dy > EPSILON
      ? (surface.height - spotlight.y) / dy
      : dy < -EPSILON
        ? -spotlight.y / dy
        : Number.POSITIVE_INFINITY
  const distance =
    Math.max(0, Math.min(xLimit, yLimit)) * Math.min(1, Math.max(0, fraction))
  return {
    x: Math.min(surface.width, Math.max(0, spotlight.x + dx * distance)),
    y: Math.min(surface.height, Math.max(0, spotlight.y + dy * distance)),
  }
}

export function tipBodyCenter(
  placement: ContextualTipPlacement,
  spotlight: Point,
  surface: Size,
): Point {
  return pointOnRay(
    spotlight,
    surface,
    placement.bodyAngleDegrees,
    placement.bodyDistanceFraction ?? 0.78,
  )
}

export function tipActionCenter(
  placement: ContextualTipPlacement,
  spotlight: Point,
  surface: Size,
): Point {
  return pointOnRay(
    spotlight,
    surface,
    placement.bodyAngleDegrees,
    placement.actionDistanceFraction ?? 0.94,
  )
}

export function coercePoint(point: Point, surface: Size): Point {
  return {
    x: Math.min(surface.width, Math.max(0, point.x)),
    y: Math.min(surface.height, Math.max(0, point.y)),
  }
}

export function normalize(point: Point): Point {
  const length = Math.hypot(point.x, point.y)
  return length > EPSILON
    ? { x: point.x / length, y: point.y / length }
    : { x: 1, y: 0 }
}

export function midpoint(a: Point, b: Point): Point {
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
}

export function dot(a: Point, b: Point): number {
  return a.x * b.x + a.y * b.y
}
