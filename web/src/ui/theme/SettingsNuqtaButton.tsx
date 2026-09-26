import { useEffect, useRef } from 'react'
import { SHIPPED_NUQTA } from '../kit/nuqta'
import { getSettingsApproach, subscribeSheetMotion } from '../paper/sheetMotion'
import { lerpRgb, paintSettingsNuqta, parseRgb } from './inkCoats'
import {
  SETTINGS_NUQTA_DRIED_TINT,
  SETTINGS_NUQTA_SCALE,
  SETTINGS_NUQTA_STAGE_SWELL,
  SettingsNuqtaMachine,
  settingsNuqtaStretch,
} from './settingsNuqta'

const ICON = 26
/** Material rounded Tune, 24px viewBox. Drawn on the canvas so the stain needs no PNG encode. */
const TUNE_PATH = 'M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6zm-6 4h2V3H9v10z'

function drawTune(
  ctx: CanvasRenderingContext2D,
  size: number,
  rgb: [number, number, number],
) {
  const scale = ICON / 24
  ctx.save()
  ctx.translate(size / 2, size / 2)
  ctx.scale(scale, scale)
  ctx.translate(-12, -12)
  ctx.fillStyle = `rgb(${rgb[0]}, ${rgb[1]}, ${rgb[2]})`
  ctx.fill(new Path2D(TUNE_PATH))
  ctx.restore()
}

/**
 * Tune glyph with a nuqta behind it. Holding or turning the stack toward
 * Settings spreads the drop; letting go short of Settings dries it in place.
 * Android `SettingsNuqtaIcon`.
 */
export function SettingsNuqtaButton({
  className,
  disabled,
  onActivate,
}: {
  className?: string
  disabled?: boolean
  onActivate: () => void
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const machine = useRef(new SettingsNuqtaMachine())
  const frame = useRef(0)
  const kickRef = useRef<() => void>(() => {})

  useEffect(() => {
    let last = performance.now()
    const quietLayer = document.createElement('canvas')
    const stainLayer = document.createElement('canvas')
    const paint = () => {
      const canvas = canvasRef.current
      if (!canvas) return
      const m = machine.current
      const approach = getSettingsApproach()
      const size = ICON * SETTINGS_NUQTA_SCALE
      const dpr = window.devicePixelRatio || 1
      const px = Math.round(size * dpr)
      if (canvas.width !== px) {
        canvas.width = px
        canvas.height = px
        canvas.style.width = `${size}px`
        canvas.style.height = `${size}px`
      }
      const ctx = canvas.getContext('2d')
      if (!ctx) return
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      ctx.clearRect(0, 0, size, size)
      const styles = getComputedStyle(document.documentElement)
      const quiet = parseRgb(styles.getPropertyValue('--ink-rung-quiet'))
      const onAccent = parseRgb(styles.getPropertyValue('--on-accent'))
      const wet = m.spread > 0 && m.presence > 0
      if (wet) {
        const accent = parseRgb(styles.getPropertyValue('--accent'))
        const paper = parseRgb(styles.getPropertyValue('--paper'))
        const dried = lerpRgb(accent, paper, SETTINGS_NUQTA_DRIED_TINT)
        const coat = lerpRgb(accent, dried, 1 - m.presence)
        const swell =
          1 +
          Math.max(settingsNuqtaStretch(approach), m.pull) +
          SETTINGS_NUQTA_STAGE_SWELL * m.stage
        if (quietLayer.width !== px) {
          quietLayer.width = px
          quietLayer.height = px
          stainLayer.width = px
          stainLayer.height = px
        }
        const ink = quietLayer.getContext('2d')
        const stain = stainLayer.getContext('2d')
        if (ink && stain) {
          ink.setTransform(dpr, 0, 0, dpr, 0, 0)
          ink.clearRect(0, 0, size, size)
          paintSettingsNuqta(ink, size, m.spread, 1 - m.presence, SHIPPED_NUQTA, coat, swell)
          ctx.drawImage(quietLayer, 0, 0, size, size)
          stain.setTransform(dpr, 0, 0, dpr, 0, 0)
          stain.clearRect(0, 0, size, size)
          drawTune(stain, size, onAccent)
          stain.globalCompositeOperation = 'destination-in'
          stain.drawImage(quietLayer, 0, 0, size, size)
          stain.globalCompositeOperation = 'source-over'
          ctx.drawImage(stainLayer, 0, 0, size, size)
          const outside = stainLayer
          const outsideCtx = stain
          outsideCtx.clearRect(0, 0, size, size)
          drawTune(outsideCtx, size, quiet)
          outsideCtx.globalCompositeOperation = 'destination-out'
          outsideCtx.drawImage(quietLayer, 0, 0, size, size)
          outsideCtx.globalCompositeOperation = 'source-over'
          ctx.drawImage(outside, 0, 0, size, size)
          return
        }
      }
      drawTune(ctx, size, quiet)
    }
    const loop = (now: number) => {
      const dt = now - last
      last = now
      machine.current.step(dt, getSettingsApproach(), SHIPPED_NUQTA.spreadMs)
      paint()
      frame.current = machine.current.busy() ? requestAnimationFrame(loop) : 0
    }
    const kick = () => {
      if (frame.current) return
      last = performance.now()
      frame.current = requestAnimationFrame(loop)
    }
    kickRef.current = kick
    paint()
    kick()
    const unsub = subscribeSheetMotion(kick)
    return () => {
      unsub()
      if (frame.current) cancelAnimationFrame(frame.current)
    }
  }, [])

  return (
    <button
      type="button"
      className={`settings-nuqta${className ? ` ${className}` : ''}`}
      aria-label="Open settings"
      disabled={disabled}
      onPointerDown={(event) => {
        if (disabled) return
        event.currentTarget.setPointerCapture(event.pointerId)
        machine.current.held = true
        kickRef.current()
      }}
      onPointerUp={() => {
        machine.current.held = false
        kickRef.current()
      }}
      onPointerCancel={() => {
        machine.current.held = false
        kickRef.current()
      }}
      onLostPointerCapture={() => {
        machine.current.held = false
        kickRef.current()
      }}
      onClick={() => {
        if (disabled) return
        machine.current.pressed = true
        machine.current.held = false
        kickRef.current()
        onActivate()
      }}
    >
      <canvas ref={canvasRef} className="settings-nuqta-glyph" aria-hidden="true" />
    </button>
  )
}
