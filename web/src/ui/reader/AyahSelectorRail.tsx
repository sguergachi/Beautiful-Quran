import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type CSSProperties,
  type MutableRefObject,
  type PointerEvent as ReactPointerEvent,
} from 'react'
import { animate, type AnimationPlaybackControls } from 'motion'
import {
  collapsedRailHitHeightPx,
  dialDeltaFromPointerDy,
  dialFromTickY,
  dialOnCollapsedActivate,
  focusRadiusForHeight,
  MOBILE_RAIL_MEDIA,
  railAnchorY,
  railCollapsedBarRect,
  railExpandedLayout,
  railTickBarRect,
  railTickLabelX,
  rubberBandDialPosition,
  symbolicAyahBarCount,
  tickFocus,
  tickLength,
  type RailGrowFrom,
} from './ayahRailMath'
import {
  collapsedAyahBarIndex,
  isMajorHapticAyah,
  RAIL_COMMIT_GRACE_MS,
  railMarkPieces,
  wheelCoast,
  wheelCoastAt,
  type RailMarks,
} from './ayahRailMarks'
import './ayahRail.css'

/**
 * Android `AyahSelectorRail` springs, converted for Motion:
 * damping = dampingRatio * 2 * sqrt(stiffness) (mass = 1).
 * Expand: dampingRatio 0.85, stiffness 340. Collapse: 1.0 / 200.
 * Settle: DampingRatioLowBouncy (0.75) / StiffnessMediumLow (400).
 */
const EXPAND_SPRING = { type: 'spring' as const, stiffness: 340, damping: 31.3 }
const COLLAPSE_SPRING = { type: 'spring' as const, stiffness: 200, damping: 28.3 }
const SETTLE_SPRING = { type: 'spring' as const, stiffness: 400, damping: 30 }

/** Android `AyahRailTickSpacing` (17dp). */
const TICK_SPACING_PX = 17
const MIN_BAR_LEN = 8
const MIN_THICKNESS = 2
const MAX_THICKNESS = 4
const VERTICAL_FADE_PX = 82
const COLLAPSED_BAR_H = 1.5
const COLLAPSED_BAR_W = 10
/** Android type sizes (sp → px): numbers 13 / 15 selected, marks off 10.5. */
const NUMBER_PX = 13
const SELECTED_NUMBER_PX = 15
const MARK_PX = 10.5
/** Clean-paper wash under the open wheel: solid, then a smoothstep feather. */
const WASH_SOLID_PX = 125
const WASH_PX = 300
const WASH_STEPS = 10
const WASH_MS = 240
/** Pointer travel before a collapsed-stack press counts as a drag. */
const TOUCH_SLOP_PX = 8

export interface AyahSelectorRailHandle {
  schedulePaint: () => void
}

type Props = {
  ayahCount: number
  /** Reading / recitation ayah the collapsed stack tracks. */
  currentAyah: number
  /** Continuous reading position (1…ayahCount), read live at paint time. */
  currentPositionRef?: MutableRefObject<number> | null
  /** Ayah numbers bookmarked in this surah — ruby ticks. */
  bookmarkedAyahs?: ReadonlySet<number>
  /** Green reading place parked at the end of the previous visit. */
  placeAyah?: number | null
  /** Wayfinding marks trailing the numbers: p. N, ۞, JUZ N, ۩. */
  marks?: RailMarks | null
  side: 'left' | 'right'
  receded: boolean
  /** Commit after the settle + grace countdown (or a tap away). */
  onJump: (ayah: number) => void
  /** Fires when the magnification wheel opens or collapses. */
  onExpandedChange?: (expanded: boolean) => void
  onCollapsedCenterY?: (centerYInSheet: number) => void
}

function readCssVar(el: HTMLElement, name: string, fallback: string): string {
  const v = getComputedStyle(el).getPropertyValue(name).trim()
  return v || fallback
}

function withAlpha(color: string, alpha: number): string {
  const a = Math.min(1, Math.max(0, alpha))
  if (color.startsWith('#')) {
    let hex = color.slice(1)
    if (hex.length === 3) hex = hex.split('').map((c) => c + c).join('')
    if (hex.length === 6) {
      const r = parseInt(hex.slice(0, 2), 16)
      const g = parseInt(hex.slice(2, 4), 16)
      const b = parseInt(hex.slice(4, 6), 16)
      return `rgba(${r}, ${g}, ${b}, ${a})`
    }
  }
  return `color-mix(in srgb, ${color} ${Math.round(a * 100)}%, transparent)`
}

function railGrowFrom(): RailGrowFrom {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return 'center'
  return window.matchMedia(MOBILE_RAIL_MEDIA).matches ? 'edge' : 'center'
}

function reducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}

/** CLOCK_TICK per verse, CONTEXT_CLICK on first / last / fives and on commit. */
function railHaptic(strong: boolean): void {
  try {
    if (typeof navigator === 'undefined' || typeof navigator.vibrate !== 'function') return
    if (reducedMotion()) return
    navigator.vibrate(strong ? 10 : 4)
  } catch {
    /* capability / permission */
  }
}

type ReleaseRun = { cancelled: boolean; raf: number; controls: AnimationPlaybackControls | null }

/**
 * Ayah scrub rail — Android `AyahSelectorRail`.
 *
 * Collapsed: a symbolic dash stack flush with the edge (green = reading
 * place, ruby = bookmark). A tap opens the wheel; a vertical drag opens and
 * scrubs it in one gesture. The open wheel carries every verse number plus
 * the page / ۞ / JUZ / ۩ marks over a clean-paper wash, and the page's verses
 * fade back. On release the wheel coasts, settles onto a verse, and a gold
 * underline drains for 1.5s before the jump commits; touching the wheel
 * again hands it back. Tapping anywhere else commits at once.
 *
 * Desktop keeps hover-to-open with a centred midline (web-only affordance).
 */
export const AyahSelectorRail = forwardRef<AyahSelectorRailHandle, Props>(function AyahSelectorRail(
  {
    ayahCount,
    currentAyah,
    currentPositionRef,
    bookmarkedAyahs,
    placeAyah,
    marks,
    side,
    receded,
    onJump,
    onExpandedChange,
    onCollapsedCenterY,
  }: Props,
  ref,
) {
  const rootRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const onExpandedChangeRef = useRef(onExpandedChange)
  onExpandedChangeRef.current = onExpandedChange
  const onCollapsedCenterYRef = useRef(onCollapsedCenterY)
  onCollapsedCenterYRef.current = onCollapsedCenterY
  const onJumpRef = useRef(onJump)
  onJumpRef.current = onJump
  const currentAyahRef = useRef(currentAyah)
  currentAyahRef.current = currentAyah

  const dialRef = useRef(currentPositionRef?.current ?? currentAyah)
  const expandRef = useRef(0)
  const washRef = useRef(0)
  const commitProgressRef = useRef(0)
  const expandTargetRef = useRef(0)
  const expandControlsRef = useRef<AnimationPlaybackControls | null>(null)
  const washControlsRef = useRef<AnimationPlaybackControls | null>(null)
  const releaseRef = useRef<ReleaseRun | null>(null)
  const pendingCommitRef = useRef<number | null>(null)
  const lastHapticAyahRef = useRef(currentAyah)
  const rafRef = useRef(0)

  // Gesture state.
  const hoverRef = useRef(false)
  const pointerRef = useRef<{
    id: number
    type: string
    downX: number
    downY: number
    lastY: number
    /** 'wait' until a collapsed press resolves; 'scrub' while moving the dial. */
    mode: 'wait' | 'scrub'
    openedHere: boolean
    dragged: boolean
    rawDial: number
    samples: { t: number; y: number }[]
  } | null>(null)
  /** Mouse hover-scrub: pointer movement nudges the dial by tick spacing. */
  const hoverLastYRef = useRef<number | null>(null)

  const [ariaAyah, setAriaAyah] = useState(currentAyah)
  /** Full-height hit zone (and the tap-away catcher) while the wheel shows. */
  const [hitExpanded, setHitExpanded] = useState(false)
  /** Keeps the tap-away catcher until the dismissing tap has fully ended. */
  const [catcherHeld, setCatcherHeld] = useState(false)
  const collapsedHitHeight = collapsedRailHitHeightPx(ayahCount)

  const readingPos = () => currentPositionRef?.current ?? currentAyah
  const clampAyah = (v: number) => Math.min(ayahCount, Math.max(1, Math.round(v)))

  useEffect(() => {
    if (expandTargetRef.current < 0.5) setAriaAyah(currentAyah)
  }, [currentAyah])

  const paint = useCallback(() => {
    const canvas = canvasRef.current
    const root = rootRef.current
    if (!canvas || !root) return

    const growFrom = railGrowFrom()
    root.dataset.flush = growFrom === 'edge' ? 'true' : 'false'
    const dpr = window.devicePixelRatio || 1
    const railW = root.clientWidth
    const cssH = root.clientHeight
    if (railW <= 0 || cssH <= 0) return
    const cssW = Math.max(railW, WASH_PX)
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

    const ink = readCssVar(root, '--ink', '#1c1b18')
    const gold = readCssVar(root, '--gold', '#c9a227')
    const ruby = readCssVar(root, '--bookmark', '#b3122f')
    const green = readCssVar(root, '--accent', '#0e5c4a')
    const paper = readCssVar(root, '--reading-paper', readCssVar(root, '--paper', '#faf3e8'))
    const serif = readCssVar(root, '--font-body', '"EB Garamond", serif')
    const arabic = readCssVar(root, '--font-arabic', 'serif')

    // Clean paper under the open wheel's labels; only this edge fades.
    const wash = washRef.current
    if (wash > 0.004) {
      const edgeX = side === 'left' ? 0 : cssW
      const farX = side === 'left' ? WASH_PX : cssW - WASH_PX
      const gradient = ctx.createLinearGradient(edgeX, 0, farX, 0)
      gradient.addColorStop(0, withAlpha(paper, wash))
      const solid = WASH_SOLID_PX / WASH_PX
      for (let i = 0; i <= WASH_STEPS; i++) {
        const t = i / WASH_STEPS
        const eased = 1 - t * t * (3 - 2 * t)
        gradient.addColorStop(solid + (1 - solid) * t, withAlpha(paper, wash * eased))
      }
      ctx.fillStyle = gradient
      ctx.fillRect(Math.min(edgeX, farX), 0, WASH_PX, cssH)
    }

    // Bars and labels are laid out in the rail's own box; the canvas reaches
    // further toward the page so the marks and wash are not clipped.
    const originX = side === 'left' ? 0 : cssW - railW
    ctx.translate(originX, 0)

    const expand = expandRef.current
    const collapsedAlpha = 1 - expand
    const centerY = cssH * 0.5
    const collapsedCount = symbolicAyahBarCount(ayahCount)
    const collapsedSpacing = Math.min(8, Math.max(4, 72 / collapsedCount))
    const collapsedStep = COLLAPSED_BAR_H + collapsedSpacing
    const readPos = Math.min(ayahCount, Math.max(1, readingPos()))
    const readProgress = ayahCount > 1 ? (readPos - 1) / (ayahCount - 1) : 0
    const collapsedActive = readProgress * (collapsedCount - 1)
    const place = placeAyah != null && placeAyah >= 1 && placeAyah <= ayahCount ? placeAyah : null
    const placeBar = place != null ? collapsedAyahBarIndex(ayahCount, place) : -1

    if (collapsedAlpha > 0.01) {
      const halfSpan = Math.max(1, (collapsedCount - 1) / 2)
      const bookmarkedBars = new Set<number>()
      if (bookmarkedAyahs) {
        for (const ayah of bookmarkedAyahs) {
          if (ayah < 1 || ayah > ayahCount) continue
          bookmarkedBars.add(collapsedAyahBarIndex(ayahCount, ayah))
        }
      }
      for (let index = 0; index < collapsedCount; index++) {
        const relative = index - (collapsedCount - 1) / 2
        const y = centerY + relative * collapsedStep
        const exit = Math.min(1, Math.max(0, collapsedAlpha * 1.35 - (Math.abs(relative) / halfSpan) * 0.35))
        if (exit <= 0.01) continue
        const focus = Math.min(1, Math.max(0, 1 - Math.abs(index - collapsedActive)))
        const barW = COLLAPSED_BAR_W * (0.7 + 0.45 * focus)
        const rect = railCollapsedBarRect(railW, side, growFrom, barW, COLLAPSED_BAR_H, exit)
        ctx.beginPath()
        roundRect(ctx, rect.x, y - COLLAPSED_BAR_H / 2, rect.width, COLLAPSED_BAR_H, COLLAPSED_BAR_H)
        ctx.fillStyle =
          index === placeBar
            ? withAlpha(green, 0.92 * exit)
            : bookmarkedBars.has(index)
              ? withAlpha(ruby, (0.55 + 0.35 * focus) * exit)
              : withAlpha(ink, (0.18 + 0.72 * focus) * exit)
        ctx.fill()
      }
    }

    if (expand > 0.01) {
      const dial = dialRef.current
      const selectedAyah = clampAyah(dial)
      const focusRadius = focusRadiusForHeight(cssH, TICK_SPACING_PX)
      const restingY = railAnchorY(dial, cssH, ayahCount, TICK_SPACING_PX)
      // The focal tick blooms out of the collapsed stack's dark bar.
      const collapsedActiveY = centerY + (collapsedActive - (collapsedCount - 1) / 2) * collapsedStep
      const anchorY = collapsedActiveY + (restingY - collapsedActiveY) * expand
      const first = Math.max(1, Math.floor(dial - anchorY / TICK_SPACING_PX))
      const last = Math.min(ayahCount, Math.ceil(dial + (cssH - anchorY) / TICK_SPACING_PX + 1))

      ctx.font = `700 ${SELECTED_NUMBER_PX}px ${serif}`
      const labelWidth = ctx.measureText(String(ayahCount)).width
      const layout = railExpandedLayout(railW, side, labelWidth, growFrom)
      const { maxBarLen, majorBonus } = layout
      const dir = side === 'left' ? 1 : -1
      const hold = commitProgressRef.current
      ctx.textBaseline = 'alphabetic'

      for (let ayah = first; ayah <= last; ayah++) {
        const offset = ayah - dial
        const y = anchorY + offset * TICK_SPACING_PX
        const distance = Math.min(1, Math.abs(offset) / focusRadius)
        const focus = tickFocus(offset, focusRadius)
        // Only the first and last verse run long (the haptic still marks fives).
        const major = ayah === 1 || ayah === ayahCount
        const arrival = Math.min(1, Math.max(0, (expand - distance * 0.3) / 0.7))
        if (arrival <= 0.01) continue
        const edgeFade = Math.min(1, Math.max(0, Math.min(y, cssH - y) / VERTICAL_FADE_PX))
        const grow = arrival * (0.35 + 0.65 * Math.max(edgeFade, focus * focus))
        const length = tickLength(focus, major, MIN_BAR_LEN, maxBarLen, majorBonus) * grow
        const thickness = MIN_THICKNESS + (MAX_THICKNESS - MIN_THICKNESS) * focus
        const isSelected = ayah === selectedAyah
        const isPlace = ayah === place
        const isBookmarked = bookmarkedAyahs?.has(ayah) ?? false
        const bar = railTickBarRect(layout, side, length, thickness)

        // Gold = dial focus; green = reading place; ruby = saved verse.
        ctx.beginPath()
        roundRect(ctx, bar.x, y - thickness / 2, bar.width, thickness, thickness)
        ctx.fillStyle = isSelected
          ? withAlpha(gold, 0.96 * arrival)
          : isPlace
            ? withAlpha(green, 0.92 * arrival * edgeFade)
            : isBookmarked
              ? withAlpha(ruby, (0.55 + 0.35 * focus) * arrival * edgeFade)
              : withAlpha(ink, (0.1 + 0.62 * focus) * arrival * edgeFade)
        ctx.fill()

        if (isSelected && hold > 0) {
          // Grace countdown: ink fills the gold bar from the edge cap.
          const holdW = bar.width * hold
          ctx.beginPath()
          roundRect(ctx, side === 'left' ? bar.x : bar.x + bar.width - holdW, y - thickness / 2, holdW, thickness, thickness)
          ctx.fillStyle = withAlpha(ink, 0.82 * arrival)
          ctx.fill()
        }

        // Every verse is numbered; weight follows the finger.
        const numberAlpha = (0.3 + 0.5 * focus) * arrival * edgeFade
        const weight = isSelected || Math.abs(offset) <= 1 ? 700 : Math.abs(offset) <= 4 ? 600 : 500
        const size = isSelected ? SELECTED_NUMBER_PX : NUMBER_PX
        ctx.font = `${weight} ${size}px ${serif}`
        ctx.textAlign = dir === 1 ? 'left' : 'right'
        ctx.fillStyle = isSelected
          ? withAlpha(gold, 0.95 * arrival)
          : isBookmarked
            ? withAlpha(ruby, (0.4 + 0.5 * focus) * arrival * edgeFade)
            : isPlace
              ? withAlpha(green, (0.4 + 0.5 * focus) * arrival * edgeFade)
              : withAlpha(ink, numberAlpha)
        const label = String(ayah)
        const numberX = railTickLabelX(layout, side, length)
        ctx.fillText(label, numberX, y + size * 0.34)

        const pieces = railMarkPieces(marks, ayah)
        if (pieces.length === 0) continue
        // The marks trail the number as one faint aside; even ink gaps.
        let trail = ctx.measureText(label).width + 7
        const markInk = isSelected ? withAlpha(gold, 0.85 * arrival) : withAlpha(ink, numberAlpha * 0.78)
        ctx.textAlign = 'left'
        ctx.fillStyle = markInk
        for (const piece of pieces) {
          const markSize = piece.kind === 'sign' ? MARK_PX * 1.1 : piece.kind === 'juz' ? MARK_PX * 0.92 : MARK_PX
          ctx.font =
            piece.kind === 'sign'
              ? `400 ${markSize}px ${arabic}`
              : `${piece.kind === 'juz' ? 600 : 500} ${markSize}px ${serif}`
          setLetterSpacing(ctx, piece.kind === 'juz' ? `${(MARK_PX * 0.92 * 0.08).toFixed(2)}px` : '0px')
          const m = ctx.measureText(piece.text)
          const inkLeft = m.actualBoundingBoxLeft
          const inkW = inkLeft + m.actualBoundingBoxRight
          const start = trail + 6
          const inkStartX = dir === 1 ? numberX + start : numberX - start - inkW
          const baseline =
            piece.kind === 'sign'
              ? y + (m.actualBoundingBoxAscent - m.actualBoundingBoxDescent) / 2
              : y + markSize * 0.34
          ctx.fillText(piece.text, inkStartX + inkLeft, baseline)
          trail = start + inkW
        }
        setLetterSpacing(ctx, '0px')
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ayahCount, bookmarkedAyahs, side, placeAyah, marks])

  const schedulePaint = useCallback(() => {
    if (rafRef.current) return
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = 0
      // Mirror the reading position only while the wheel is fully hidden.
      if (
        expandTargetRef.current < 0.01 &&
        expandRef.current < 0.01 &&
        pointerRef.current == null &&
        releaseRef.current == null &&
        !hoverRef.current
      ) {
        dialRef.current = readingPos()
        lastHapticAyahRef.current = clampAyah(currentAyahRef.current)
      }
      paint()
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paint])

  useEffect(() => {
    schedulePaint()
  }, [ayahCount, bookmarkedAyahs, currentAyah, placeAyah, marks, schedulePaint])

  const cancelRelease = () => {
    const run = releaseRef.current
    if (!run) return
    run.cancelled = true
    if (run.raf) cancelAnimationFrame(run.raf)
    run.controls?.stop()
    releaseRef.current = null
  }

  useEffect(() => {
    const onResize = () => schedulePaint()
    window.addEventListener('resize', onResize)
    const mq = window.matchMedia(MOBILE_RAIL_MEDIA)
    mq.addEventListener('change', onResize)
    return () => {
      window.removeEventListener('resize', onResize)
      mq.removeEventListener('change', onResize)
      if (rafRef.current) cancelAnimationFrame(rafRef.current)
      rafRef.current = 0
      expandControlsRef.current?.stop()
      washControlsRef.current?.stop()
      cancelRelease()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schedulePaint])

  const reportCollapsedCenter = useCallback(() => {
    const root = rootRef.current
    const report = onCollapsedCenterYRef.current
    if (!root || !report) return
    const sheet = root.closest('.sheet')
    if (!(sheet instanceof HTMLElement)) return
    const sheetRect = sheet.getBoundingClientRect()
    const rootRect = root.getBoundingClientRect()
    report(rootRect.top + rootRect.height / 2 - sheetRect.top)
  }, [])

  useEffect(() => {
    reportCollapsedCenter()
    const root = rootRef.current
    if (!root) return
    const ro = new ResizeObserver(() => reportCollapsedCenter())
    ro.observe(root)
    window.addEventListener('resize', reportCollapsedCenter)
    return () => {
      ro.disconnect()
      window.removeEventListener('resize', reportCollapsedCenter)
    }
  }, [reportCollapsedCenter, ayahCount, side])

  const setExpanded = (open: boolean) => {
    const target = open ? 1 : 0
    const wasOpen = expandTargetRef.current >= 0.5
    expandTargetRef.current = target
    expandControlsRef.current?.stop()
    expandControlsRef.current = null
    washControlsRef.current?.stop()
    washControlsRef.current = null
    if (open) setHitExpanded(true)
    if (open !== wasOpen) onExpandedChangeRef.current?.(open)

    const finishClose = () => {
      commitProgressRef.current = 0
      setHitExpanded(false)
    }
    if (reducedMotion()) {
      expandRef.current = target
      washRef.current = target
      if (!open) finishClose()
      schedulePaint()
      return
    }
    washControlsRef.current = animate(washRef.current, target, {
      duration: WASH_MS / 1000,
      ease: [0.4, 0, 0.2, 1],
      onUpdate: (v) => {
        washRef.current = v
        schedulePaint()
      },
    })
    if (Math.abs(expandRef.current - target) < 0.001) {
      expandRef.current = target
      if (!open) finishClose()
      schedulePaint()
      return
    }
    expandControlsRef.current = animate(expandRef.current, target, {
      ...(open ? EXPAND_SPRING : COLLAPSE_SPRING),
      onUpdate: (value) => {
        expandRef.current = value
        schedulePaint()
      },
      onComplete: () => {
        expandRef.current = target
        expandControlsRef.current = null
        if (!open) finishClose()
        schedulePaint()
      },
    })
  }

  /** Open the wheel on the reading position (it blooms from the dark bar). */
  const openWheel = () => {
    lastHapticAyahRef.current = clampAyah(currentAyahRef.current)
    dialRef.current = dialOnCollapsedActivate(readingPos(), ayahCount)
    setExpanded(true)
  }

  const setDial = (next: number) => {
    dialRef.current = next
    const ayah = clampAyah(next)
    if (expandTargetRef.current >= 0.5 && ayah !== lastHapticAyahRef.current) {
      railHaptic(isMajorHapticAyah(ayah, ayahCount))
      lastHapticAyahRef.current = ayah
    }
    setAriaAyah(ayah)
    schedulePaint()
  }

  /** Jump (when it moves the reader) and collapse anchored on [target]. */
  const commitSelection = (target: number) => {
    cancelRelease()
    pendingCommitRef.current = null
    if (target !== clampAyah(currentAyahRef.current)) onJumpRef.current(target)
    railHaptic(true)
    hoverRef.current = false
    hoverLastYRef.current = null
    setAriaAyah(target)
    setExpanded(false)
  }

  /** Settle (coast + spring onto a verse), then the 1.5s grace, then commit. */
  const scheduleReleaseCommit = (start: number, velocity: number) => {
    cancelRelease()
    commitProgressRef.current = 0
    const run: ReleaseRun = { cancelled: false, raf: 0, controls: null }
    releaseRef.current = run
    const s = Math.min(ayahCount, Math.max(1, start))
    const coast = reducedMotion() ? { end: s, durationMs: 0 } : wheelCoast(s, velocity, ayahCount)
    const flung = coast.durationMs > 0

    const grace = (target: number) => {
      pendingCommitRef.current = target
      lastHapticAyahRef.current = target
      const t0 = performance.now()
      const step = () => {
        if (run.cancelled) return
        const p = Math.min(1, (performance.now() - t0) / RAIL_COMMIT_GRACE_MS)
        commitProgressRef.current = p
        schedulePaint()
        if (p < 1) run.raf = requestAnimationFrame(step)
        else commitSelection(target)
      }
      run.raf = requestAnimationFrame(step)
    }
    const settle = () => {
      const target = clampAyah(dialRef.current)
      if (reducedMotion()) {
        setDial(target)
        grace(target)
        return
      }
      run.controls = animate(dialRef.current, target, {
        ...SETTLE_SPRING,
        velocity: flung ? 0 : velocity,
        onUpdate: (v) => {
          if (!run.cancelled) setDial(v)
        },
        onComplete: () => {
          if (run.cancelled) return
          run.controls = null
          setDial(target)
          grace(target)
        },
      })
    }
    if (!flung) {
      settle()
      return
    }
    const t0 = performance.now()
    const coastStep = () => {
      if (run.cancelled) return
      const t = Math.min(coast.durationMs, performance.now() - t0)
      setDial(Math.min(ayahCount, Math.max(1, wheelCoastAt(s, velocity, t))))
      if (t < coast.durationMs) run.raf = requestAnimationFrame(coastStep)
      else settle()
    }
    run.raf = requestAnimationFrame(coastStep)
  }

  /** Tap away / Escape: take the reader to the chosen verse now. */
  const dismiss = () => {
    if (expandTargetRef.current < 0.5) return
    const target = pendingCommitRef.current ?? clampAyah(dialRef.current)
    pointerRef.current = null
    commitSelection(target)
  }

  useImperativeHandle(ref, () => ({ schedulePaint }), [schedulePaint])

  useEffect(() => {
    if (!hitExpanded) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        dismiss()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hitExpanded])

  const tickUnderPointer = (clientY: number) => {
    const root = rootRef.current
    if (!root) return dialRef.current
    const rect = root.getBoundingClientRect()
    const dial = dialRef.current
    const anchorY = railAnchorY(dial, rect.height, ayahCount, TICK_SPACING_PX)
    return Math.min(ayahCount, Math.max(1, dialFromTickY(clientY - rect.top, dial, anchorY, TICK_SPACING_PX)))
  }

  // —— Mouse hover (desktop web affordance) ——
  const onPointerEnter = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (receded || e.pointerType !== 'mouse' || pointerRef.current) return
    hoverRef.current = true
    hoverLastYRef.current = e.clientY
    if (expandTargetRef.current < 0.5) openWheel()
  }

  const onPointerLeave = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (e.pointerType !== 'mouse') return
    hoverRef.current = false
    hoverLastYRef.current = null
    // A pending release finishes its countdown; a plain hover just closes.
    if (pointerRef.current == null && releaseRef.current == null && expandTargetRef.current >= 0.5) {
      setAriaAyah(clampAyah(currentAyahRef.current))
      setExpanded(false)
    }
  }

  const onPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (receded) return
    if (e.pointerType === 'mouse' && e.button !== 0) return
    e.preventDefault()
    e.currentTarget.setPointerCapture(e.pointerId)
    const open = expandTargetRef.current >= 0.5
    pointerRef.current = {
      id: e.pointerId,
      type: e.pointerType,
      downX: e.clientX,
      downY: e.clientY,
      lastY: e.clientY,
      mode: open ? 'scrub' : 'wait',
      openedHere: !open,
      dragged: false,
      rawDial: dialRef.current,
      samples: [{ t: e.timeStamp, y: e.clientY }],
    }
    if (open) {
      // Touching the wheel again hands it back for edits.
      cancelRelease()
      pendingCommitRef.current = null
      commitProgressRef.current = 0
      schedulePaint()
    } else if (e.pointerType === 'mouse') {
      openWheel()
      pointerRef.current.mode = 'scrub'
      pointerRef.current.rawDial = dialRef.current
    }
  }

  const onPointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (receded) return
    const p = pointerRef.current
    if (!p || p.id !== e.pointerId) {
      // Mouse hover scrub on the open wheel.
      if (hoverRef.current && e.pointerType === 'mouse' && releaseRef.current == null) {
        const last = hoverLastYRef.current
        hoverLastYRef.current = e.clientY
        if (last != null && expandTargetRef.current >= 0.5) {
          setDial(rubberBandDialPosition(dialRef.current + dialDeltaFromPointerDy(e.clientY - last, TICK_SPACING_PX), 1, ayahCount))
        }
      }
      return
    }
    if (p.mode === 'wait') {
      const dx = e.clientX - p.downX
      const dy = e.clientY - p.downY
      if (Math.hypot(dx, dy) <= TOUCH_SLOP_PX) return
      if (Math.abs(dx) >= Math.abs(dy)) {
        // Sideways: not ours (leave the edge for the system).
        pointerRef.current = null
        return
      }
      openWheel()
      p.mode = 'scrub'
      p.rawDial = dialRef.current
      p.lastY = p.downY
    }
    const dy = e.clientY - p.lastY
    p.lastY = e.clientY
    p.samples.push({ t: e.timeStamp, y: e.clientY })
    while (p.samples.length > 2 && e.timeStamp - p.samples[0].t > 100) p.samples.shift()
    if (Math.abs(dy) < 0.01) return
    p.dragged = true
    p.rawDial += dialDeltaFromPointerDy(dy, TICK_SPACING_PX)
    setDial(rubberBandDialPosition(p.rawDial, 1, ayahCount))
  }

  const onPointerUp = (e: ReactPointerEvent<HTMLDivElement>) => {
    const p = pointerRef.current
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
    if (!p || p.id !== e.pointerId) return
    pointerRef.current = null
    if (receded) return
    if (p.mode === 'wait') {
      // A tap opens the wheel and leaves it open — no countdown yet.
      if (e.type !== 'pointercancel') openWheel()
      return
    }
    hoverLastYRef.current = e.clientY
    let velocity = 0
    if (p.dragged) {
      const first = p.samples[0]
      const dt = (e.timeStamp - first.t) / 1000
      if (dt > 0.005) velocity = -((e.clientY - first.y) / dt) / TICK_SPACING_PX
    } else if (!p.openedHere && p.type === 'mouse') {
      // Desktop click on an open wheel picks the tick under the cursor.
      setDial(tickUnderPointer(e.clientY))
    }
    scheduleReleaseCommit(dialRef.current, velocity)
  }

  return (
    <div
      ref={rootRef}
      className="ayah-rail"
      data-side={side}
      data-receded={receded}
      data-expanded={hitExpanded ? 'true' : 'false'}
      style={{ '--rail-hit-h': `${collapsedHitHeight}px` } as CSSProperties}
    >
      <canvas ref={canvasRef} className="ayah-rail-canvas" aria-hidden="true" />
      {hitExpanded || catcherHeld ? (
        // While the wheel is open a tap anywhere off it commits and closes
        // it instead of reaching the page. Nothing is drawn here.
        <div
          className="ayah-rail-dismiss"
          aria-hidden="true"
          onPointerDown={(e) => {
            if (e.pointerType === 'mouse' && e.button !== 0) return
            e.preventDefault()
            e.stopPropagation()
            // Outlive the collapse so this tap's up / click never land on the page.
            setCatcherHeld(true)
            const release = () => {
              window.removeEventListener('pointerup', release, true)
              window.removeEventListener('pointercancel', release, true)
              window.setTimeout(() => setCatcherHeld(false), 350)
            }
            window.addEventListener('pointerup', release, true)
            window.addEventListener('pointercancel', release, true)
            dismiss()
          }}
          onClick={(e) => e.stopPropagation()}
        />
      ) : null}
      <div
        className="ayah-rail-hit"
        onPointerEnter={onPointerEnter}
        onPointerLeave={onPointerLeave}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        title="Jump to ayah"
        role="slider"
        aria-valuemin={1}
        aria-valuemax={ayahCount}
        aria-valuenow={ariaAyah}
        aria-label="Ayah position"
        aria-disabled={receded}
      />
    </div>
  )
})

function setLetterSpacing(ctx: CanvasRenderingContext2D, value: string) {
  const c = ctx as CanvasRenderingContext2D & { letterSpacing?: string }
  if ('letterSpacing' in c) c.letterSpacing = value
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  const radius = Math.max(0, Math.min(r, w / 2, h / 2))
  ctx.moveTo(x + radius, y)
  ctx.arcTo(x + w, y, x + w, y + h, radius)
  ctx.arcTo(x + w, y + h, x, y + h, radius)
  ctx.arcTo(x, y + h, x, y, radius)
  ctx.arcTo(x, y, x + w, y, radius)
  ctx.closePath()
}
