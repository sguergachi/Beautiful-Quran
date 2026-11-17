import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react'
import type { Surah } from '../../data/models'
import { VerseBookmarkRibbon } from '../../render/VerseBookmarkRibbon'
import { rowPlacement, type RowPlacement } from './continuePlace'

/** Android `ContinueDockDwellMs`: the row must sit in view a beat first. */
const DOCK_DWELL_MS = 500
/** Android `ContinueDockMs` / `ContinueUndockMs`. */
const FLIGHT_MS = 500
/** Android `ContinueFlightEase`: strong ease-in-out. */
const FLIGHT_EASE = 'cubic-bezier(0.77, 0, 0.175, 1)'

type Phase = 'hidden' | 'shown' | 'docking' | 'docked' | 'undocking'
type Box = { left: number; top: number; width: number; height: number }

/**
 * The reading place floats at the foot of Chapters only while its own row
 * (which carries the green place ribbon) is out of view (Android
 * `ContinuePill` + `shouldShowContinuePill`). When the row comes fully into
 * view and stays a beat, the pill flies up and settles onto it, squaring its
 * corners and fading into the row; when the row leaves through the top, a copy
 * peels off it and flies back down to the pill's seat.
 */
export function ContinueFloat({
  surah,
  ayah,
  eligible,
  rowKey,
  sheetRef,
  scrollerRef,
  layoutKey,
  onOpen,
  onPrepare,
  onFloatChange,
}: {
  surah: Surah | null
  ayah: number
  /** A place exists, search is off, Chapters is on top, the transport is down. */
  eligible: boolean
  rowKey: string | null
  sheetRef: RefObject<HTMLDivElement | null>
  scrollerRef: RefObject<HTMLDivElement | null>
  /** Changes whenever the list's rows may have moved without a scroll. */
  layoutKey: unknown
  onOpen: () => void
  onPrepare: () => void
  /** The pill holds paper at the list's foot (shown, or dwelling to dock). */
  onFloatChange: (holding: boolean) => void
}) {
  const [phase, setPhase] = useState<Phase>('hidden')
  const [instant, setInstant] = useState(false)
  const [flight, setFlight] = useState<{ from: Box; to: Box; dock: boolean } | null>(null)
  const phaseRef = useRef<Phase>('hidden')
  const seatRef = useRef<HTMLDivElement>(null)
  const ghostRef = useRef<HTMLDivElement>(null)
  const dwell = useRef<number | null>(null)
  const [dwelling, setDwelling] = useState(false)
  const lastPlacement = useRef<RowPlacement>('none')

  const go = useCallback((next: Phase, opts: { instant?: boolean } = {}) => {
    phaseRef.current = next
    setInstant(opts.instant === true)
    setPhase(next)
  }, [])

  const clearDwell = () => {
    if (dwell.current != null) window.clearTimeout(dwell.current)
    dwell.current = null
    setDwelling(false)
  }

  const boxOf = (el: Element | null | undefined): Box | null => {
    const sheet = sheetRef.current
    if (!el || !sheet) return null
    const r = el.getBoundingClientRect()
    const s = sheet.getBoundingClientRect()
    if (r.width <= 0 || r.height <= 0) return null
    return { left: r.left - s.left, top: r.top - s.top, width: r.width, height: r.height }
  }
  /** The pill's resting box, untransformed (its enter/exit moves the body only). */
  const seatBox = (): Box | null => {
    const seat = seatRef.current
    const body = seat?.firstElementChild as HTMLElement | null
    if (!seat || !body) return null
    const box = boxOf(seat)
    if (!box) return null
    return { ...box, left: box.left + body.offsetLeft, width: body.offsetWidth, height: body.offsetHeight }
  }
  const rowElement = (): HTMLElement | null => {
    if (!rowKey) return null
    return (scrollerRef.current?.querySelector<HTMLElement>(`[data-row-key="${rowKey}"]`)?.closest('li') ??
      null) as HTMLElement | null
  }
  const placementNow = (): RowPlacement => {
    const scroller = scrollerRef.current
    const row = rowElement()
    if (!scroller || !row) return scroller?.querySelector('[data-row-key]') ? 'none' : 'unmeasured'
    const view = scroller.getBoundingClientRect()
    const field = scroller.querySelector('.search-row')?.getBoundingClientRect()
    const r = row.getBoundingClientRect()
    return rowPlacement(r.top, r.bottom, field ? field.bottom : view.top, view.bottom)
  }
  const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

  const evaluate = useCallback(() => {
    const placement = placementNow()
    const previous = lastPlacement.current
    lastPlacement.current = placement
    const current = phaseRef.current
    if (!eligible || !surah || placement === 'unmeasured') {
      clearDwell()
      setFlight(null)
      if (current !== 'hidden') go('hidden')
      return
    }
    if (placement === 'visible') {
      if (current === 'undocking') {
        setFlight(null)
        go('docked')
      } else if (current === 'shown' && dwell.current == null) {
        setDwelling(true)
        dwell.current = window.setTimeout(() => {
          dwell.current = null
          setDwelling(false)
          if (phaseRef.current !== 'shown' || placementNow() !== 'visible') return
          const from = seatBox()
          const to = boxOf(rowElement())
          if (!from || !to || reducedMotion()) {
            go('docked')
            return
          }
          setFlight({ from, to, dock: true })
          go('docking', { instant: true })
        }, DOCK_DWELL_MS)
      }
      return
    }
    clearDwell()
    if (current === 'docking') {
      setFlight(null)
      go('shown')
    } else if (current === 'docked') {
      // Left through the top while it owned the place: fly a copy home.
      const from = previous === 'visible' || previous === 'partial' ? boxOf(rowElement()) : null
      const to = seatBox()
      if (placement === 'above' && from && to && !reducedMotion()) {
        setFlight({ from, to, dock: false })
        go('undocking')
      } else {
        go('shown')
      }
    } else if (current === 'hidden') {
      go('shown')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [eligible, surah, rowKey, go])

  // Rows move under the pill as the list scrolls, filters, or pins.
  useEffect(() => {
    const scroller = scrollerRef.current
    if (!scroller) return
    let frame = 0
    const schedule = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(evaluate)
    }
    schedule()
    scroller.addEventListener('scroll', schedule, { passive: true })
    const ro = new ResizeObserver(schedule)
    ro.observe(scroller)
    return () => {
      cancelAnimationFrame(frame)
      scroller.removeEventListener('scroll', schedule)
      ro.disconnect()
    }
  }, [evaluate, scrollerRef, layoutKey])

  useEffect(() => () => clearDwell(), [])

  const holding = phase === 'shown' || dwelling
  useEffect(() => onFloatChange(holding), [holding, onFloatChange])

  useLayoutEffect(() => {
    const ghost = ghostRef.current
    if (!flight || !ghost) return
    const { from, to, dock } = flight
    const frames: Keyframe[] = [
      { left: `${from.left}px`, top: `${from.top}px`, width: `${from.width}px`, height: `${from.height}px`, borderRadius: dock ? '28px' : '0px', opacity: 1 },
      ...(dock ? [{ offset: 0.5, opacity: 1 } as Keyframe] : []),
      { left: `${to.left}px`, top: `${to.top}px`, width: `${to.width}px`, height: `${to.height}px`, borderRadius: dock ? '0px' : '28px', opacity: dock ? 0 : 1 },
    ]
    // The CONTINUE flag dissolves across a dock (the row has none) and
    // returns on the way home.
    const flag = ghost.querySelector<HTMLElement>('.continue-label')
    flag?.animate([{ opacity: dock ? 1 : 0 }, { opacity: dock ? 0 : 1 }], { duration: FLIGHT_MS, easing: FLIGHT_EASE, fill: 'forwards' })
    const travel = ghost.animate(frames, { duration: FLIGHT_MS, easing: FLIGHT_EASE, fill: 'forwards' })
    travel.onfinish = () => {
      setFlight(null)
      if (dock) go('docked')
      // The copy lands exactly on the seat: the pill takes over without an enter.
      else go('shown', { instant: true })
    }
    return () => {
      travel.onfinish = null
      travel.cancel()
    }
  }, [flight, go])

  if (!surah) return null
  return (
    <>
      <div
        ref={seatRef}
        className="continue-float"
        data-shown={phase === 'shown' || dwelling || undefined}
        data-instant={instant || undefined}
        inert={!(phase === 'shown' || dwelling) || undefined}
      >
        <ContinuePillBody
          surah={surah}
          ayah={ayah}
          onClick={onOpen}
          onPrepare={onPrepare}
        />
      </div>
      {flight ? (
        <div
          ref={ghostRef}
          className="continue-ghost"
          aria-hidden="true"
          style={{ left: flight.from.left, top: flight.from.top, width: flight.from.width, height: flight.from.height }}
        >
          <ContinuePillBody surah={surah} ayah={ayah} />
        </div>
      ) : null}
    </>
  )
}

/** The pill's face: place ribbon, gold number, CONTINUE over the name and
 * place, Arabic name — the chapter row's order on the green wash. */
export function ContinuePillBody({
  surah,
  ayah,
  onClick,
  onPrepare,
}: {
  surah: Surah
  ayah: number
  onClick?: () => void
  onPrepare?: () => void
}) {
  const Tag = onClick ? 'button' : 'div'
  return (
    <Tag
      {...(onClick ? { type: 'button' as const } : {})}
      className="continue"
      aria-label={onClick ? `Continue listening: ${surah.nameTransliteration}, ayah ${ayah}` : undefined}
      onPointerEnter={onPrepare}
      onPointerDown={onPrepare}
      onFocus={onPrepare}
      onClick={onClick}
    >
      <span className="continue-ribbon" aria-hidden="true">
        <VerseBookmarkRibbon
          bookmarked={false}
          placeMarked
          focused={false}
          side="left"
          interactive={false}
          topInset={0}
          bottomGap={0}
          edgeInset={7.5}
          onToggle={() => false}
          decorative
        />
      </span>
      <span className="continue-number" aria-hidden="true">
        {surah.id}
      </span>
      <span className="continue-copy" aria-hidden="true">
        <span className="continue-label">Continue</span>
        <span className="continue-target">
          <span className="continue-name">{surah.nameTransliteration}</span>
          <span className="continue-ayah"> · Ayah {ayah}</span>
        </span>
      </span>
      <span className="continue-ar" lang="ar" dir="rtl" aria-hidden="true">
        {surah.nameArabic}
      </span>
    </Tag>
  )
}
