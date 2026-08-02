/**
 * Progressive royal-green vellum for contextual guides.
 *
 * Ports Android's Android 11–12 `GradientInkSpillField` fallback: a directional
 * linear wash from the lesson body toward the live spotlight. WebGL/runtime
 * shaders can replace this later; the product shape is the inverse spotlight.
 */
import { useEffect, useRef } from 'react'
import type { Point } from './contextualTipPlacement'
import { normalize } from './contextualTipPlacement'

/** Defaults mirrored from Android `ContextualGuideTuning`. */
const BODY_EDGE = 0.5
const FEATHER_WIDTH = 0.2819

type Props = {
  progress: number
  spotlight: Point
  body: Point
  color: string
  width: number
  height: number
}

function parseRgb(color: string): { r: number; g: number; b: number } {
  const hex = color.trim()
  if (hex.startsWith('#') && hex.length === 7) {
    return {
      r: parseInt(hex.slice(1, 3), 16),
      g: parseInt(hex.slice(3, 5), 16),
      b: parseInt(hex.slice(5, 7), 16),
    }
  }
  const m = hex.match(/rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)/i)
  if (m) {
    return { r: Number(m[1]), g: Number(m[2]), b: Number(m[3]) }
  }
  return { r: 6, g: 44, b: 36 } // royal green paper
}

export function InkSpillField({
  progress,
  spotlight,
  body,
  color,
  width,
  height,
}: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || width <= 0 || height <= 0) return
    const amount = progress
    const dpr = window.devicePixelRatio || 1
    const cssW = width
    const cssH = height
    if (
      canvas.width !== Math.round(cssW * dpr) ||
      canvas.height !== Math.round(cssH * dpr)
    ) {
      canvas.width = Math.round(cssW * dpr)
      canvas.height = Math.round(cssH * dpr)
      canvas.style.width = `${cssW}px`
      canvas.style.height = `${cssH}px`
    }
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, cssW, cssH)
    if (amount <= 0.001) return

    const direction = normalize({
      x: spotlight.x - body.x,
      y: spotlight.y - body.y,
    })
    const reachFraction = BODY_EDGE + FEATHER_WIDTH
    const span =
      Math.abs(direction.x) * cssW + Math.abs(direction.y) * cssH
    const reach = span * reachFraction * amount
    const bodyStop = Math.min(1, Math.max(0, BODY_EDGE / reachFraction))
    const { r, g, b } = parseRgb(color)
    const fieldCenter = { x: cssW / 2, y: cssH / 2 }
    const start = {
      x: fieldCenter.x - direction.x * (span / 2),
      y: fieldCenter.y - direction.y * (span / 2),
    }
    const end = {
      x: start.x + direction.x * reach,
      y: start.y + direction.y * reach,
    }
    const gradient = ctx.createLinearGradient(start.x, start.y, end.x, end.y)
    gradient.addColorStop(0, `rgba(${r}, ${g}, ${b}, ${amount})`)
    gradient.addColorStop(bodyStop, `rgba(${r}, ${g}, ${b}, ${amount})`)
    gradient.addColorStop(
      bodyStop + (1 - bodyStop) * 0.42,
      `rgba(${r}, ${g}, ${b}, ${0.72 * amount})`,
    )
    gradient.addColorStop(
      bodyStop + (1 - bodyStop) * 0.76,
      `rgba(${r}, ${g}, ${b}, ${0.28 * amount})`,
    )
    gradient.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`)
    ctx.fillStyle = gradient
    ctx.fillRect(0, 0, cssW, cssH)
  }, [progress, spotlight.x, spotlight.y, body.x, body.y, color, width, height])

  return (
    <canvas
      ref={canvasRef}
      className="contextual-tip-vellum"
      aria-hidden="true"
    />
  )
}
