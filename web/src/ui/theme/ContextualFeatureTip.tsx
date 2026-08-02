/**
 * Short lesson written into unused paper around a live feature spotlight.
 *
 * Inverse paper spotlight: royal-green vellum covers unused paper while the
 * feature stays clear. Body-side half-plane absorbs gestures; spotlight-side
 * stays interactive. Not a dialog, card, scrim, or sheet push.
 *
 * Lesson copy sits on the wash's leading feather (Android lane + CenterStart),
 * not against the solid far paper edge — parchment type settles into that
 * progressive veil toward the live mark.
 */
import { animate, type AnimationPlaybackControls } from 'motion'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { FAST_OUT_SLOW_IN, inkExpandProgress } from '../motion/easing'
import {
  coercePoint,
  tipActionCenter,
  tipBodyCenter,
  type ContextualTipPlacement,
  type Point,
} from './contextualTipPlacement'
import { InkSpillField } from './InkSpillField'

type Props = {
  visible: boolean
  title: string
  body: string
  onDismiss: () => void
  spotlightCenter: Point
  placement: ContextualTipPlacement
  /** Which edge hosts the live feature — drives the absorbing half-plane. */
  spotlightSide: 'left' | 'right'
  actionCenter?: Point | null
  dismissLabel?: string
  /** Reader theme paper — “Got it” cutout stays on the underlying sheet color. */
  dismissPaperColor?: string
  dismissInkColor?: string
  onRenderedChange?: (rendered: boolean) => void
  guideWidthFraction?: number
  guideHeightPx?: number
  contentPadding?: { start: number; end: number }
}

const ROYAL_GREEN_PAPER = '#062c24'

export function ContextualFeatureTip({
  visible,
  title,
  body,
  onDismiss,
  spotlightCenter,
  placement,
  spotlightSide,
  actionCenter = null,
  dismissLabel = 'Got it',
  dismissPaperColor = 'var(--reader-paper, #faf3e8)',
  dismissInkColor = 'var(--reader-ink, #1c1b18)',
  onRenderedChange,
  guideWidthFraction = 0.42,
  guideHeightPx = 188,
  contentPadding = { start: 18, end: 32 },
}: Props) {
  const rootRef = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ width: 0, height: 0 })
  const [rendered, setRendered] = useState(visible)
  const [paper, setPaper] = useState(visible ? 1 : 0)
  const [ink, setInk] = useState(visible ? 1 : 0)
  const paperCtrl = useRef<AnimationPlaybackControls | null>(null)
  const inkCtrl = useRef<AnimationPlaybackControls | null>(null)
  const inkDelay = useRef(0)
  const onDismissRef = useRef(onDismiss)
  onDismissRef.current = onDismiss
  const onRenderedRef = useRef(onRenderedChange)
  onRenderedRef.current = onRenderedChange

  useLayoutEffect(() => {
    const el = rootRef.current
    if (!el) return
    const measure = () => {
      const rect = el.getBoundingClientRect()
      setSize({ width: rect.width, height: rect.height })
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    onRenderedRef.current?.(rendered)
  }, [rendered])

  useEffect(() => {
    paperCtrl.current?.stop()
    inkCtrl.current?.stop()
    window.clearTimeout(inkDelay.current)
    if (visible) {
      setRendered(true)
      setPaper(0)
      setInk(0)
      paperCtrl.current = animate(0, 1, {
        duration: 0.38,
        ease: (t) => inkExpandProgress(t),
        onUpdate: setPaper,
      })
      inkDelay.current = window.setTimeout(() => {
        inkCtrl.current = animate(0, 1, {
          duration: 0.24,
          ease: [...FAST_OUT_SLOW_IN],
          onUpdate: setInk,
        })
      }, 110)
    } else if (rendered) {
      inkCtrl.current = animate(ink, 0, {
        duration: 0.16,
        onUpdate: setInk,
      })
      paperCtrl.current = animate(paper, 0, {
        duration: 0.32,
        ease: [...FAST_OUT_SLOW_IN],
        onUpdate: setPaper,
        onComplete: () => setRendered(false),
      })
    }
    return () => {
      paperCtrl.current?.stop()
      inkCtrl.current?.stop()
      window.clearTimeout(inkDelay.current)
    }
    // Intentionally only keyed on visibility — animation owns paper/ink.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible])

  if (!rendered) return null

  const surface = size
  const spotlight = coercePoint(spotlightCenter, surface)
  const bodyCenter = tipBodyCenter(placement, spotlight, surface)
  const action =
    actionCenter != null
      ? coercePoint(actionCenter, surface)
      : tipActionCenter(placement, spotlight, surface)
  // Leading-edge anchor on the progressive feather (toward the spotlight) —
  // DESIGN.md: parchment type settles partly into that veil. Keep the shader
  // reservoir at the default bodyDistanceFraction (0.78).
  const leadingEdge = tipBodyCenter(
    {
      ...placement,
      bodyDistanceFraction: 0.4,
    },
    spotlight,
    surface,
  )
  const laneWidth = Math.min(
    surface.width * Math.min(1, Math.max(0.25, guideWidthFraction)),
    18 * 16,
  )
  const laneHeight = Math.min(guideHeightPx, surface.height || guideHeightPx)
  // Seat the lesson at the leading edge; type faces the spotlight.
  const spotlightOnLeft = spotlightSide === 'left'
  const laneLeft = spotlightOnLeft
    ? Math.min(
        Math.max(0, leadingEdge.x - contentPadding.start),
        Math.max(0, surface.width - laneWidth),
      )
    : Math.max(
        0,
        Math.min(
          leadingEdge.x - laneWidth + contentPadding.end,
          surface.width - laneWidth,
        ),
      )
  const laneTop = Math.min(
    Math.max(0, bodyCenter.y - laneHeight / 2),
    Math.max(0, surface.height - laneHeight),
  )
  const actionX = Math.min(
    Math.max(52, action.x),
    Math.max(52, surface.width - 52),
  )
  const actionY = Math.min(
    Math.max(52, action.y),
    Math.max(52, surface.height - 52),
  )
  const spotlightX = Math.min(
    Math.max(14, spotlight.x),
    Math.max(14, surface.width - 14),
  )
  const spotlightY = Math.min(
    Math.max(14, spotlight.y),
    Math.max(14, surface.height - 14),
  )

  return (
    <div
      ref={rootRef}
      className="contextual-feature-tip"
      data-visible={visible ? 'true' : 'false'}
      data-spotlight={spotlightSide}
      role="dialog"
      aria-label={title}
      aria-describedby="contextual-tip-body"
    >
      <InkSpillField
        progress={paper}
        spotlight={spotlight}
        body={bodyCenter}
        action={action}
        color={ROYAL_GREEN_PAPER}
        width={surface.width}
        height={surface.height}
      />
      {/* Body half absorbs; spotlight half stays pointer-events:none so the
          live ribbon / rail beneath keep receiving gestures. */}
      <div className="contextual-tip-absorb" aria-hidden="true" />
      <div
        className="contextual-tip-lesson"
        style={{
          left: laneLeft,
          top: laneTop,
          maxWidth: laneWidth,
          height: laneHeight,
          paddingLeft: contentPadding.start,
          paddingRight: contentPadding.end,
          opacity: ink,
          textAlign: spotlightOnLeft ? 'start' : 'end',
        }}
      >
        <h2 className="contextual-tip-title">{title}</h2>
        <p id="contextual-tip-body" className="contextual-tip-body">
          {body}
        </p>
      </div>
      <button
        type="button"
        className="contextual-tip-dismiss"
        style={{
          left: actionX,
          top: actionY,
          opacity: ink,
          background: dismissPaperColor,
          color: dismissInkColor,
        }}
        onClick={() => onDismissRef.current()}
      >
        {dismissLabel}
      </button>
      <div
        className="contextual-tip-mark"
        style={{ left: spotlightX, top: spotlightY, opacity: ink }}
        aria-hidden="true"
      >
        <span className="contextual-pulse-mark" />
      </div>
    </div>
  )
}
