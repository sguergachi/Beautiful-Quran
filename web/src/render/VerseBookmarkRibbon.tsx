/**
 * Verse bookmark ribbon — port of Android `VerseBookmarkRibbon.kt`.
 *
 * Idle: opaque playback-ink outlined tip hidden until the verse is hovered (or the ribbon is
 * keyboard-focused). Saved: the same ruby strip grown nearly the full
 * verse height. Click unfurls with a cloth wave + overshoot; unmark
 * gathers back into the tip.
 */
import { useEffect, useRef, useCallback, useState, type CSSProperties } from 'react'
import { animate, type AnimationPlaybackControls } from 'motion'
import { RETRACT, UNFURL } from '../ui/motion/easing'

const EDGE_INSET = 9
const RIBBON_WIDTH = 13
const NUB_LENGTH = 16
const BOTTOM_GAP = 48
const NOTCH = 6.5
const WAVE_AMP = 4.5
const SETTLE_AMP = 3.2
const OVERSHOOT = 0.06
const SOLID_ALPHA = 0.94
const TOP_INSET = 24
const TOP_FOLD = 3.5

type Side = 'left' | 'right'

type Props = {
  bookmarked: boolean
  focused: boolean
  /** Verse under the pointer — reveals the idle swallowtail tip. */
  hovered?: boolean
  side: Side
  /** 0–1; fades with reader chrome while reciting. */
  chromeAlpha?: number
  interactive?: boolean
  /** False for navigation and confirmation-only ribbons. */
  animateOnTap?: boolean
  /** Returns true when the verse is now bookmarked. */
  onToggle: () => boolean
  /** Replays the existing physical unfurl when this value changes above zero. */
  unfurlSignal?: number
  /** Geometry overrides for the same ribbon on taller non-verse sheets. */
  edgeInset?: number
  topInset?: number
  bottomGap?: number
  ariaLabel?: string
  /** Paint-only use inside a larger navigation target. */
  decorative?: boolean
  tabIndex?: number
}

function parseRuby(cssColor: string): { r: number; g: number; b: number } {
  const hex = cssColor.trim()
  if (hex.startsWith('#') && (hex.length === 7 || hex.length === 4)) {
    if (hex.length === 4) {
      const r = parseInt(hex[1]! + hex[1]!, 16)
      const g = parseInt(hex[2]! + hex[2]!, 16)
      const b = parseInt(hex[3]! + hex[3]!, 16)
      return { r, g, b }
    }
    return {
      r: parseInt(hex.slice(1, 3), 16),
      g: parseInt(hex.slice(3, 5), 16),
      b: parseInt(hex.slice(5, 7), 16),
    }
  }
  return { r: 179, g: 18, b: 47 }
}

export function VerseBookmarkRibbon({
  bookmarked,
  focused,
  hovered = false,
  side,
  chromeAlpha = 1,
  interactive = true,
  animateOnTap = true,
  onToggle,
  unfurlSignal = 0,
  edgeInset = EDGE_INSET,
  topInset = TOP_INSET,
  bottomGap = BOTTOM_GAP,
  ariaLabel,
  decorative = false,
  tabIndex,
}: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const wrapRef = useRef<HTMLButtonElement>(null)
  const unfurl = useRef(bookmarked ? 1 : 0)
  const sway = useRef(0)
  const animating = useRef(false)
  const controlsRef = useRef<AnimationPlaybackControls[]>([])
  const rubyRef = useRef({ r: 179, g: 18, b: 47 })
  const playbackInkRef = useRef({ r: 110, g: 104, b: 88 })
  const userDriven = useRef(false)
  const [ribbonFocused, setRibbonFocused] = useState(false)

  const stopControls = useCallback(() => {
    for (const c of controlsRef.current) c.stop()
    controlsRef.current = []
  }, [])

  const draw = useCallback(() => {
    const canvas = canvasRef.current
    const wrap = wrapRef.current
    if (!canvas || !wrap) return
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const cssW = wrap.clientWidth
    const cssH = wrap.clientHeight
    if (cssW <= 0 || cssH <= 0) return

    if (canvas.width !== Math.round(cssW * dpr) || canvas.height !== Math.round(cssH * dpr)) {
      canvas.width = Math.round(cssW * dpr)
      canvas.height = Math.round(cssH * dpr)
      canvas.style.width = `${cssW}px`
      canvas.style.height = `${cssH}px`
    }

    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, cssW, cssH)

    const h = cssH
    const mirrored = side === 'right'
    const ax = (logicalX: number) => (mirrored ? cssW - logicalX : logicalX)

    const outer = edgeInset
    const inner = edgeInset + RIBBON_WIDTH
    const center = edgeInset + RIBBON_WIDTH / 2
    const retractedTipY = topInset + NUB_LENGTH
    const fullLen = Math.max(retractedTipY, h - bottomGap)

    const progress = Math.max(0, unfurl.current)
    const tipY =
      progress <= 0.001
        ? retractedTipY
        : Math.min(
            fullLen * 1.08,
            retractedTipY + Math.max(1, fullLen - retractedTipY) * progress,
          )

    const showingRibbon = progress > 0.02 || bookmarked
    // Tip only while the verse is hovered (or the ribbon button is focused).
    const revealTip = hovered || ribbonFocused
    let alpha: number
    if (showingRibbon && progress > 0.5) alpha = SOLID_ALPHA
    else if (showingRibbon) alpha = SOLID_ALPHA * (0.55 + 0.45 * Math.min(1, progress))
    else if (!revealTip) alpha = 0
    else alpha = 1

    // Nothing to paint — skip path work so idle verses stay blank.
    if (alpha <= 0.001) return

    const wavePhase = progress * Math.PI * 2.4
    const liveWave =
      animating.current && progress > 0.05 && progress < 0.98
        ? (1 - progress) * 0.85 + 0.15
        : 0
    const settle = sway.current

    const lateral = (y: number) => {
      const t = Math.min(1, Math.max(0, y / Math.max(1, h)))
      const tipWeight = t * t
      const cloth =
        Math.sin(wavePhase - t * Math.PI * 3.2) * WAVE_AMP * liveWave * tipWeight
      const flutter =
        Math.sin(t * Math.PI * 2.5 + settle * 1.2) * SETTLE_AMP * settle * tipWeight
      return cloth + flutter
    }

    const top = topInset + TOP_FOLD
    const bot = Math.max(topInset + NUB_LENGTH * 0.6, tipY)
    const span = Math.max(1, bot - top)
    const notchDepth = Math.min(NOTCH, span * 0.42)
    const steps = Math.min(72, Math.max(8, Math.floor(span / 2.5)))
    const { r, g, b } = rubyRef.current

    const buildBodyPath = () => {
      ctx.beginPath()
      // Soft rounded top — ribbon folded over the page edge
      const o0 = ax(outer + lateral(top))
      const i0 = ax(inner + lateral(top))
      const c0 = ax(center)
      ctx.moveTo(o0, top)
      ctx.quadraticCurveTo(o0, top - TOP_FOLD, c0, top - TOP_FOLD)
      ctx.quadraticCurveTo(i0, top - TOP_FOLD, i0, top)

      // Inner edge down
      for (let i = 1; i <= steps; i++) {
        const y = top + span * (i / steps)
        ctx.lineTo(ax(inner + lateral(y)), y)
      }

      // Swallowtail: upward V cut (two soft lobes)
      const yBot = bot
      const yNotch = bot - notchDepth
      const xInnerBot = ax(inner + lateral(yBot))
      const xOuterBot = ax(outer + lateral(yBot))
      const xNotch = ax(center + lateral(yNotch))
      ctx.quadraticCurveTo(xInnerBot, yBot, xNotch, yNotch)
      ctx.quadraticCurveTo(xOuterBot, yBot, ax(outer + lateral(yBot)), yBot)

      // Outer edge up
      for (let i = steps - 1; i >= 0; i--) {
        const y = top + span * (i / steps)
        ctx.lineTo(ax(outer + lateral(y)), y)
      }
      ctx.closePath()
    }

    // Ruby fill belongs only to a saved mark. The idle affordance uses the
    // same opaque monochrome ink as playback controls.
    buildBodyPath()
    const x0 = Math.min(ax(outer), ax(inner))
    const x1 = Math.max(ax(outer), ax(inner))
    if (showingRibbon) {
      const tipWash = ctx.createLinearGradient(0, top - TOP_FOLD, 0, bot)
      tipWash.addColorStop(0, `rgba(${r},${g},${b},${alpha})`)
      tipWash.addColorStop(0.55, `rgba(${r},${g},${b},${alpha})`)
      tipWash.addColorStop(1, `rgba(${r},${g},${b},${alpha * 0.82})`)
      ctx.fillStyle = tipWash
      ctx.fill()
    } else {
      const ink = playbackInkRef.current
      ctx.strokeStyle = `rgb(${ink.r},${ink.g},${ink.b})`
      ctx.lineWidth = 1.25
      ctx.lineJoin = 'round'
      ctx.lineCap = 'round'
      ctx.stroke()
    }

    // Soft weave
    if (showingRibbon && span > NUB_LENGTH && alpha > 0.3) {
      ctx.save()
      buildBodyPath()
      ctx.clip()
      ctx.strokeStyle = `rgba(255,255,255,${0.08 * alpha})`
      ctx.lineWidth = 0.7
      for (let y = top - TOP_FOLD; y < bot; y += 4.5) {
        ctx.beginPath()
        ctx.moveTo(x0, y)
        ctx.lineTo(x1, y + 3)
        ctx.stroke()
      }
      ctx.restore()
    }
  }, [bookmarked, hovered, ribbonFocused, side, edgeInset, topInset, bottomGap])

  // Keep bookmark and playback-ink colors in sync with theme tokens.
  useEffect(() => {
    const read = () => {
      const raw = getComputedStyle(document.documentElement)
      const bookmark = raw.getPropertyValue('--bookmark').trim()
      const inkMuted = raw.getPropertyValue('--ink-muted').trim()
      if (bookmark) rubyRef.current = parseRuby(bookmark)
      if (inkMuted) playbackInkRef.current = parseRuby(inkMuted)
      draw()
    }
    read()
    const obs = new MutationObserver(read)
    obs.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme', 'style', 'class'],
    })
    return () => obs.disconnect()
  }, [draw])

  // External bookmark changes snap (list reload); tap path animates.
  useEffect(() => {
    if (userDriven.current) return
    if (animating.current) return
    unfurl.current = bookmarked ? 1 : 0
    sway.current = 0
    draw()
  }, [bookmarked, draw])

  useEffect(() => {
    draw()
  }, [hovered, ribbonFocused, chromeAlpha, draw])

  // Resize observer — ribbon height tracks the ayah block.
  useEffect(() => {
    const wrap = wrapRef.current
    if (!wrap) return
    const ro = new ResizeObserver(() => draw())
    ro.observe(wrap)
    return () => ro.disconnect()
  }, [draw])

  const runAnimation = useCallback(
    (toMarked: boolean) => {
      stopControls()
      animating.current = true
      userDriven.current = true
      const wrap = wrapRef.current
      const h = wrap?.clientHeight ?? 120
      const durationMs = toMarked
        ? Math.min(1400, Math.max(420, 280 + h * 0.55))
        : Math.min(900, Math.max(320, 220 + h * 0.4))

      if (toMarked) {
        unfurl.current = 0
        sway.current = 0
      }

      const from = unfurl.current
      const overshootTarget = toMarked ? 1 + OVERSHOOT : 0
      const ease = [...(toMarked ? UNFURL : RETRACT)] as [number, number, number, number]
      let flutterArmed = false

      const travel = animate(0, 1, {
        duration: durationMs / 1000,
        ease,
        onUpdate: (t) => {
          unfurl.current = from + (overshootTarget - from) * t
          if (toMarked && t >= 0.78 && !flutterArmed) {
            flutterArmed = true
            sway.current = 1
          }
          if (!toMarked && t < 0.2) {
            sway.current = 0.35 * (1 - t / 0.2)
          }
          draw()
        },
        onComplete: () => {
          if (!toMarked) {
            unfurl.current = 0
            sway.current = 0
            animating.current = false
            userDriven.current = false
            draw()
            return
          }
          unfurl.current = 1 + OVERSHOOT
          const settleUnfurl = animate(unfurl.current, 1, {
            type: 'spring',
            stiffness: 180,
            damping: 14,
            onUpdate: (v) => {
              unfurl.current = v
              draw()
            },
          })
          const settleSway = animate(sway.current, 0, {
            type: 'spring',
            stiffness: 220,
            damping: 18,
            onUpdate: (v) => {
              sway.current = v
              draw()
            },
            onComplete: () => {
              unfurl.current = 1
              sway.current = 0
              animating.current = false
              userDriven.current = false
              draw()
            },
          })
          controlsRef.current = [settleUnfurl, settleSway]
        },
      })
      controlsRef.current = [travel]
    },
    [draw, stopControls],
  )

  useEffect(() => {
    if (unfurlSignal <= 0 || !bookmarked) return
    const frame = window.requestAnimationFrame(() => runAnimation(true))
    return () => window.cancelAnimationFrame(frame)
  }, [bookmarked, runAnimation, unfurlSignal])

  useEffect(() => () => stopControls(), [stopControls])

  const onClick = () => {
    if (!interactive || chromeAlpha < 0.1) return
    if (!animateOnTap) {
      stopControls()
      unfurl.current = 1
      sway.current = 0
      animating.current = false
      userDriven.current = false
      draw()
      onToggle()
      return
    }
    const nowMarked = onToggle()
    runAnimation(nowMarked)
  }

  const style: CSSProperties = {
    opacity: chromeAlpha,
    pointerEvents: interactive && chromeAlpha >= 0.1 ? 'auto' : 'none',
  }

  return (
    <button
      ref={wrapRef}
      type="button"
      className="verse-ribbon"
      data-side={side}
      data-on={bookmarked || unfurl.current > 0.5 ? 'true' : 'false'}
      data-focused={focused ? 'true' : 'false'}
      data-hovered={hovered || ribbonFocused ? 'true' : 'false'}
      aria-label={
        decorative
          ? undefined
          : ariaLabel ?? (bookmarked ? 'Remove bookmark' : 'Bookmark verse')
      }
      aria-hidden={decorative || undefined}
      aria-pressed={decorative ? undefined : bookmarked}
      tabIndex={decorative ? -1 : tabIndex}
      onClick={onClick}
      onFocus={() => setRibbonFocused(true)}
      onBlur={() => setRibbonFocused(false)}
      style={style}
    >
      <canvas ref={canvasRef} className="verse-ribbon-canvas" aria-hidden="true" />
    </button>
  )
}
