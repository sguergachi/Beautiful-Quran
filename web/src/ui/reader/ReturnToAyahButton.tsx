/**
 * Return-to-ayah roundel — port of Android `IslamicReturnToAyahButton`.
 *
 * Twelve-petal illuminated marker with a reed-pen (qalam) arrow that paints
 * in stroke-by-stroke. The arrow points up or down toward the active verse
 * and re-inks from a dry page whenever the direction flips.
 */
import { useEffect, useRef, useCallback, type CSSProperties } from 'react'
import { animate, cubicBezier, type AnimationPlaybackControls } from 'motion'
import { FAST_OUT_SLOW_IN } from '../motion/easing'

const SIZE_PX = 44
const INK_MS = 1100
const INK_DELAY_MS = 120
const ROTATE_MS = 300

type Props = {
  pointUp: boolean
  onClick: () => void
  className?: string
  style?: CSSProperties
}

const fastOutSlowIn = cubicBezier(...FAST_OUT_SLOW_IN)

function span(progress: number, from: number, to: number): number {
  return fastOutSlowIn(Math.min(1, Math.max(0, (progress - from) / (to - from))))
}

function cssColor(el: Element, name: string, fallback: string): string {
  const v = getComputedStyle(el).getPropertyValue(name).trim()
  return v || fallback
}

function withAlpha(color: string, a: number): string {
  if (color.startsWith('#')) {
    let hex = color.slice(1)
    if (hex.length === 3) {
      hex = hex
        .split('')
        .map((c) => c + c)
        .join('')
    }
    if (hex.length === 6) {
      const r = parseInt(hex.slice(0, 2), 16)
      const g = parseInt(hex.slice(2, 4), 16)
      const b = parseInt(hex.slice(4, 6), 16)
      return `rgba(${r}, ${g}, ${b}, ${a})`
    }
  }
  return `color-mix(in srgb, ${color} ${Math.round(a * 100)}%, transparent)`
}

type Pt = { x: number; y: number }

function buildCorolla(c: number, cuspR: number, tipR: number): Path2D {
  const petals = 12
  const reach = tipR - cuspR
  const step = 360 / petals
  const polar = (degrees: number, radius: number): Pt => {
    const rad = (degrees * Math.PI) / 180
    return { x: c + radius * Math.cos(rad), y: c + radius * Math.sin(rad) }
  }
  const path = new Path2D()
  for (let k = 0; k < petals; k++) {
    const cuspA = k * step - 90
    const tipA = cuspA + step / 2
    const nextA = cuspA + step
    const cusp = polar(cuspA, cuspR)
    const tip = polar(tipA, tipR)
    const next = polar(nextA, cuspR)
    if (k === 0) path.moveTo(cusp.x, cusp.y)
    const outFromCusp = polar(cuspA, cuspR + reach * 0.55)
    const inToTip = polar(tipA, tipR - reach * 0.45)
    path.bezierCurveTo(outFromCusp.x, outFromCusp.y, inToTip.x, inToTip.y, tip.x, tip.y)
    const outFromTip = polar(tipA, tipR - reach * 0.45)
    const inToNext = polar(nextA, cuspR + reach * 0.55)
    path.bezierCurveTo(outFromTip.x, outFromTip.y, inToNext.x, inToNext.y, next.x, next.y)
  }
  path.closePath()
  return path
}

function buildShaft(c: number, s: number): Path2D {
  const pt = (x: number, y: number): Pt => ({ x: c + x * s, y: c + y * s })
  const path = new Path2D()
  const a = pt(-0.034, -0.15)
  path.moveTo(a.x, a.y)
  const nib = pt(0.03, -0.184)
  path.lineTo(nib.x, nib.y)
  const c1 = pt(0.044, -0.06)
  const c2 = pt(0.012, 0.02)
  const br = pt(0.015, 0.15)
  path.bezierCurveTo(c1.x, c1.y, c2.x, c2.y, br.x, br.y)
  const tip = pt(0, 0.192)
  path.bezierCurveTo(pt(0.012, 0.172).x, pt(0.012, 0.172).y, pt(0.006, 0.187).x, pt(0.006, 0.187).y, tip.x, tip.y)
  const bl = pt(-0.017, 0.148)
  path.bezierCurveTo(pt(-0.008, 0.187).x, pt(-0.008, 0.187).y, pt(-0.015, 0.172).x, pt(-0.015, 0.172).y, bl.x, bl.y)
  const c3 = pt(-0.032, 0.02)
  const c4 = pt(-0.046, -0.06)
  path.bezierCurveTo(c3.x, c3.y, c4.x, c4.y, a.x, a.y)
  path.closePath()
  return path
}

function buildBarb(c: number, s: number, side: number): Path2D {
  const pt = (x: number, y: number): Pt => ({ x: c + x * s, y: c + y * s })
  const path = new Path2D()
  const tip = pt(side * 0.004, 0.192)
  path.moveTo(tip.x, tip.y)
  const o1 = pt(side * -0.07, 0.158)
  const o2 = pt(side * -0.118, 0.1)
  const end = pt(side * -0.15, 0.038)
  path.bezierCurveTo(o1.x, o1.y, o2.x, o2.y, end.x, end.y)
  const i1 = pt(side * -0.1, 0.058)
  const i2 = pt(side * -0.055, 0.1)
  const root = pt(side * -0.012, 0.128)
  path.bezierCurveTo(i1.x, i1.y, i2.x, i2.y, root.x, root.y)
  path.closePath()
  return path
}

/** Reused offscreen for the feathered ink wipe (avoids allocs every frame). */
let inkLayer: HTMLCanvasElement | null = null
let inkLayerCtx: CanvasRenderingContext2D | null = null

function ensureInkLayer(cssSize: number, dpr: number): CanvasRenderingContext2D | null {
  const w = Math.max(1, Math.ceil(cssSize * dpr))
  if (!inkLayer) {
    inkLayer = document.createElement('canvas')
    inkLayerCtx = inkLayer.getContext('2d')
  }
  if (!inkLayerCtx) return null
  if (inkLayer.width !== w || inkLayer.height !== w) {
    inkLayer.width = w
    inkLayer.height = w
  }
  inkLayerCtx.setTransform(dpr, 0, 0, dpr, 0, 0)
  inkLayerCtx.globalCompositeOperation = 'source-over'
  inkLayerCtx.clearRect(0, 0, cssSize, cssSize)
  return inkLayerCtx
}

function inkStroke(
  ctx: CanvasRenderingContext2D,
  path: Path2D,
  color: string,
  progress: number,
  from: Pt,
  to: Pt,
  layerSize: number,
  dpr: number,
) {
  if (progress <= 0) return
  const feather = 0.28
  const head = progress * (1 + feather)
  if (head - feather >= 1) {
    ctx.fillStyle = color
    ctx.fill(path)
    return
  }

  // Full-size offscreen layer — same idea as Compose saveLayer + DstIn.
  const octx = ensureInkLayer(layerSize, dpr)
  if (!octx || !inkLayer) return

  octx.fillStyle = color
  octx.fill(path)

  const g = octx.createLinearGradient(from.x, from.y, to.x, to.y)
  const solid = Math.min(1, Math.max(0, head - feather))
  const tip = Math.min(1, head)
  g.addColorStop(0, '#000')
  g.addColorStop(solid, '#000')
  g.addColorStop(tip, 'rgba(0,0,0,0)')
  if (tip < 1) g.addColorStop(1, 'rgba(0,0,0,0)')
  octx.globalCompositeOperation = 'destination-in'
  octx.fillStyle = g
  octx.fillRect(0, 0, layerSize, layerSize)
  octx.globalCompositeOperation = 'source-over'

  // Size in current user space (CSS px) so parent dpr/rotation transforms apply.
  ctx.drawImage(inkLayer, 0, 0, layerSize, layerSize)
}

export function ReturnToAyahButton({ pointUp, onClick, className, style }: Props) {
  const btnRef = useRef<HTMLButtonElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const inkRef = useRef(0)
  const rotationRef = useRef(pointUp ? 180 : 0)
  const targetRotationRef = useRef(pointUp ? 180 : 0)
  const controlsRef = useRef<AnimationPlaybackControls[]>([])
  const rotFromRef = useRef(pointUp ? 180 : 0)

  const stopControls = useCallback(() => {
    for (const c of controlsRef.current) c.stop()
    controlsRef.current = []
  }, [])

  const draw = useCallback(() => {
    const canvas = canvasRef.current
    const btn = btnRef.current
    if (!canvas || !btn) return
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const css = SIZE_PX
    if (canvas.width !== Math.round(css * dpr) || canvas.height !== Math.round(css * dpr)) {
      canvas.width = Math.round(css * dpr)
      canvas.height = Math.round(css * dpr)
      canvas.style.width = `${css}px`
      canvas.style.height = `${css}px`
    }
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, css, css)

    const paper = cssColor(btn, '--paper', '#faf3e8')
    const gold = cssColor(btn, '--gold', '#a8831d')
    const goldBright = cssColor(btn, '--gold-bright', '#e9cd7a')
    const goldDeep = cssColor(btn, '--gold-deep', '#8a6b1e')
    const accent = cssColor(btn, '--accent', '#0e5c4a')
    const embossDark = cssColor(btn, '--emboss-dark', 'rgba(0,0,0,0.14)')
    const embossLight = cssColor(btn, '--emboss-light', 'rgba(255,255,255,0.35)')

    const s = css
    const c = s / 2
    const cuspR = s * 0.365
    const tipR = s * 0.475
    const corolla = buildCorolla(c, cuspR, tipR)

    // Paper body with faint gilt bloom toward the rim.
    const bloom = ctx.createRadialGradient(c, c, 0, c, c, tipR)
    bloom.addColorStop(0, paper)
    bloom.addColorStop(0.62, paper)
    bloom.addColorStop(1, withAlpha(gold, 0.14))
    ctx.fillStyle = bloom
    ctx.fill(corolla)

    // Embossed edge, then gilt face.
    const edgeW = s * 0.02
    ctx.lineJoin = 'round'
    ctx.lineWidth = edgeW
    ctx.strokeStyle = embossDark
    ctx.save()
    ctx.translate(0.8, 0.8)
    ctx.stroke(corolla)
    ctx.restore()
    ctx.strokeStyle = embossLight
    ctx.save()
    ctx.translate(-0.8, -0.8)
    ctx.stroke(corolla)
    ctx.restore()

    const gilt = ctx.createLinearGradient(c, 0, c, s)
    gilt.addColorStop(0, goldBright)
    gilt.addColorStop(0.5, gold)
    gilt.addColorStop(1, goldDeep)
    ctx.strokeStyle = gilt
    ctx.stroke(corolla)

    // Pearls in each notch.
    for (let k = 0; k < 12; k++) {
      const deg = (k * 360) / 12 - 90
      const rad = (deg * Math.PI) / 180
      const px = c + s * 0.44 * Math.cos(rad)
      const py = c + s * 0.44 * Math.sin(rad)
      ctx.fillStyle = gilt
      ctx.beginPath()
      ctx.arc(px, py, s * 0.016, 0, Math.PI * 2)
      ctx.fill()
    }

    // Hairline ring framing the ink field.
    ctx.strokeStyle = withAlpha(gold, 0.45)
    ctx.lineWidth = s * 0.009
    ctx.beginPath()
    ctx.arc(c, c, s * 0.285, 0, Math.PI * 2)
    ctx.stroke()

    // Qalam arrow — rotate then paint strokes.
    const ink = inkRef.current
    const rotation = rotationRef.current
    ctx.save()
    ctx.translate(c, c)
    ctx.rotate((rotation * Math.PI) / 180)
    ctx.translate(-c, -c)

    const shaft = buildShaft(c, s)
    const barbR = buildBarb(c, s, 1)
    const barbL = buildBarb(c, s, -1)
    const pt = (x: number, y: number): Pt => ({ x: c + x * s, y: c + y * s })

    inkStroke(ctx, shaft, accent, span(ink, 0, 0.48), pt(0, -0.19), pt(0, 0.2), s, dpr)
    inkStroke(ctx, barbR, accent, span(ink, 0.42, 0.74), pt(0, 0.18), pt(-0.16, 0.03), s, dpr)
    inkStroke(ctx, barbL, accent, span(ink, 0.66, 1), pt(0, 0.18), pt(0.16, 0.03), s, dpr)
    ctx.restore()
  }, [])

  const startAnim = useCallback(
    (restartInk: boolean) => {
      stopControls()
      rotFromRef.current = rotationRef.current
      const fromRot = rotFromRef.current
      const toRot = targetRotationRef.current

      const rot = animate(0, 1, {
        duration: ROTATE_MS / 1000,
        ease: [...FAST_OUT_SLOW_IN] as [number, number, number, number],
        onUpdate: (t) => {
          rotationRef.current = fromRot + (toRot - fromRot) * t
          draw()
        },
      })

      const next: AnimationPlaybackControls[] = [rot]
      if (restartInk) {
        inkRef.current = 0
        const ink = animate(0, 1, {
          duration: INK_MS / 1000,
          delay: INK_DELAY_MS / 1000,
          ease: 'linear',
          onUpdate: (t) => {
            inkRef.current = t
            draw()
          },
        })
        next.push(ink)
      }
      controlsRef.current = next
      draw()
    },
    [draw, stopControls],
  )

  // Direction change: rotate + re-ink from dry.
  useEffect(() => {
    targetRotationRef.current = pointUp ? 180 : 0
    startAnim(true)
  }, [pointUp, startAnim])

  // First paint + theme / DPR changes.
  useEffect(() => {
    draw()
    const btn = btnRef.current
    if (!btn || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(() => draw())
    ro.observe(btn)
    return () => ro.disconnect()
  }, [draw])

  useEffect(() => () => stopControls(), [stopControls])

  return (
    <button
      ref={btnRef}
      type="button"
      className={className ?? 'return-ayah'}
      aria-label="Return to ayah"
      onClick={onClick}
      style={style}
    >
      <canvas ref={canvasRef} width={SIZE_PX} height={SIZE_PX} aria-hidden="true" />
    </button>
  )
}
