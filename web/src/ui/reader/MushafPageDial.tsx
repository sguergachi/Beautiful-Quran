import { useEffect, useLayoutEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { appStore, useAppSelector } from '../../store/appStore'
import { IconTune } from '../icons/PlaybackIcons'
import { COVER_LAYER } from '../paper/stack'
import {
  DIAL_BELOW_GRAB,
  DIAL_CHAPTER_TICK,
  DIAL_EDGE_INSET,
  DIAL_PAGE_TICK,
  DIAL_RETURN_DOT,
  DIAL_RETURN_HIT,
  DIAL_RULE_Y,
  DIAL_RUNOUT_S,
  DIAL_RUN_OUT,
  DIAL_SLOT,
  DIAL_STRAY,
  DIAL_THUMB_ASPECT,
  DIAL_THUMB_H,
  DIAL_THUMB_HELD_H,
  MUSHAF_DIAL_RETURN_MS,
  dialAlong,
  dialChapterAt,
  dialChapterAtHysteresis,
  dialChapterRun,
  dialClampToTrack,
  dialCombSeats,
  dialFraction,
  dialLabelFoot,
  dialLabelHead,
  dialPastTrough,
  dialReturnLeft,
  dialShouldOpen,
  dialSpeed,
  dialTrackFraction,
  dialTrackX,
  dialTroughPage,
  mushafReturnWay,
} from './mushafDial'
import { mushafLeaf, useMushafLeaf } from './mushafLeafState'
import './mushaf.css'

/** Movement along the rule before a press becomes a scrub. */
const TOUCH_SLOP = 8

interface Hand {
  pointerId: number
  pressX: number
  pressY: number
  x: number
  y: number
  moved: boolean
  cancelled: boolean
  tier: 'chapter' | 'trough'
  chapter: number
  trough: { first: number; last: number } | null
  troughPage: number
  /** Captured at the click, paid off as the trough opens. */
  bias: number
  still: number
  speed: number
  lastX: number
  lastT: number
  runOut: number
  /** Walked out of the trough: no new one until the hand is back on the measure. */
  guard: boolean
  /** Pressed on the return roundel: a still release goes back. */
  onReturn: boolean
}

interface Shown {
  head: string
  foot: string
  x: number
}

/**
 * The hairline under the leaf — Android `MushafPageDial`. At rest a rule
 * with one thumb at the leaf's place among the book's leaves; under a hand
 * a comb of chapter openings nailed to the rule (leaf 1 at the binding's
 * end), and, held still, the leaves of one chapter in a trough. Release
 * turns there; a small roundel at the old place goes back for five seconds.
 */
export function MushafPageDial() {
  const leaf = useMushafLeaf()
  const root = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const hand = useRef<Hand | null>(null)
  const [label, setLabel] = useState<Shown | null>(null)
  const [width, setWidth] = useState(0)
  const [back, setBack] = useState<{ page: number; target: number; at: number } | null>(null)
  const [backFading, setBackFading] = useState(false)
  const leafRef = useRef(leaf)
  leafRef.current = leaf
  const frame = useRef(0)

  useLayoutEffect(() => {
    const el = root.current
    if (!el) return
    const observer = new ResizeObserver(() => setWidth(el.clientWidth))
    observer.observe(el)
    setWidth(el.clientWidth)
    return () => observer.disconnect()
  }, [])

  const seats = useRef<{ key: string; xs: number[] }>({ key: '', xs: [] })
  const seatsFor = (w: number) => {
    const { chapterPages, pageCount, rightToLeft } = leafRef.current
    const key = `${w}:${pageCount}:${rightToLeft}:${chapterPages.length}:${chapterPages[chapterPages.length - 1] ?? 0}`
    if (seats.current.key !== key) {
      seats.current = { key, xs: dialCombSeats(chapterPages, pageCount, DIAL_EDGE_INSET, w, rightToLeft) }
    }
    return seats.current.xs
  }

  const draw = () => {
    const el = canvas.current
    const box = root.current
    if (!el || !box) return
    const w = box.clientWidth
    const h = DIAL_SLOT + DIAL_BELOW_GRAB
    const dpr = Math.min(2, window.devicePixelRatio || 1)
    if (el.width !== Math.round(w * dpr) || el.height !== Math.round(h * dpr)) {
      el.width = Math.round(w * dpr)
      el.height = Math.round(h * dpr)
      el.style.width = `${w}px`
      el.style.height = `${h}px`
    }
    const ctx = el.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, w, h)
    const ink = getComputedStyle(box).color
    const tone = (alpha: number) => {
      ctx.fillStyle = ink
      ctx.globalAlpha = Math.max(0, Math.min(1, alpha))
    }
    const capsule = (x: number, y: number, cw: number, ch: number) => {
      ctx.beginPath()
      ctx.roundRect(x, y, cw, ch, ch / 2)
      ctx.fill()
    }
    const { page, pageCount, rightToLeft, chapterPages, reciting } = leafRef.current
    const held = hand.current
    const lift = held && held.moved ? 1 : 0
    const rule = lift ? 2 : 1
    tone(0.1 + 0.06 * lift)
    capsule(DIAL_EDGE_INSET, DIAL_RULE_Y - rule / 2, Math.max(rule, w - DIAL_EDGE_INSET * 2), rule)
    if (held && held.moved && !held.cancelled && held.tier === 'chapter' && chapterPages.length) {
      const xs = seatsFor(w)
      tone(0.54)
      for (const x of xs) ctx.fillRect(x - 0.5, DIAL_RULE_Y - DIAL_CHAPTER_TICK, 1, DIAL_CHAPTER_TICK)
      // The chapter under the hand, drawn as its cell: the bracket.
      const i = held.chapter
      const here = xs[i] ?? 0
      const before = xs[i - 1]
      const after = xs[i + 1]
      const a = before != null ? (before + here) / 2 : here
      const b = after != null ? (after + here) / 2 : here
      const lo = Math.min(a, b)
      const hi = Math.max(a, b)
      const min = DIAL_THUMB_HELD_H * DIAL_THUMB_ASPECT
      const span = Math.max(min, hi - lo)
      const mid = (lo + hi) / 2
      tone(0.62)
      capsule(mid - span / 2, DIAL_RULE_Y - DIAL_THUMB_HELD_H / 2, span, DIAL_THUMB_HELD_H)
    } else if (held && held.moved && !held.cancelled && held.tier === 'trough' && held.trough) {
      const inset = DIAL_EDGE_INSET + DIAL_RUN_OUT
      const run = held.trough
      tone(0.2)
      capsule(inset, DIAL_RULE_Y - DIAL_THUMB_HELD_H / 2, Math.max(DIAL_THUMB_HELD_H, w - inset * 2), DIAL_THUMB_HELD_H)
      const leaves = run.last - run.first + 1
      const at = (p: number) => {
        const t = leaves <= 1 ? 0.5 : (p - run.first) / (run.last - run.first)
        return dialTrackX(dialAlong(t, rightToLeft), w, inset)
      }
      tone(0.3)
      if (leaves <= 160) {
        for (let p = run.first; p <= run.last; p++) {
          ctx.fillRect(at(p) - 0.5, DIAL_RULE_Y - DIAL_PAGE_TICK, 1, DIAL_PAGE_TICK)
        }
      }
      tone(0.62)
      const mark = DIAL_THUMB_HELD_H * DIAL_THUMB_ASPECT
      capsule(at(held.troughPage) - mark / 2, DIAL_RULE_Y - DIAL_THUMB_HELD_H / 2 - 1, mark, DIAL_THUMB_HELD_H + 2)
    } else {
      const seat = dialTrackX(dialAlong(dialFraction(page, pageCount), rightToLeft), w, DIAL_EDGE_INSET)
      const x = held && held.moved ? dialClampToTrack(held.x, w, DIAL_EDGE_INSET) : seat
      const th = held ? DIAL_THUMB_HELD_H : DIAL_THUMB_H
      tone(reciting && !held ? 0.22 : 0.62)
      capsule(x - (th * DIAL_THUMB_ASPECT) / 2, DIAL_RULE_Y - th / 2, th * DIAL_THUMB_ASPECT, th)
    }
    ctx.globalAlpha = 1
  }

  // The rest state redraws whenever the leaf, its book or the width moves.
  useEffect(() => {
    if (!hand.current) draw()
  })

  // A settled page other than the dial's own landing is a new search: the
  // way back is consumed (Android `mushafDialShouldDismissReturn`).
  const seenPage = useRef(leaf.page)
  useEffect(() => {
    const previous = seenPage.current
    seenPage.current = leaf.page
    if (!back || previous === leaf.page) return
    // Arriving at the dial's own landing keeps it; any other turn is new.
    if (leaf.page !== back.target) setBack(null)
  }, [leaf.page, back])
  useEffect(() => {
    if (!back) return
    setBackFading(false)
    const fade = window.setTimeout(() => setBackFading(true), MUSHAF_DIAL_RETURN_MS)
    const gone = window.setTimeout(() => setBack(null), MUSHAF_DIAL_RETURN_MS + 260)
    return () => {
      window.clearTimeout(fade)
      window.clearTimeout(gone)
    }
  }, [back])

  const describe = (held: Hand, w: number): Shown | null => {
    if (!held.moved || held.cancelled) return null
    const { chapterPages } = leafRef.current
    if (held.tier === 'trough') {
      const surahId = held.chapter + 1
      const info = mushafLeaf.label(held.troughPage, surahId)
      if (!info) return null
      return { head: dialLabelHead(info, true, held.troughPage), foot: dialLabelFoot(info, true), x: dialClampToTrack(held.x, w, DIAL_EDGE_INSET) }
    }
    const start = chapterPages[held.chapter] ?? 1
    const info = mushafLeaf.label(start, held.chapter + 1)
    if (!info) return null
    const named = { ...info, number: held.chapter + 1 }
    return { head: dialLabelHead(named, false, start), foot: dialLabelFoot(named, false, start), x: dialClampToTrack(held.x, w, DIAL_EDGE_INSET) }
  }

  // The tier machine runs on the frame clock: a finger that stops moving
  // stops reporting, and holding still is the gesture that opens the trough.
  const tick = (now: number) => {
    const held = hand.current
    const box = root.current
    if (!held || !box) return
    const w = box.clientWidth
    const dt = Math.min(0.1, Math.max(0, (now - held.lastT) / 1000))
    held.lastT = now
    held.speed = dialSpeed(held.speed, dt > 0 ? (held.x - held.lastX) / dt : 0, dt)
    held.lastX = held.x
    const { chapterPages, pageCount, rightToLeft } = leafRef.current
    if (held.moved && !held.cancelled) {
      const xs = seatsFor(w)
      if (held.tier === 'chapter') {
        held.chapter = dialChapterAtHysteresis(xs, held.x, held.chapter)
        const onLine = Math.abs(held.y - held.pressY) <= DIAL_STRAY
        const inside = !dialPastTrough(held.x, w, DIAL_EDGE_INSET + DIAL_RUN_OUT)
        if (held.guard && onLine && inside) held.guard = false
        if (held.y - held.pressY < -DIAL_STRAY && !held.guard) {
          // Pulled up off the rule: the comb folds away; nothing turns.
          held.cancelled = true
        }
        held.still = held.speed < 16 ? held.still + dt : 0
        if (!held.guard && dialShouldOpen(held.speed, held.still)) {
          const run = dialChapterRun(chapterPages, chapterPages[held.chapter] ?? 1, pageCount)
          held.tier = 'trough'
          held.trough = run
          // The leaf under the thumb when the trough opens is the chapter's
          // opening, as the comb promised; the hand aims from there.
          held.troughPage = run.first
          const inset = DIAL_EDGE_INSET + DIAL_RUN_OUT
          held.bias = (rightToLeft ? 1 : 0) - dialTrackFraction(held.x, w, inset)
          held.runOut = 0
          navigator.vibrate?.(8)
        }
      } else if (held.trough) {
        const inset = DIAL_EDGE_INSET + DIAL_RUN_OUT
        const strayed = Math.abs(held.y - held.pressY) > DIAL_STRAY
        held.runOut = dialPastTrough(held.x, w, inset) ? held.runOut + dt : 0
        if (strayed || held.runOut >= DIAL_RUNOUT_S) {
          held.tier = 'chapter'
          held.trough = null
          held.guard = true
          held.still = 0
        } else {
          held.bias *= Math.exp(-dt / 0.11)
          held.troughPage = dialTroughPage(dialTrackFraction(held.x, w, inset) + held.bias, held.trough, rightToLeft)
        }
      }
    }
    draw()
    const shown = describe(held, w)
    setLabel((current) => current?.head === shown?.head && current?.foot === shown?.foot && current?.x === shown?.x ? current : shown)
    frame.current = requestAnimationFrame(tick)
  }
  useEffect(() => () => cancelAnimationFrame(frame.current), [])

  const press = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!leaf.ready || (event.pointerType === 'mouse' && event.button !== 0)) return
    const box = root.current
    if (!box) return
    const rect = box.getBoundingClientRect()
    const x = event.clientX - rect.left
    const y = event.clientY - rect.top
    const w = rect.width
    const onReturn = back != null && !backFading &&
      x >= dialReturnLeft(back.page, leaf.pageCount, w, DIAL_EDGE_INSET, DIAL_RETURN_HIT, leaf.rightToLeft) &&
      x <= dialReturnLeft(back.page, leaf.pageCount, w, DIAL_EDGE_INSET, DIAL_RETURN_HIT, leaf.rightToLeft) + DIAL_RETURN_HIT
    event.currentTarget.setPointerCapture(event.pointerId)
    event.preventDefault()
    hand.current = {
      pointerId: event.pointerId, pressX: x, pressY: y, x, y, moved: false, cancelled: false,
      tier: 'chapter', chapter: dialChapterAt(seatsFor(w), x), trough: null, troughPage: leaf.page, bias: 0,
      still: 0, speed: 0, lastX: x, lastT: performance.now(), runOut: 0, guard: false, onReturn,
    }
    cancelAnimationFrame(frame.current)
    frame.current = requestAnimationFrame(tick)
  }
  const move = (event: ReactPointerEvent<HTMLDivElement>) => {
    const held = hand.current
    const box = root.current
    if (!held || !box || held.pointerId !== event.pointerId) return
    const rect = box.getBoundingClientRect()
    held.x = event.clientX - rect.left
    held.y = event.clientY - rect.top
    if (!held.moved && Math.abs(held.x - held.pressX) >= TOUCH_SLOP) {
      held.moved = true
      // Taking hold of the dial again consumes the way back.
      setBack(null)
    }
  }
  const release = (event: ReactPointerEvent<HTMLDivElement>, cancelled = false) => {
    const held = hand.current
    if (!held || held.pointerId !== event.pointerId) return
    hand.current = null
    cancelAnimationFrame(frame.current)
    setLabel(null)
    const { page, chapterPages } = leafRef.current
    if (!cancelled && !held.moved && held.onReturn && back) {
      const to = back.page
      setBack(null)
      mushafLeaf.seek(to)
      draw()
      return
    }
    if (!cancelled && held.moved && !held.cancelled) {
      const target = held.tier === 'trough' ? held.troughPage : chapterPages[held.chapter] ?? page
      const surahId = held.chapter + 1
      if (target !== page) setBack({ page, target, at: performance.now() })
      mushafLeaf.seek(target, held.tier === 'chapter' ? surahId : undefined)
    }
    draw()
  }

  const returnLeft = back && width > 0
    ? dialReturnLeft(back.page, leaf.pageCount, width, DIAL_EDGE_INSET, DIAL_RETURN_HIT, leaf.rightToLeft)
    : 0
  const way = back ? mushafReturnWay(leaf.page, back.page, leaf.rightToLeft) : null
  const labelLeft = label ? Math.min(Math.max(label.x, 8), Math.max(8, width - 8)) : 0

  return (
    <div
      ref={root}
      className="mushaf-dial"
      role="slider"
      aria-label="Page"
      aria-valuemin={1}
      aria-valuemax={leaf.pageCount}
      aria-valuenow={leaf.page}
      tabIndex={-1}
      data-held={label != null || undefined}
      onPointerDown={press}
      onPointerMove={move}
      onPointerUp={(event) => release(event)}
      onPointerCancel={(event) => release(event, true)}
    >
      <canvas ref={canvas} className="mushaf-dial-rule" aria-hidden="true" />
      {label ? (
        <div className="mushaf-dial-label" aria-live="polite">
          <div className="mushaf-dial-label-text" style={{ left: labelLeft }}>
            <span className="mushaf-dial-label-head">{label.head}</span>
            <span className="mushaf-dial-label-foot">{label.foot}</span>
          </div>
        </div>
      ) : null}
      {back && way ? (
        <span
          className="mushaf-dial-return"
          data-fading={backFading || undefined}
          style={{ left: returnLeft, width: DIAL_RETURN_HIT }}
          role="button"
          aria-label={`Return to page ${back.page}`}
        >
          <span className="mushaf-dial-return-dot" style={{ width: DIAL_RETURN_DOT, height: DIAL_RETURN_DOT }}>
            <svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true" data-way={way}>
              <path fill="currentColor" d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
            </svg>
          </span>
        </span>
      ) : null}
    </div>
  )
}

/**
 * Chapters and Settings at the fore-edges of the transport row — Android
 * `MushafReadingSheet`. Recited, they recede to almost nothing and cannot be
 * touched, so a thumb at the fore-edge never walks out of the page.
 */
export function MushafTransportEnds({ reciting }: { reciting: boolean }) {
  const stackLayer = useAppSelector((state) => state.stackLayer)
  return (
    <>
      <button
        type="button"
        className="mushaf-transport-end mushaf-transport-end--start"
        aria-label="Chapters"
        data-receded={reciting || undefined}
        disabled={reciting || stackLayer <= COVER_LAYER}
        onClick={() => appStore.revealLayer(COVER_LAYER)}
      >
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
          <path fill="currentColor" d="M19 11H7.83l4.88-4.88c.39-.39.39-1.03 0-1.42a.996.996 0 0 0-1.41 0l-6.59 6.59a.996.996 0 0 0 0 1.41l6.59 6.59a.996.996 0 1 0 1.41-1.41L7.83 13H19c.55 0 1-.45 1-1s-.45-1-1-1z" />
        </svg>
      </button>
      <button
        type="button"
        className="mushaf-transport-end mushaf-transport-end--end"
        aria-label="Settings"
        data-receded={reciting || undefined}
        disabled={reciting}
        onClick={() => appStore.setSheet('settings')}
      >
        <IconTune />
      </button>
    </>
  )
}
