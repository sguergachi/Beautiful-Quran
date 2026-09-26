import {
  nuqtaDropPath,
  nuqtaDropRadii,
  nuqtaFingers,
  nuqtaInkStops,
  nuqtaPath,
  NUQTA_INK_STOP_COUNT,
  type NuqtaParams,
} from '../kit/nuqta'

/**
 * Ink laid as three thin coats — Android `InkCoats.kt`. A wide pale
 * under-coat, a body, and a small dense pool, each with its own fibres.
 * Drying fades the pool first and the under-coat last.
 */
export const INK_COAT_COUNT = 3

/** How far each coat reaches, as a share of the drop's full reach. */
export const INK_COAT_REACH = [1, 0.78, 0.58] as const

const INK_COAT_ALPHA = 0.54
const INK_COAT_DRY_SPAN = 0.46

export function inkCoatParams(p: NuqtaParams, k: number): NuqtaParams {
  return {
    ...p,
    seed: p.seed + 11 * k,
    reach: p.reach * INK_COAT_REACH[k],
    originDx: p.originDx + 0.5 * k,
    originDy: p.originDy - 0.4 * k,
  }
}

export function inkCoatFingers(p: NuqtaParams): Float32Array[] {
  return Array.from({ length: INK_COAT_COUNT }, (_, k) => nuqtaFingers(inkCoatParams(p, k).seed))
}

/** Coat [k]'s opacity at dry 0..1. The pool fades first, the under-coat last. */
export function inkCoatAlpha(k: number, dry: number): number {
  const order = INK_COAT_COUNT - 1 - k
  const start = (order * (1 - INK_COAT_DRY_SPAN)) / (INK_COAT_COUNT - 1)
  const x = clamp((dry - start) / INK_COAT_DRY_SPAN)
  const eased = x * x * (3 - 2 * x)
  return INK_COAT_ALPHA * (1 - eased)
}

/** Under-coat reach that still lets the pool cover [distance] px. */
export function inkWashReachToCover(distance: number): number {
  return (distance / INK_COAT_REACH[INK_COAT_REACH.length - 1]) * 1.02
}

function clamp(x: number, lo = 0, hi = 1): number {
  return Math.min(hi, Math.max(lo, x))
}

/** Split a CSS colour into rgb channels. Hex and `rgb()` / `rgba()` both work. */
export function parseRgb(color: string): [number, number, number] {
  const hex = color.trim().match(/^#([\da-f]{6})$/i)
  if (hex) {
    const n = parseInt(hex[1], 16)
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
  }
  const rgb = color.match(/rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)/i)
  if (rgb) return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])]
  return [14, 92, 74]
}

export function rgba([r, g, b]: [number, number, number], alpha: number): string {
  return `rgba(${r}, ${g}, ${b}, ${clamp(alpha)})`
}

export function lerpRgb(
  a: [number, number, number],
  b: [number, number, number],
  t: number,
): [number, number, number] {
  const u = clamp(t)
  return [
    a[0] + (b[0] - a[0]) * u,
    a[1] + (b[1] - a[1]) * u,
    a[2] + (b[2] - a[2]) * u,
  ]
}

/**
 * Every coat of an unbounded wash landing at [origin], under-coat reaching
 * [full] px. Caller clips to the surface. Android `drawInkWashCoats`.
 */
export function paintInkWash(
  ctx: CanvasRenderingContext2D,
  t: number,
  dry: number,
  params: NuqtaParams,
  fingers: Float32Array[],
  rgb: [number, number, number],
  originX: number,
  originY: number,
  full: number,
): void {
  if (t <= 0) return
  for (let k = 0; k < INK_COAT_COUNT; k++) {
    const alpha = inkCoatAlpha(k, dry)
    if (alpha <= 0) continue
    const coat = inkCoatParams(params, k)
    const reach = full * INK_COAT_REACH[k]
    const radii = nuqtaDropRadii(t, coat, fingers[k])
    const edge = Math.max(...radii)
    const stops = nuqtaInkStops(t, coat, edge)
    const path = new Path2D(nuqtaDropPath(originX, originY, reach, radii))
    const radius = Math.max(reach * edge, 0.5)
    const gradient = ctx.createRadialGradient(originX, originY, 0, originX, originY, radius)
    for (let i = 0; i < NUQTA_INK_STOP_COUNT; i++) {
      gradient.addColorStop(
        clamp(stops[i * 2]),
        rgba(rgb, alpha * stops[i * 2 + 1]),
      )
    }
    ctx.fillStyle = gradient
    ctx.fill(path)
  }
}

/**
 * The settings glyph's drop: the same coats, clipped to the qalam rhombus
 * and scaled from the centre. Android `drawInkNuqtaCoats` inside `scale`.
 */
export function paintSettingsNuqta(
  ctx: CanvasRenderingContext2D,
  size: number,
  t: number,
  dry: number,
  params: NuqtaParams,
  rgb: [number, number, number],
  swell: number,
): void {
  if (t <= 0) return
  ctx.save()
  ctx.translate(size / 2, size / 2)
  ctx.scale(swell, swell)
  ctx.translate(-size / 2, -size / 2)
  ctx.clip(new Path2D(nuqtaPath(size, params.bow)))
  const originX = size * 0.5 + params.originDx
  const originY = size * 0.5 + params.originDy
  const fingers = inkCoatFingers(params)
  for (let k = 0; k < INK_COAT_COUNT; k++) {
    const alpha = inkCoatAlpha(k, dry)
    if (alpha <= 0) continue
    const coat = inkCoatParams(params, k)
    const full = size * 0.7 * coat.reach
    const radii = nuqtaDropRadii(t, coat, fingers[k])
    const edge = Math.max(...radii)
    const stops = nuqtaInkStops(t, coat, edge)
    const ox = originX + (coat.originDx - params.originDx)
    const oy = originY + (coat.originDy - params.originDy)
    const path = new Path2D(nuqtaDropPath(ox, oy, full, radii))
    const radius = Math.max(full * edge, 0.5)
    const gradient = ctx.createRadialGradient(ox, oy, 0, ox, oy, radius)
    for (let i = 0; i < NUQTA_INK_STOP_COUNT; i++) {
      gradient.addColorStop(clamp(stops[i * 2]), rgba(rgb, alpha * stops[i * 2 + 1]))
    }
    ctx.fillStyle = gradient
    ctx.fill(path)
  }
  ctx.restore()
}
