import { useEffect, useRef } from 'react'
import { IconTune } from '../icons/PlaybackIcons'
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
  const onInkRef = useRef<HTMLSpanElement>(null)
  const machine = useRef(new SettingsNuqtaMachine())
  const frame = useRef(0)
  const kickRef = useRef<() => void>(() => {})

  useEffect(() => {
    let last = performance.now()
    const paint = () => {
      const canvas = canvasRef.current
      const onInk = onInkRef.current
      if (!canvas || !onInk) return
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
      const wet = m.spread > 0 && m.presence > 0
      if (!wet) {
        onInk.style.webkitMaskImage = 'none'
        onInk.style.maskImage = 'none'
        onInk.style.opacity = '0'
        return
      }
      const styles = getComputedStyle(document.documentElement)
      const accent = parseRgb(styles.getPropertyValue('--accent'))
      const paper = parseRgb(styles.getPropertyValue('--paper'))
      const dried = lerpRgb(accent, paper, SETTINGS_NUQTA_DRIED_TINT)
      const coat = lerpRgb(accent, dried, 1 - m.presence)
      const swell =
        1 +
        Math.max(settingsNuqtaStretch(approach), m.pull) +
        SETTINGS_NUQTA_STAGE_SWELL * m.stage
      paintSettingsNuqta(ctx, size, m.spread, 1 - m.presence, SHIPPED_NUQTA, coat, swell)
      const url = canvas.toDataURL()
      const mask = `url(${url})`
      onInk.style.webkitMaskImage = mask
      onInk.style.maskImage = mask
      onInk.style.webkitMaskSize = `${size}px ${size}px`
      onInk.style.maskSize = `${size}px ${size}px`
      onInk.style.webkitMaskPosition = 'center'
      onInk.style.maskPosition = 'center'
      onInk.style.opacity = '1'
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
      onPointerDown={() => {
        if (disabled) return
        machine.current.held = true
        kickRef.current()
      }}
      onPointerUp={() => {
        machine.current.held = false
      }}
      onPointerCancel={() => {
        machine.current.held = false
      }}
      onClick={() => {
        if (disabled) return
        machine.current.pressed = true
        machine.current.held = false
        kickRef.current()
        onActivate()
      }}
    >
      <canvas ref={canvasRef} aria-hidden="true" />
      <span className="settings-nuqta-glyph">
        <IconTune />
      </span>
      <span ref={onInkRef} className="settings-nuqta-glyph settings-nuqta-on-ink" aria-hidden="true">
        <IconTune />
      </span>
    </button>
  )
}
