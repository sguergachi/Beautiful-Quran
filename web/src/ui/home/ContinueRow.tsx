import { useEffect, useRef } from 'react'
import { appStore } from '../../store/appStore'
import { SHIPPED_NUQTA } from '../kit/nuqta'
import {
  getOpenSurahId,
  getReaderApproach,
  subscribeSheetMotion,
} from '../paper/sheetMotion'
import { inkCoatFingers, inkWashReachToCover, paintInkWash, parseRgb } from '../theme/inkCoats'
import { ContinueInkMachine } from './continueInk'

const WIPE_FEATHER = 14

/**
 * Continue listening, flooded with ink when that chapter is the one the
 * stack is turning into. A different chapter leaves the row dry.
 * Android `ContinueRow`.
 */
export function ContinueRow({
  surahId,
  ayah,
  transliteration,
  arabic,
  onPrepare,
}: {
  surahId: number
  ayah: number
  transliteration: string
  arabic: string
  onPrepare: () => void
}) {
  const machine = useRef(new ContinueInkMachine())
  const frame = useRef(0)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const onInkRef = useRef<HTMLSpanElement>(null)
  const fingers = useRef(inkCoatFingers(SHIPPED_NUQTA))
  const kickRef = useRef<() => void>(() => {})

  useEffect(() => {
    let last = performance.now()
    const paint = () => {
      const button = buttonRef.current
      const canvas = canvasRef.current
      const onInk = onInkRef.current
      if (!button || !canvas || !onInk) return
      const m = machine.current
      const dpr = window.devicePixelRatio || 1
      const w = button.clientWidth
      const h = button.clientHeight
      if (w < 1 || h < 1) return
      const pxW = Math.round(w * dpr)
      const pxH = Math.round(h * dpr)
      if (canvas.width !== pxW || canvas.height !== pxH) {
        canvas.width = pxW
        canvas.height = pxH
        canvas.style.width = `${w}px`
        canvas.style.height = `${h}px`
      }
      const ctx = canvas.getContext('2d')
      if (!ctx) return
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      ctx.clearRect(0, 0, w, h)
      if (m.spread <= 0) {
        onInk.style.opacity = '0'
        return
      }
      const accent = parseRgb(getComputedStyle(document.documentElement).getPropertyValue('--accent'))
      const reach = Math.hypot(w, h / 2)
      paintInkWash(
        ctx,
        m.spread,
        0,
        SHIPPED_NUQTA,
        fingers.current,
        accent,
        w,
        h / 2,
        inkWashReachToCover(reach),
      )
      if (m.dry > 0) {
        const edge = -WIPE_FEATHER + (w + 2 * WIPE_FEATHER) * m.dry
        ctx.globalCompositeOperation = 'destination-in'
        const fade = ctx.createLinearGradient(edge - WIPE_FEATHER, 0, edge + WIPE_FEATHER, 0)
        fade.addColorStop(0, 'rgba(0,0,0,0)')
        fade.addColorStop(1, 'rgba(0,0,0,1)')
        ctx.fillStyle = fade
        ctx.fillRect(0, 0, w, h)
        ctx.globalCompositeOperation = 'source-over'
      }
      const url = canvas.toDataURL()
      onInk.style.webkitMaskImage = `url(${url})`
      onInk.style.maskImage = `url(${url})`
      onInk.style.opacity = '1'
    }
    const loop = (now: number) => {
      const dt = now - last
      last = now
      machine.current.step(dt, getReaderApproach(), getOpenSurahId(), surahId)
      paint()
      const approach = getReaderApproach()
      const moving = approach > 0.001 && approach < 0.999
      frame.current = machine.current.busy() || moving ? requestAnimationFrame(loop) : 0
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
    const observed = buttonRef.current
    const observer = new ResizeObserver(() => paint())
    if (observed) observer.observe(observed)
    return () => {
      observer.disconnect()
      unsub()
      if (frame.current) cancelAnimationFrame(frame.current)
    }
  }, [surahId])

  const copy = (
    <>
      <span className="continue-copy">
        <span className="continue-label">Continue listening</span>
        <span className="continue-target">
          {transliteration}
          {ayah > 0 ? ` · Ayah ${ayah}` : ''}
        </span>
      </span>
      <span className="continue-ar" lang="ar" dir="rtl">
        {arabic}
      </span>
    </>
  )

  return (
    <div className="continue-row">
      <button
        ref={buttonRef}
        type="button"
        className="continue"
        onPointerEnter={onPrepare}
        onPointerDown={onPrepare}
        onFocus={onPrepare}
        onClick={() => {
          machine.current.tapped = true
          kickRef.current()
          // Let the ink's first frame land before the reader is built.
          requestAnimationFrame(() => {
            appStore.openSurah(surahId, ayah || 1)
          })
        }}
      >
        <canvas ref={canvasRef} className="continue-wash" aria-hidden="true" />
        <span className="continue-face">{copy}</span>
        <span ref={onInkRef} className="continue-face continue-on-ink" aria-hidden="true">
          {copy}
        </span>
      </button>
    </div>
  )
}
