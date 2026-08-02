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
  dialFromTrackY,
  focusRadiusForHeight,
  isMajorAyah,
  MOBILE_RAIL_MEDIA,
  railCollapsedBarRect,
  railExpandedLayout,
  railTickBarRect,
  railTickLabelX,
  rubberBandDialPosition,
  symbolicAyahBarCount,
  tickFocus,
  tickLength,
  trackYFromDial,
  type RailGrowFrom,
} from './ayahRailMath'

/**
 * Android `AyahSelectorRail` springs, converted for Motion:
 * damping = dampingRatio * 2 * sqrt(stiffness) (mass = 1).
 * Expand: dampingRatio 0.85, stiffness 340. Collapse: 1.0 / 200.
 */
const EXPAND_SPRING = { type: 'spring' as const, stiffness: 340, damping: 31.3 }
const COLLAPSE_SPRING = { type: 'spring' as const, stiffness: 200, damping: 28.3 }

const TOP_FRAC = 0.08
const BOTTOM_FRAC = 0.12
const TICK_SPACING_PX = 14
const MIN_BAR_LEN = 8
const MIN_THICKNESS = 2
const MAX_THICKNESS = 4
const VERTICAL_FADE_PX = 82
const COLLAPSED_BAR_H = 1.5
const COLLAPSED_BAR_W = 10

/**
 * Imperative handle exposed via `ref`. The reader writes the continuous
 * scroll readout into a ref (not React state) so per-frame position changes
 * do not reconcile the whole reader tree — it then bumps `schedulePaint`
 * so the canvas redraws on the next animation frame.
 */
export interface AyahSelectorRailHandle {
  schedulePaint: () => void
}

type Props = {
  ayahCount: number
  /** Reading / recitation ayah the collapsed stack tracks. */
  currentAyah: number
  /**
   * Continuous reading position (1…ayahCount) read live from a ref so the
   * rail can track scroll without its parent re-rendering every frame.
   * Falls back to [currentAyah] when absent.
   */
  currentPositionRef?: MutableRefObject<number> | null
  /** Ayah numbers bookmarked in this surah — ruby ticks on the collapsed stack. */
  bookmarkedAyahs?: ReadonlySet<number>
  /** Which sheet edge the overlay rail sits on. */
  side: 'left' | 'right'
  receded: boolean
  /**
   * Commit after release / click. The dial moves under the pointer while
   * dragging, but the page does not — matching Android, only this callback
   * should drive a FocusEngine jump onto the chosen ayah.
   */
  onJump: (ayah: number) => void
  /** Fires when the magnification wheel opens or collapses. */
  onExpandedChange?: (expanded: boolean) => void
  /**
   * Sheet-relative vertical center of the collapsed dash stack — used by
   * contextual education to aim the ayah-rail spotlight.
   */
  onCollapsedCenterY?: (centerYInSheet: number) => void
}

function readCssColor(el: HTMLElement, name: string, fallback: string): string {
  const v = getComputedStyle(el).getPropertyValue(name).trim()
  return v || fallback
}

function withAlpha(color: string, alpha: number): string {
  const a = Math.min(1, Math.max(0, alpha))
  // hex #rgb / #rrggbb
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
  // already rgb/rgba — replace alpha via color-mix when possible
  return `color-mix(in srgb, ${color} ${Math.round(a * 100)}%, transparent)`
}

function railGrowFrom(): RailGrowFrom {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return 'center'
  }
  return window.matchMedia(MOBILE_RAIL_MEDIA).matches ? 'edge' : 'center'
}

/**
 * Ayah scrub rail for the web reader.
 *
 * Collapsed: a symbolic stack of dashes tracking reading progress.
 * Hover / press: spring-animates into a magnification dial (Android parity) —
 * minimized dashes retract while ticks bloom widthwise under the pointer,
 * taper at the top and bottom edges, and the focal ayah is the widest (gold).
 * Hover maps pointer Y along the track so the gold tick stays under the cursor.
 *
 * Desktop keeps a centered midline. Mobile (≤640px) matches Android: bars
 * grow flush from the screen edge with the outer rounded cap hidden, and
 * ayah numbers hang inward toward the page.
 *
 * Dragging moves the dial by tick spacing (Android wheel scrub). The page
 * stays put until release, when [onJump] fires so the reader can run a
 * FocusEngine pre-roll slide — same commit model as Android's
 * `AyahSelectorRail`. A no-drag tap selects the visible tick under the
 * pointer, not an absolute track fraction.
 */
export const AyahSelectorRail = forwardRef<AyahSelectorRailHandle, Props>(
  function AyahSelectorRail(
    {
      ayahCount,
      currentAyah,
      currentPositionRef,
      bookmarkedAyahs,
      side,
      receded,
      onJump,
      onExpandedChange,
      onCollapsedCenterY,
    }: Props,
    ref,
  ) {
    const rootRef = useRef<HTMLDivElement>(null)
    const onExpandedChangeRef = useRef(onExpandedChange)
    onExpandedChangeRef.current = onExpandedChange
    const onCollapsedCenterYRef = useRef(onCollapsedCenterY)
    onCollapsedCenterYRef.current = onCollapsedCenterY
    const canvasRef = useRef<HTMLCanvasElement>(null)
    const dialRef = useRef(currentPositionRef?.current ?? currentAyah)
    const expandRef = useRef(0)
    const hoverRef = useRef(false)
    const draggingRef = useRef(false)
    const draggedRef = useRef(false)
    const lastClientYRef = useRef<number | null>(null)
    const rafRef = useRef(0)
    const expandTargetRef = useRef(0)
    const expandControlsRef = useRef<AnimationPlaybackControls | null>(null)
    const [ariaAyah, setAriaAyah] = useState(currentAyah)
    /** Full-height hit zone while open; shrinks to the dash stack when closed. */
    const [hitExpanded, setHitExpanded] = useState(false)
    const collapsedHitHeight = collapsedRailHitHeightPx(ayahCount)

    const readingPos = () => currentPositionRef?.current ?? currentAyah

    useEffect(() => {
      if (!hoverRef.current && !draggingRef.current) {
        setAriaAyah(currentAyah)
      }
    }, [currentAyah])

  const paint = useCallback(() => {
    const canvas = canvasRef.current
    const root = rootRef.current
    if (!canvas || !root) return

    const growFrom = railGrowFrom()
    root.dataset.flush = growFrom === 'edge' ? 'true' : 'false'

    const dpr = window.devicePixelRatio || 1
    const cssW = root.clientWidth
    const cssH = root.clientHeight
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

    const ink = readCssColor(root, '--ink', '#1c1b18')
    const gold = readCssColor(root, '--gold', '#c9a227')
    const ruby = readCssColor(root, '--bookmark', '#b3122f')
    const expand = expandRef.current
    const collapsedAlpha = 1 - expand
    const centerY = cssH * 0.5

    const collapsedCount = symbolicAyahBarCount(ayahCount)
    const collapsedSpacing = Math.min(8, Math.max(4, 72 / collapsedCount))
    const collapsedStep = COLLAPSED_BAR_H + collapsedSpacing
    const readPos = Math.min(ayahCount, Math.max(1, readingPos()))
    const readProgress = ayahCount > 1 ? (readPos - 1) / (ayahCount - 1) : 0
    const collapsedActive = readProgress * (collapsedCount - 1)

    if (collapsedAlpha > 0.01) {
      const halfSpan = Math.max(1, (collapsedCount - 1) / 2)
      // Bookmarks recolor the nearest existing bar — no extra mark beside it.
      const bookmarkedBars = new Set<number>()
      if (bookmarkedAyahs) {
        for (const ayah of bookmarkedAyahs) {
          if (ayah < 1 || ayah > ayahCount) continue
          const progress = ayahCount > 1 ? (ayah - 1) / (ayahCount - 1) : 0
          bookmarkedBars.add(
            Math.min(
              collapsedCount - 1,
              Math.max(0, Math.round(progress * (collapsedCount - 1))),
            ),
          )
        }
      }
      for (let index = 0; index < collapsedCount; index++) {
        const relative = index - (collapsedCount - 1) / 2
        const y = centerY + relative * collapsedStep
        const exit = Math.min(
          1,
          Math.max(0, collapsedAlpha * 1.35 - (Math.abs(relative) / halfSpan) * 0.35),
        )
        if (exit <= 0.01) continue
        const focus = Math.min(1, Math.max(0, 1 - Math.abs(index - collapsedActive)))
        const barW = COLLAPSED_BAR_W * (0.7 + 0.45 * focus)
        const alpha = (0.18 + 0.72 * focus) * exit
        const rect = railCollapsedBarRect(cssW, side, growFrom, barW, COLLAPSED_BAR_H, exit)
        ctx.beginPath()
        roundRect(ctx, rect.x, y - COLLAPSED_BAR_H / 2, rect.width, COLLAPSED_BAR_H, COLLAPSED_BAR_H)
        ctx.fillStyle = bookmarkedBars.has(index)
          ? withAlpha(ruby, (0.55 + 0.35 * focus) * exit)
          : withAlpha(ink, alpha)
        ctx.fill()
      }
    }

    if (expand > 0.01) {
      const dial = dialRef.current
      const selectedAyah = Math.min(ayahCount, Math.max(1, Math.round(dial)))
      const focusRadius = focusRadiusForHeight(cssH, TICK_SPACING_PX)
      const anchorY = trackYFromDial(dial, cssH, ayahCount, TOP_FRAC, BOTTOM_FRAC)
      // Blend anchor from collapsed active bar toward track position on open.
      const collapsedActiveY =
        centerY + (collapsedActive - (collapsedCount - 1) / 2) * collapsedStep
      const drawAnchorY = collapsedActiveY + (anchorY - collapsedActiveY) * expand

      const first = Math.max(1, Math.floor(dial - drawAnchorY / TICK_SPACING_PX))
      const last = Math.min(
        ayahCount,
        Math.ceil(dial + (cssH - drawAnchorY) / TICK_SPACING_PX + 1),
      )

      // Reserve room for the longest ayah label at the selected (largest) size
      // so numbers never clip against the canvas edge on narrow mobile rails.
      ctx.font = '700 11px "EB Garamond", "Times New Roman", serif'
      const labelWidth = ctx.measureText(String(ayahCount)).width
      const layout = railExpandedLayout(cssW, side, labelWidth, growFrom)
      const { maxBarLen, majorBonus } = layout

      // Numbers hang toward the page (inner edge of the overlay rail).
      const numbersTowardPage = side === 'left'
      ctx.textAlign = numbersTowardPage ? 'left' : 'right'
      ctx.textBaseline = 'middle'
      ctx.font = '600 8.5px "EB Garamond", "Times New Roman", serif'

      for (let ayah = first; ayah <= last; ayah++) {
        const offset = ayah - dial
        const y = drawAnchorY + offset * TICK_SPACING_PX
        const distance = Math.min(1, Math.abs(offset) / focusRadius)
        const focus = tickFocus(offset, focusRadius)
        const major = isMajorAyah(ayah, ayahCount)
        const arrival = Math.min(1, Math.max(0, (expand - distance * 0.3) / 0.7))
        if (arrival <= 0.01) continue
        const edgeFade = Math.min(1, Math.max(0, Math.min(y, cssH - y) / VERTICAL_FADE_PX))
        const grow = arrival * (0.35 + 0.65 * Math.max(edgeFade, focus * focus))
        const length =
          tickLength(focus, major, MIN_BAR_LEN, maxBarLen, majorBonus) * grow
        const thickness = MIN_THICKNESS + (MAX_THICKNESS - MIN_THICKNESS) * focus
        const alpha = (0.1 + 0.62 * focus) * arrival * edgeFade
        const isSelected = ayah === selectedAyah
        const isBookmarked = bookmarkedAyahs?.has(ayah) ?? false
        const bar = railTickBarRect(layout, side, length, thickness)

        // Gold = dial focus; ruby = saved verse; ink = ordinary tick.
        ctx.beginPath()
        roundRect(ctx, bar.x, y - thickness / 2, bar.width, thickness, thickness)
        ctx.fillStyle = isSelected
          ? withAlpha(gold, 0.96 * arrival)
          : isBookmarked
            ? withAlpha(ruby, (0.55 + 0.35 * focus) * arrival * edgeFade)
            : withAlpha(ink, alpha)
        ctx.fill()

        if (isSelected || isBookmarked || (major && focus > 0.35)) {
          const labelAlpha = isSelected
            ? 0.95 * arrival
            : isBookmarked
              ? (0.4 + 0.5 * focus) * arrival * edgeFade
              : (0.18 + 0.46 * focus) * arrival * edgeFade
          ctx.fillStyle = isSelected
            ? withAlpha(gold, labelAlpha)
            : isBookmarked
              ? withAlpha(ruby, labelAlpha)
              : withAlpha(ink, labelAlpha)
          ctx.font = isSelected
            ? '700 11px "EB Garamond", "Times New Roman", serif'
            : '600 8.5px "EB Garamond", "Times New Roman", serif'
          ctx.fillText(String(ayah), railTickLabelX(layout, side, length), y)
        }
      }
    }
  }, [ayahCount, bookmarkedAyahs, side])

  const schedulePaint = useCallback(() => {
    if (rafRef.current) return
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = 0
      // Sync dial to reading position only while fully collapsed and idle.
      if (
        expandTargetRef.current < 0.01 &&
        expandRef.current < 0.01 &&
        !draggingRef.current &&
        !hoverRef.current
      ) {
        dialRef.current = readingPos()
      }
      paint()
    })
  }, [paint])

  useImperativeHandle(
    ref,
    () => ({ schedulePaint }),
    [schedulePaint],
  )

  useEffect(() => {
    schedulePaint()
  }, [ayahCount, bookmarkedAyahs, currentAyah, schedulePaint])

  useEffect(() => {
    const onResize = () => schedulePaint()
    window.addEventListener('resize', onResize)
    const mq = window.matchMedia(MOBILE_RAIL_MEDIA)
    const onMq = () => schedulePaint()
    mq.addEventListener('change', onMq)
    return () => {
      window.removeEventListener('resize', onResize)
      mq.removeEventListener('change', onMq)
      if (rafRef.current) {
        cancelAnimationFrame(rafRef.current)
        rafRef.current = 0
      }
      expandControlsRef.current?.stop()
      expandControlsRef.current = null
    }
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
    // Grow the hit zone immediately on open so drag can continue; shrink only
    // after collapse finishes so the wheel does not lose the pointer mid-fade.
    if (open) setHitExpanded(true)
    if (open !== wasOpen) onExpandedChangeRef.current?.(open)

    const reducedMotion =
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches

    if (reducedMotion || Math.abs(expandRef.current - target) < 0.001) {
      expandRef.current = target
      if (!open) setHitExpanded(false)
      schedulePaint()
      return
    }

    // Spring the canvas expand scalar — minimized stack retracts while the
    // magnification wheel blooms (same springs as Android AyahSelectorRail).
    expandControlsRef.current = animate(expandRef.current, target, {
      ...(open ? EXPAND_SPRING : COLLAPSE_SPRING),
      onUpdate: (value) => {
        expandRef.current = value
        schedulePaint()
      },
      onComplete: () => {
        expandRef.current = target
        expandControlsRef.current = null
        if (!open) setHitExpanded(false)
        schedulePaint()
      },
    })
  }

  const commitDial = (next: number) => {
    dialRef.current = next
    const ayah = Math.min(ayahCount, Math.max(1, Math.round(next)))
    setAriaAyah(ayah)
    schedulePaint()
  }

  /**
   * Scrub the magnification wheel by pointer delta — one tickSpacingPx of
   * movement = one ayah, matching the drawn tick spacing (Android parity).
   * Absolute Y→ayah mapping is wrong for drag: nearby tick labels are 14px
   * apart while a long surah packs the full range into the track.
   */
  const scrubDialByClientY = (clientY: number, rubberBand: boolean) => {
    const lastY = lastClientYRef.current
    lastClientYRef.current = clientY
    if (lastY == null) return
    const dy = clientY - lastY
    if (Math.abs(dy) < 0.01) return
    draggedRef.current = true
    const raw = dialRef.current + dialDeltaFromPointerDy(dy, TICK_SPACING_PX)
    const next = rubberBand
      ? rubberBandDialPosition(raw, 1, ayahCount)
      : Math.min(ayahCount, Math.max(1, raw))
    commitDial(next)
  }

  /**
   * Hover follow: map pointer Y along the track so the gold focal tick sits
   * under the cursor. Drag uses tick-spaced wheel scrub instead.
   */
  const followDialToClientY = (clientY: number) => {
    const root = rootRef.current
    if (!root) return
    const rect = root.getBoundingClientRect()
    const y = clientY - rect.top
    const next = dialFromTrackY(y, rect.height, ayahCount, TOP_FRAC, BOTTOM_FRAC)
    commitDial(Math.min(ayahCount, Math.max(1, next)))
  }

  /** Pick the visible tick under [clientY] without moving the page. */
  const snapDialToTickUnderPointer = (clientY: number) => {
    const root = rootRef.current
    if (!root) return
    const rect = root.getBoundingClientRect()
    const y = clientY - rect.top
    const dial = dialRef.current
    const anchorY = trackYFromDial(dial, rect.height, ayahCount, TOP_FRAC, BOTTOM_FRAC)
    const next = Math.min(
      ayahCount,
      Math.max(1, dialFromTickY(y, dial, anchorY, TICK_SPACING_PX)),
    )
    commitDial(next)
  }

  const onPointerEnter = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (receded) return
    hoverRef.current = true
    lastClientYRef.current = e.clientY
    if (!draggingRef.current) {
      followDialToClientY(e.clientY)
    }
    setExpanded(true)
  }

  const onPointerLeave = () => {
    hoverRef.current = false
    lastClientYRef.current = null
    if (!draggingRef.current) {
      setAriaAyah(currentAyah)
      setExpanded(false)
    }
  }

  const onPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (receded) return
    if (e.pointerType === 'mouse' && e.button !== 0) return
    e.preventDefault()
    draggingRef.current = true
    draggedRef.current = false
    hoverRef.current = true
    lastClientYRef.current = e.clientY
    e.currentTarget.setPointerCapture(e.pointerId)
    if (expandTargetRef.current < 0.5) {
      dialRef.current = readingPos()
    }
    setExpanded(true)
    schedulePaint()
  }

  const onPointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (receded) return
    if (draggingRef.current) {
      scrubDialByClientY(e.clientY, true)
      return
    }
    if (hoverRef.current) {
      followDialToClientY(e.clientY)
    }
  }

  const onPointerUp = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (!draggingRef.current) return
    draggingRef.current = false
    if (e.currentTarget.hasPointerCapture(e.pointerId)) {
      e.currentTarget.releasePointerCapture(e.pointerId)
    }
    // No-drag tap/click: select the tick under the pointer (visible label),
    // not an absolute track fraction — that was skipping past the exact ayah.
    if (!draggedRef.current) {
      snapDialToTickUnderPointer(e.clientY)
    } else {
      scrubDialByClientY(e.clientY, false)
    }
    lastClientYRef.current = null
    const ayah = Math.min(ayahCount, Math.max(1, Math.round(dialRef.current)))
    // Hand-initiated jump — FocusEngine planJump + home-scroll, not a scrub.
    onJump(ayah)
    // Keep expanded while still hovering (mouse); collapse on touch lift.
    if (e.pointerType !== 'mouse' || !hoverRef.current) {
      hoverRef.current = false
      setAriaAyah(ayah)
      setExpanded(false)
    }
  }

  return (
    <div
      ref={rootRef}
      className="ayah-rail"
      data-side={side}
      data-receded={receded}
      data-expanded={hitExpanded ? 'true' : 'false'}
      style={
        {
          '--rail-hit-h': `${collapsedHitHeight}px`,
        } as CSSProperties
      }
    >
      <canvas ref={canvasRef} className="ayah-rail-canvas" aria-hidden="true" />
      {/*
        Collapsed hit zone is only the visual dash stack (vertical center).
        Expanded grows full-height so the scrub wheel stays under the finger.
        Root is pointer-events:none so page taps above/below the stack pass through.
      */}
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
  }
)

function roundRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number,
) {
  const radius = Math.min(r, w / 2, h / 2)
  ctx.moveTo(x + radius, y)
  ctx.arcTo(x + w, y, x + w, y + h, radius)
  ctx.arcTo(x + w, y + h, x, y + h, radius)
  ctx.arcTo(x, y + h, x, y, radius)
  ctx.arcTo(x, y, x + w, y, radius)
  ctx.closePath()
}
