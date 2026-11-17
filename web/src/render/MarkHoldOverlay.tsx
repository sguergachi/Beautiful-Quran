/**
 * Paints the mark hold's gilded shamsa above every verse — Android
 * `markHoldOverlay`. A fixed, click-through canvas that draws only while a
 * hold is up; idle it is cleared and costs nothing.
 */
import { useEffect, useRef } from 'react'
import { markHold } from './markHold'
import { markHoldGold, shamsaFrameRays, shamsaRayLook } from './markHoldShamsa'

function luminance(color: string): number {
  const m = color.match(/rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)/)
  if (!m) return 1
  return (0.2126 * Number(m[1]) + 0.7152 * Number(m[2]) + 0.0722 * Number(m[3])) / 255
}

export function MarkHoldOverlay() {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    let raf = 0
    let painted = false
    const paint = () => {
      raf = 0
      const ctx = canvas.getContext('2d')
      if (!ctx) return
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      const w = window.innerWidth
      const h = window.innerHeight
      if (!markHold.visible || !markHold.target) {
        if (painted) ctx.clearRect(0, 0, canvas.width, canvas.height)
        painted = false
        return
      }
      if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
        canvas.width = Math.round(w * dpr)
        canvas.height = Math.round(h * dpr)
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      ctx.clearRect(0, 0, w, h)
      painted = true
      const t = markHold.target
      const { charge, burst, hint, surge } = markHold.values
      const { frame, rays, expansion } = shamsaFrameRays(t.bounds, t.baseline, t.finger, t.rtl, charge)
      const css = getComputedStyle(document.documentElement)
      const dark = luminance(getComputedStyle(document.body).backgroundColor) < 0.5
      const gold = markHoldGold(
        css.getPropertyValue('--gold').trim() || '#b8913a',
        css.getPropertyValue('--gold-bright').trim() || '#d9b45a',
        css.getPropertyValue('--gold-deep').trim() || '#8a6a24',
        dark,
      )
      const inner = Math.max(frame.rx, frame.ry) + 2.5
      const outer = inner + (5 + (10 - 5) * expansion) * 1.2
      const gilt = ctx.createRadialGradient(frame.cx, frame.cy, 0, frame.cx, frame.cy, outer)
      gilt.addColorStop(0, gold.base)
      gilt.addColorStop(Math.min(0.99, Math.max(0, inner / outer)), gold.base)
      gilt.addColorStop(1, gold.tip)
      ctx.fillStyle = gilt
      rays.forEach((ray, i) => {
        const look = shamsaRayLook(i, rays.length, charge, burst, hint, surge)
        if (look.alpha <= 0.004) return
        const baseX = ray.baseX + ray.dirX * look.lift
        const baseY = ray.baseY + ray.dirY * look.lift
        const len = ray.length * look.length
        const half = ray.halfWidth * look.width
        const px = -ray.dirY * half
        const py = ray.dirX * half
        const tipX = baseX + ray.dirX * len
        const tipY = baseY + ray.dirY * len
        const waistX = baseX + ray.dirX * len * 0.45
        const waistY = baseY + ray.dirY * len * 0.45
        ctx.globalAlpha = Math.min(1, Math.max(0, look.alpha))
        ctx.beginPath()
        ctx.moveTo(baseX + px, baseY + py)
        ctx.quadraticCurveTo(waistX + px * 0.35, waistY + py * 0.35, tipX, tipY)
        ctx.quadraticCurveTo(waistX - px * 0.35, waistY - py * 0.35, baseX - px, baseY - py)
        ctx.closePath()
        ctx.fill()
      })
      ctx.globalAlpha = 1
    }
    const schedule = () => {
      if (!raf) raf = requestAnimationFrame(paint)
    }
    const unsubscribe = markHold.subscribe(schedule)
    return () => {
      unsubscribe()
      if (raf) cancelAnimationFrame(raf)
    }
  }, [])
  return <canvas ref={canvasRef} className="mark-hold-overlay" aria-hidden="true" />
}
