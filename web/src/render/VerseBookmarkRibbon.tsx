/**
 * Verse bookmark ribbon — port of Android `VerseBookmarkRibbon.kt`.
 *
 * Idle: a playback-ink outlined tip at 0.4 — always on touch / phone widths
 * (Android), revealed by hover on a desktop pointer. Saved: the same ruby strip grown nearly the full
 * verse height. Click unfurls with a cloth wave + overshoot; unmark
 * gathers back into the tip.
 */
import {
  useEffect,
  useRef,
  useCallback,
  useState,
  type CSSProperties,
} from 'react'
import { animate, type AnimationPlaybackControls } from 'motion'
import { RETRACT, UNFURL } from '../ui/motion/easing'
import { paperSelectHaptic } from '../ui/kit/paperHaptics'

// Android VerseBookmarkRibbon.kt geometry (dp = px).
const EDGE_INSET = 8
const RIBBON_WIDTH = 11
const NUB_LENGTH = 14
const BOTTOM_GAP = 48
const NOTCH = 5.5
const WAVE_AMP = 4.5
const SETTLE_AMP = 3.2
const OVERSHOOT = 0.06
const SOLID_ALPHA = 0.92
/** Quiet affordance when just the tail is showing. */
const IDLE_NUB_ALPHA = 0.4
/** Two physical ribbons, never one overpainted strip. */
const RIBBON_GAP = 3
const VERSE_PLACE_EDGE_SHIFT = 4
/** Passive marker, quieter than the tappable ruby. */
const PLACE_RIBBON_WIDTH_RATIO = 0.72
/** Hold on a saved ribbon opens its note. */
const LONG_PRESS_MS = 450

/** Reader reserves green's screen-edge lane; Home keeps its original inset. */
export function bookmarkRibbonInset(reservePlaceLane: boolean, edgeInset: number, ribbonWidth: number): number {
  return edgeInset + (reservePlaceLane ? ribbonWidth * PLACE_RIBBON_WIDTH_RATIO + RIBBON_GAP : 0)
}

export function placeRibbonInset(reservePlaceLane: boolean, edgeInset: number): number {
  return Math.max(0, edgeInset - (reservePlaceLane ? VERSE_PLACE_EDGE_SHIFT : 0))
}
const TOP_INSET = 24
const TOP_FOLD = 3.5

type Side = 'left' | 'right'

type Props = {
  bookmarked: boolean
  /** Passive green place cloth, as on Android's continue pill. */
  placeMarked?: boolean
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
  /** Replays the green place cloth's unfurl when this changes above zero. */
  placeUnfurlSignal?: number
  /** Reader-only paired lanes: green beside ruby, not overpainted by it. */
  reservePlaceLane?: boolean
  /** Draw the empty tip at rest, as Android does (touch / phone). */
  idleTipAlways?: boolean
  /** Press and hold (saved ribbons open their note). */
  onLongPress?: () => void
  /** Geometry overrides for the same ribbon on taller non-verse sheets. */
  edgeInset?: number
  topInset?: number
  bottomGap?: number
  ariaLabel?: string
  /** Paint-only use inside a larger navigation target. */
  decorative?: boolean
  tabIndex?: number
}

function parseRibbonColor(cssColor: string): {
  r: number
  g: number
  b: number
  a: number
} {
  const hex = cssColor.trim()
  if (hex.startsWith('#') && (hex.length === 7 || hex.length === 4)) {
    if (hex.length === 4) {
      const r = parseInt(hex[1]! + hex[1]!, 16)
      const g = parseInt(hex[2]! + hex[2]!, 16)
      const b = parseInt(hex[3]! + hex[3]!, 16)
      return { r, g, b, a: 1 }
    }
    return {
      r: parseInt(hex.slice(1, 3), 16),
      g: parseInt(hex.slice(3, 5), 16),
      b: parseInt(hex.slice(5, 7), 16),
      a: 1,
    }
  }
  const rgb = hex.match(
    /^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:\s*[,/]\s*([\d.]+))?\s*\)$/,
  )
  if (rgb)
    return {
      r: Number(rgb[1]),
      g: Number(rgb[2]),
      b: Number(rgb[3]),
      a: Number(rgb[4] ?? 1),
    }
  return { r: 179, g: 18, b: 47, a: 1 }
}

export function VerseBookmarkRibbon({
  bookmarked,
  placeMarked = false,
  focused,
  hovered = false,
  side,
  chromeAlpha = 1,
  interactive = true,
  animateOnTap = true,
  onToggle,
  unfurlSignal = 0,
  placeUnfurlSignal = 0,
  reservePlaceLane = false,
  idleTipAlways = false,
  onLongPress,
  edgeInset = EDGE_INSET,
  topInset = TOP_INSET,
  bottomGap = BOTTOM_GAP,
  ariaLabel,
  decorative = false,
  tabIndex,
}: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const wrapRef = useRef<HTMLElement>(null)
  // With paired lanes the ruby is the bookmark alone; green has its own cloth.
  const marked = reservePlaceLane ? bookmarked : bookmarked || placeMarked
  const unfurl = useRef(marked ? 1 : 0)
  const placeUnfurl = useRef(placeMarked ? 1 : 0)
  const greenInkRef = useRef({ r: 31, g: 107, b: 74, a: 1 })
  const longPressed = useRef(false)
  const sway = useRef(0)
  const animating = useRef(false)
  const controlsRef = useRef<AnimationPlaybackControls[]>([])
  const ribbonInkRef = useRef({ r: 179, g: 18, b: 47, a: 1 })
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

    const h = cssH
    const mirrored = side === 'right'
    const ax = (logicalX: number) => (mirrored ? cssW - logicalX : logicalX)

    const bookmarkInset = bookmarkRibbonInset(reservePlaceLane, edgeInset, RIBBON_WIDTH)
    const outer = bookmarkInset
    const inner = bookmarkInset + RIBBON_WIDTH
    const center = bookmarkInset + RIBBON_WIDTH / 2
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

    const showingRibbon = progress > 0.02 || marked
    // Tip at rest on touch; on a desktop pointer only while hovered/focused.
    const revealTip = idleTipAlways || hovered || ribbonFocused
    let alpha: number
    if (showingRibbon && progress > 0.5) alpha = SOLID_ALPHA
    else if (showingRibbon)
      alpha = SOLID_ALPHA * (0.55 + 0.45 * Math.min(1, progress))
    else if (!revealTip) alpha = 0
    else alpha = IDLE_NUB_ALPHA

    const placeProgress = reservePlaceLane && placeMarked ? Math.max(0, placeUnfurl.current) : 0
    // Nothing to paint — skip path work so idle verses stay blank.
    if (alpha <= 0.001 && placeProgress <= 0.001) return

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
        Math.sin(wavePhase - t * Math.PI * 3.2) *
        WAVE_AMP *
        liveWave *
        tipWeight
      const flutter =
        Math.sin(t * Math.PI * 2.5 + settle * 1.2) *
        SETTLE_AMP *
        settle *
        tipWeight
      return cloth + flutter
    }

    const top = topInset + TOP_FOLD
    const bot = Math.max(topInset + NUB_LENGTH * 0.6, tipY)
    const span = Math.max(1, bot - top)
    const notchDepth = Math.min(NOTCH, span * 0.45)
    const steps = Math.min(72, Math.max(8, Math.floor(span / 2.5)))
    const { r, g, b, a } = ribbonInkRef.current

    // The passive green place cloth, in its own lane at the screen edge.
    if (placeProgress > 0.001) {
      const pInset = placeRibbonInset(reservePlaceLane, edgeInset)
      const pWidth = RIBBON_WIDTH * PLACE_RIBBON_WIDTH_RATIO
      const pTip = Math.min(fullLen * 1.08, retractedTipY + (fullLen - retractedTipY) * placeProgress)
      const pBot = Math.max(topInset + NUB_LENGTH * 0.6, pTip)
      const pSpan = Math.max(1, pBot - top)
      const pNotch = Math.min(NOTCH, pSpan * 0.45)
      const po = ax(pInset)
      const pi = ax(pInset + pWidth)
      const pc = ax(pInset + pWidth / 2)
      ctx.beginPath()
      ctx.moveTo(po, top)
      ctx.quadraticCurveTo(po, top - TOP_FOLD, pc, top - TOP_FOLD)
      ctx.quadraticCurveTo(pi, top - TOP_FOLD, pi, top)
      ctx.lineTo(pi, pBot)
      ctx.lineTo(pc, pBot - pNotch)
      ctx.lineTo(po, pBot)
      ctx.closePath()
      const gk = greenInkRef.current
      const wash = ctx.createLinearGradient(0, topInset, 0, Math.max(1, fullLen))
      wash.addColorStop(0, `rgba(${gk.r},${gk.g},${gk.b},${SOLID_ALPHA})`)
      wash.addColorStop(0.55, `rgba(${gk.r},${gk.g},${gk.b},${SOLID_ALPHA})`)
      wash.addColorStop(1, `rgba(${gk.r},${gk.g},${gk.b},${SOLID_ALPHA * 0.82})`)
      ctx.fillStyle = wash
      ctx.fill()
    }
    if (alpha <= 0.001) return

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

    // Ruby marks a bookmark; green marks a kept place. The idle affordance uses the
    // same opaque monochrome ink as playback controls.
    buildBodyPath()
    const x0 = Math.min(ax(outer), ax(inner))
    const x1 = Math.max(ax(outer), ax(inner))
    if (showingRibbon) {
      const tipWash = ctx.createLinearGradient(0, top - TOP_FOLD, 0, bot)
      tipWash.addColorStop(0, `rgba(${r},${g},${b},${alpha * a})`)
      tipWash.addColorStop(0.55, `rgba(${r},${g},${b},${alpha * a})`)
      tipWash.addColorStop(1, `rgba(${r},${g},${b},${alpha * a * 0.82})`)
      ctx.fillStyle = tipWash
      ctx.fill()
    } else {
      const ink = playbackInkRef.current
      ctx.strokeStyle = `rgba(${ink.r},${ink.g},${ink.b},${alpha})`
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
  }, [marked, hovered, ribbonFocused, side, edgeInset, topInset, bottomGap, idleTipAlways, reservePlaceLane, placeMarked])

  // Keep bookmark and playback-ink colors in sync with theme tokens.
  useEffect(() => {
    const read = () => {
      const raw = getComputedStyle(document.documentElement)
      const ribbonInk = raw
        .getPropertyValue(
          placeMarked && !bookmarked && !reservePlaceLane ? '--green-ink' : '--bookmark',
        )
        .trim()
      const green = raw.getPropertyValue('--green-ink').trim()
      if (green) greenInkRef.current = parseRibbonColor(green)
      const inkMuted = raw.getPropertyValue('--ink-muted').trim()
      if (ribbonInk) ribbonInkRef.current = parseRibbonColor(ribbonInk)
      if (inkMuted) playbackInkRef.current = parseRibbonColor(inkMuted)
      draw()
    }
    read()
    const obs = new MutationObserver(read)
    obs.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme', 'data-colors', 'style', 'class'],
    })
    return () => obs.disconnect()
  }, [draw, bookmarked, placeMarked, reservePlaceLane])

  // External bookmark changes snap (list reload); tap path animates.
  useEffect(() => {
    if (userDriven.current) return
    if (animating.current) return
    unfurl.current = marked ? 1 : 0
    sway.current = 0
    draw()
  }, [marked, draw])

  useEffect(() => {
    draw()
  }, [hovered, ribbonFocused, chromeAlpha, draw])

  // Green place cloth: snaps on external changes, unfurls on a real pause.
  useEffect(() => {
    if (!reservePlaceLane) return
    if (!placeMarked) {
      placeUnfurl.current = 0
      draw()
      return
    }
    if (placeUnfurlSignal <= 0) {
      placeUnfurl.current = 1
      draw()
      return
    }
    placeUnfurl.current = 0
    draw()
    const h = wrapRef.current?.clientHeight ?? 120
    const durationMs = Math.min(1400, Math.max(420, 280 + h * 0.55))
    let settle: AnimationPlaybackControls | null = null
    const travel = animate(0, 1 + OVERSHOOT, {
      duration: durationMs / 1000,
      ease: [...UNFURL] as [number, number, number, number],
      onUpdate: (v) => {
        placeUnfurl.current = v
        draw()
      },
      onComplete: () => {
        settle = animate(1 + OVERSHOOT, 1, {
          type: 'spring',
          stiffness: 180,
          damping: 14,
          onUpdate: (v) => {
            placeUnfurl.current = v
            draw()
          },
        })
      },
    })
    return () => {
      travel.stop()
      settle?.stop()
    }
  }, [placeMarked, placeUnfurlSignal, reservePlaceLane, draw])

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
      const ease = [...(toMarked ? UNFURL : RETRACT)] as [
        number,
        number,
        number,
        number,
      ]
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
    if (longPressed.current) {
      longPressed.current = false
      return
    }
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
  // A paint-only ribbon can sit inside the pill's single button.
  const Tag = decorative ? 'span' : 'button'

  return (
    <Tag
      ref={(node: HTMLButtonElement | HTMLSpanElement | null) => {
        wrapRef.current = node
      }}
      type={decorative ? undefined : 'button'}
      className="verse-ribbon"
      data-side={side}
      data-on={marked || unfurl.current > 0.5 ? 'true' : 'false'}
      data-focused={focused ? 'true' : 'false'}
      data-hovered={hovered || ribbonFocused ? 'true' : 'false'}
      data-idle-tip={idleTipAlways ? 'always' : undefined}
      aria-label={
        decorative
          ? undefined
          : (ariaLabel ?? (bookmarked ? 'Remove bookmark' : 'Bookmark verse'))
      }
      aria-hidden={decorative || undefined}
      aria-pressed={decorative ? undefined : bookmarked}
      tabIndex={decorative ? -1 : tabIndex}
      onClick={onClick}
      onPointerDown={onLongPress ? (event) => {
        longPressed.current = false
        if (event.pointerType === 'mouse' && event.button !== 0) return
        const id = event.pointerId
        const sx = event.clientX
        const sy = event.clientY
        const timer = window.setTimeout(() => {
          cleanup()
          if (!interactive || chromeAlpha < 0.1) return
          longPressed.current = true
          paperSelectHaptic()
          onLongPress()
        }, LONG_PRESS_MS)
        const move = (e: PointerEvent) => {
          if (e.pointerId === id && Math.hypot(e.clientX - sx, e.clientY - sy) > 8) cleanup()
        }
        const end = (e: PointerEvent) => {
          if (e.pointerId === id) cleanup()
        }
        function cleanup() {
          window.clearTimeout(timer)
          window.removeEventListener('pointermove', move, true)
          window.removeEventListener('pointerup', end, true)
          window.removeEventListener('pointercancel', end, true)
        }
        window.addEventListener('pointermove', move, true)
        window.addEventListener('pointerup', end, true)
        window.addEventListener('pointercancel', end, true)
      } : undefined}
      onContextMenu={onLongPress ? (event) => event.preventDefault() : undefined}
      onFocus={() => setRibbonFocused(true)}
      onBlur={() => setRibbonFocused(false)}
      style={style}
    >
      <canvas
        ref={canvasRef}
        className="verse-ribbon-canvas"
        aria-hidden="true"
      />
    </Tag>
  )
}
