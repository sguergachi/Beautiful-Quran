import { useEffect, useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import type { Surah } from '../../data/models'
import { clampAyah, dialIndexAt } from './searchDialsMath'

const ITEM_HEIGHT = 42
const ENTER_MS = 220
const EXIT_MS = 180
const PANE_TOP_GAP = 12
const PANE_BOTTOM_GAP = 10

/**
 * Android `SearchDialsPane`: while the empty search field holds focus, a pane
 * of two dials fades in beneath it over the chapter list. The left dial spins
 * across every surah, the right across the centred surah's ayahs; a gilded
 * band marks the pair, and Open goes straight there. A tap outside the field
 * and the pane dismisses it without opening the row beneath.
 */
export function SearchDials({
  visible,
  surahs,
  anchorRef,
  sheetRef,
  onDismiss,
  onOpen,
}: {
  visible: boolean
  surahs: Surah[]
  anchorRef: RefObject<HTMLElement | null>
  sheetRef: RefObject<HTMLElement | null>
  onDismiss: () => void
  onOpen: (surahId: number, ayah: number) => void
}) {
  const show = visible && surahs.length > 0
  const [rendered, setRendered] = useState(show)
  const [box, setBox] = useState<{ left: number; top: number; width: number; height: number } | null>(null)
  const paneRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (show) {
      setRendered(true)
      return
    }
    const done = window.setTimeout(() => setRendered(false), EXIT_MS)
    return () => window.clearTimeout(done)
  }, [show])

  // Anchored under the field, down to the keyboard (or the sheet's foot).
  useLayoutEffect(() => {
    if (!show) return
    const place = () => {
      const anchor = anchorRef.current
      const sheet = sheetRef.current
      if (!anchor || !sheet) return
      const a = anchor.getBoundingClientRect()
      const s = sheet.getBoundingClientRect()
      const vv = window.visualViewport
      const viewBottom = vv ? vv.offsetTop + vv.height : window.innerHeight
      const top = a.bottom - s.top + PANE_TOP_GAP
      const bottom = Math.min(s.bottom, viewBottom) - s.top - PANE_BOTTOM_GAP
      const next = { left: a.left - s.left, top, width: a.width, height: Math.max(0, bottom - top) }
      setBox((prev) =>
        prev &&
        Math.abs(prev.left - next.left) < 0.5 &&
        Math.abs(prev.top - next.top) < 0.5 &&
        Math.abs(prev.width - next.width) < 0.5 &&
        Math.abs(prev.height - next.height) < 0.5
          ? prev
          : next,
      )
    }
    place()
    // The masthead recedes (~220ms) and the list lifts as the field takes
    // the top: follow the field every frame until it has settled.
    const until = performance.now() + 600
    let frame = 0
    const follow = () => {
      place()
      if (performance.now() < until) frame = requestAnimationFrame(follow)
    }
    frame = requestAnimationFrame(follow)
    window.visualViewport?.addEventListener('resize', place)
    window.addEventListener('resize', place)
    return () => {
      cancelAnimationFrame(frame)
      window.visualViewport?.removeEventListener('resize', place)
      window.removeEventListener('resize', place)
    }
  }, [show, anchorRef, sheetRef])

  // An outside tap only dismisses: it must not open the row under the pane.
  useEffect(() => {
    if (!show) return
    let swallowUntil = 0
    const onDown = (event: PointerEvent) => {
      const target = event.target as Node | null
      if (!target) return
      if (anchorRef.current?.contains(target) || paneRef.current?.contains(target)) return
      event.preventDefault()
      event.stopPropagation()
      swallowUntil = event.timeStamp + 800
      onDismiss()
    }
    const onClick = (event: MouseEvent) => {
      if (event.timeStamp > swallowUntil) return
      swallowUntil = 0
      event.preventDefault()
      event.stopPropagation()
    }
    window.addEventListener('pointerdown', onDown, true)
    window.addEventListener('click', onClick, true)
    return () => {
      window.removeEventListener('pointerdown', onDown, true)
      // Leave the click guard a moment: the dismissing tap's click follows.
      window.setTimeout(() => window.removeEventListener('click', onClick, true), 800)
    }
  }, [show, anchorRef, onDismiss])

  if (!rendered || !box || box.height < 120) return null
  return (
    <div
      ref={paneRef}
      className="search-dials"
      data-visible={show || undefined}
      style={{
        left: box.left,
        top: box.top,
        width: box.width,
        height: box.height,
        transitionDuration: `${show ? ENTER_MS : EXIT_MS}ms`,
      }}
      // Spinning a dial must not take focus from the field (that would put
      // the pane away under the finger).
      onPointerDown={(event) => event.preventDefault()}
      onMouseDown={(event) => event.preventDefault()}
      inert={!show || undefined}
    >
      <Dials surahs={surahs} onOpen={onOpen} />
    </div>
  )
}

function Dials({ surahs, onOpen }: { surahs: Surah[]; onOpen: (surahId: number, ayah: number) => void }) {
  const [surahIndex, setSurahIndex] = useState(0)
  const [ayah, setAyah] = useState(1)
  const current = surahs[Math.min(surahIndex, surahs.length - 1)]!
  const boundedAyah = clampAyah(ayah, current.ayahCount)
  return (
    <>
      <div className="search-dials-header">
        <span className="search-dials-label search-dials-label-surah">Surah</span>
        <span className="search-dials-label search-dials-label-ayah">Ayah</span>
      </div>
      <div className="search-dials-wheels">
        <span className="search-dials-band" aria-hidden="true" />
        <DialWheel
          className="search-dial-surah"
          label="Surah"
          count={surahs.length}
          selected={surahIndex}
          onSelect={setSurahIndex}
          valueText={`${current.id} ${current.nameTransliteration}`}
        >
          {(index, selected) => {
            const surah = surahs[index]!
            return (
              <span className="search-dial-surah-item" data-selected={selected || undefined}>
                <span className="search-dial-surah-num">{surah.id}</span>
                <span className="search-dial-surah-name">{surah.nameTransliteration}</span>
                <span className="search-dial-surah-ar" lang="ar" dir="rtl">{surah.nameArabic}</span>
              </span>
            )
          }}
        </DialWheel>
        <DialWheel
          className="search-dial-ayah"
          label="Ayah"
          count={current.ayahCount}
          selected={boundedAyah - 1}
          onSelect={(index) => setAyah(index + 1)}
          valueText={String(boundedAyah)}
        >
          {(index, selected) => (
            <span className="search-dial-ayah-item" data-selected={selected || undefined}>
              {index + 1}
            </span>
          )}
        </DialWheel>
      </div>
      <div className="search-dials-go">
        <span className="search-dials-target">
          {current.nameTransliteration} · Ayah {boundedAyah}
        </span>
        <button
          type="button"
          className="search-dials-open"
          onClick={() => onOpen(current.id, boundedAyah)}
        >
          Open
        </button>
      </div>
    </>
  )
}

/** A vertical snap dial: rows scroll under a fixed centre slot, and the row
 * resting in the centre is the selection (Android `SearchDialWheel`). */
function DialWheel({
  className,
  label,
  count,
  selected,
  onSelect,
  valueText,
  children,
}: {
  className: string
  label: string
  count: number
  selected: number
  onSelect: (index: number) => void
  valueText: string
  children: (index: number, selected: boolean) => ReactNode
}) {
  const ref = useRef<HTMLDivElement>(null)
  const [edge, setEdge] = useState(0)
  const onSelectRef = useRef(onSelect)
  onSelectRef.current = onSelect

  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const measure = () => setEdge(Math.max(0, (el.clientHeight - ITEM_HEIGHT) / 2))
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    const el = ref.current
    if (!el) return
    let frame = 0
    const onScroll = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => onSelectRef.current(dialIndexAt(el.scrollTop, ITEM_HEIGHT, count)))
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      cancelAnimationFrame(frame)
      el.removeEventListener('scroll', onScroll)
    }
  }, [count])

  const nudge = (index: number) => {
    ref.current?.scrollTo({ top: index * ITEM_HEIGHT, behavior: 'smooth' })
  }

  return (
    <div
      ref={ref}
      className={`search-dial ${className}`}
      role="slider"
      aria-label={label}
      aria-valuemin={1}
      aria-valuemax={count}
      aria-valuenow={selected + 1}
      aria-valuetext={valueText}
      style={{ paddingBlock: edge }}
    >
      {Array.from({ length: count }, (_, index) => (
        <div
          key={index}
          className="search-dial-item"
          onClick={() => nudge(index)}
        >
          {children(index, index === selected)}
        </div>
      ))}
    </div>
  )
}
