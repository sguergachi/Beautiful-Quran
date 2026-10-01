import {
  Fragment,
  useCallback,
  useEffect,
  useMemo,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type Ref,
} from 'react'
import { createPortal } from 'react-dom'
import { QuranRepository } from '../../data/repository'
import { runtimeMushafCache } from '../../data/runtimeMushaf'
import {
  MUSHAF_OPENING_PAGES,
  MUSHAF_PAGE_COUNT,
  mushafFacingPages,
  mushafTokenEndsAyah,
} from '../../domain/mushafPage'
import { formatAyahNumberMark, pageFolioLayout } from '../../util/digits'
import type { PageNumberScript } from '../../data/settings'
import { appStore } from '../../store/appStore'
import { useBookSpread, useTurningLeafSlot, useVersoLeafSlot } from '../paper/bookSpread'
import { COVER_LAYER, READER_LAYER } from '../paper/stack'
import { TurningLeaf } from './TurningLeaf'
import { finishPageTurn, requestPageTurn, type PageTurnQueue } from './pageTurnQueue'
import { mushafFit, mushafLeafFit, naturalLineWidth } from './mushafFit'
import { mushafLeafModel } from './mushafLeafModel'
import { PAGE_TURN_SCHEDULE, playFlip, warmPageTurnSounds } from '../paper/pageTurnSounds'

/** Keep in step with `mushaf-leaf-turn` in styles.css. */
const PAGE_TURN_MS = 760

/** Playback highlight when it exists; otherwise the ayah the sheet was opened on. */
function followedAyah(activeAyah: number | null, openAyah: number): number {
  return activeAyah != null && activeAyah > 0 ? activeAyah : Math.max(1, openAyah)
}

/**
 * One Madinah leaf. The 604 page boundaries come from the Quran Foundation
 * page map once it is on this device. The words are Hafs: the per-page QCF
 * faces are not shipped to the browser.
 */
export function MushafReader({
  ownsKeyboard,
  activeSurahId,
  activeAyah,
  openAyah,
  openRevision,
  english,
  pageNumberScript,
  onPlayWord,
}: {
  pageNumberScript: PageNumberScript
  ownsKeyboard: boolean
  activeSurahId: number
  activeAyah: number | null
  openAyah: number
  openRevision: number
  english: boolean
  onPlayWord: (surahId: number, ayah: number, position: number) => void
}) {
  const targetAyah = followedAyah(activeAyah, openAyah)
  // Swipes stick until the opened ayah or the recited ayah changes.
  const followKey = `${activeSurahId}:${openRevision}:${targetAyah}`
  const [manualPage, setManualPage] = useState<number | null>(null)
  const [manualFor, setManualFor] = useState(followKey)
  if (manualFor !== followKey) {
    setManualFor(followKey)
    setManualPage(null)
  }
  const [ready, setReady] = useState(() => runtimeMushafCache?.layoutReady() ?? false)
  const spread = useBookSpread()
  const versoSlot = useVersoLeafSlot()
  const turnSlot = useTurningLeafSlot()
  // Two facing leaves on a desktop spread; one leaf everywhere else.
  const facing = spread && versoSlot != null
  const rectoRef = useRef<HTMLDivElement>(null)
  const [versoBox, setVersoBox] = useState<CSSProperties>({})
  const [fitRevision, setFitRevision] = useState(0)
  const [fontsReady, setFontsReady] = useState(() => !document.fonts || document.fonts.status === 'loaded')
  const [fits, setFits] = useState<Record<number, number>>({})
  const reportFit = useCallback((page: number, fit: number) => {
    setFits((current) => current[page] != null && Math.abs(current[page] - fit) < 0.002
      ? current : { ...current, [page]: fit })
  }, [])
  const [reduced, setReduced] = useState(() => window.matchMedia('(prefers-reduced-motion: reduce)').matches)
  useEffect(() => {
    const query = window.matchMedia('(prefers-reduced-motion: reduce)')
    const changed = () => setReduced(query.matches)
    query.addEventListener('change', changed)
    return () => query.removeEventListener('change', changed)
  }, [])

  useEffect(() => {
    warmPageTurnSounds()
  }, [])

  useEffect(() => {
    if (!runtimeMushafCache) return
    return runtimeMushafCache.subscribe(() => setReady(runtimeMushafCache.layoutReady()))
  }, [])

  const derived = ready && runtimeMushafCache
    ? runtimeMushafCache.pageOfAyah(activeSurahId, targetAyah)
    : null
  const page = manualPage ?? derived ?? 1

  const turn = (delta: number) => {
    setManualPage((current) => {
      const base = current ?? derived ?? 1
      // A spread turns both of its leaves at once.
      const from = facing ? mushafFacingPages(base).right : base
      return Math.min(MUSHAF_PAGE_COUNT, Math.max(1, from + delta * (facing ? 2 : 1)))
    })
  }

  // The facing leaf hangs under the verso sheets, outside this one. Give it
  // the recto leaf's own box so the two pages' lines sit on one grid.
  useLayoutEffect(() => {
    const leaf = rectoRef.current
    const sheet = leaf?.closest('.sheet')
    if (!leaf || !sheet) return
    let size = ''
    let disposed = false
    const measure = () => {
      if (disposed) return
      const box = leaf.getBoundingClientRect()
      const page = sheet.getBoundingClientRect()
      const nextSize = `${box.width}:${box.height}`
      if (size !== nextSize) {
        size = nextSize
        setFits({})
        setFitRevision((revision) => revision + 1)
      }
      setVersoBox({
        top: box.top - page.top,
        left: box.left - page.left,
        width: box.width,
        height: box.height,
      })
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(leaf)
    observer.observe(sheet)
    void document.fonts?.ready.then(() => {
      if (!disposed) {
        setFontsReady(true)
        setFits({})
        setFitRevision((revision) => revision + 1)
      }
    })
    return () => { disposed = true; observer.disconnect() }
  }, [facing, ready, english])

  // Pages run right to left: the left arrow goes on, the right arrow back.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!ownsKeyboard || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
      // Only fields that use the arrows themselves keep them. A focused
      // button does not — on a phone the chapter row that opened this
      // reader still holds focus behind it, and would swallow every turn.
      const target = event.target
      if (
        target instanceof HTMLElement &&
        (target.isContentEditable || target.closest('input, textarea, select, [role="slider"]'))
      ) {
        return
      }
      event.preventDefault()
      turn(event.key === 'ArrowLeft' ? 1 : -1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const place = facing ? mushafFacingPages(page).right : page
  const [queue, setQueue] = useState<PageTurnQueue>({ settled: place, flight: null, queued: null })
  const footing = `${ready}:${facing}:${english}:${pageNumberScript}`
  const [settledFooting, setSettledFooting] = useState(footing)
  const [motion, setMotion] = useState<{
    flight: NonNullable<PageTurnQueue['flight']>
    fit: number
    fromFit: number
    initiatedAt: number
    props: typeof leafProps
    box: CSSProperties
  } | null>(null)
  const leafProps = { fitRevision, activeSurahId, activeAyah, english, pageNumberScript, onPlayWord, onTurn: turn, onFit: reportFit }
  const reset = settledFooting !== footing || reduced || !ownsKeyboard
  useLayoutEffect(() => {
    if (reset) {
      setSettledFooting(footing)
      setQueue({ settled: place, flight: null, queued: null })
      setMotion(null)
    } else {
      setQueue((current) => requestPageTurn(current, place))
    }
  }, [place, footing, reset])

  const destination = queue.flight?.to ?? queue.settled
  const pair = facing ? mushafFacingPages(destination) : { right: destination, left: destination }
  const preparedFit = mushafFit(destination, facing, fits)
  const fitReady = fontsReady && preparedFit != null
  useLayoutEffect(() => {
    if (reset || !queue.flight || motion || !fitReady) return
    setMotion({
      flight: queue.flight,
      fit: preparedFit!,
      fromFit: mushafFit(queue.flight.from, facing, fits) ?? 1,
      initiatedAt: performance.now(),
      props: leafProps,
      box: versoBox,
    })
    // The page, ink and geometry in the air stay frozen until it lands.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queue.flight, fitReady, motion, reset])
  const landedFlight = useRef<PageTurnQueue['flight']>(null)
  const cancelSoundRef = useRef<(() => void) | null>(null)
  useEffect(() => () => cancelSoundRef.current?.(), [])
  const endTurn = useCallback(() => {
    landedFlight.current = motion?.flight ?? null
    setQueue((current) => finishPageTurn(current, motion?.flight ?? null))
    setMotion((current) => current === motion ? null : current)
  }, [motion])
  useEffect(() => {
    if (!motion || reset) return
    cancelSoundRef.current?.()
    const cancelSound = playFlip(PAGE_TURN_SCHEDULE, motion.initiatedAt)
    cancelSoundRef.current = cancelSound
    const timer = window.setTimeout(endTurn, PAGE_TURN_MS + 250)
    return () => {
      window.clearTimeout(timer)
      // A normal landing lets the drop ring out; cancellation cuts its stems.
      if (landedFlight.current !== motion.flight) cancelSound()
    }
  }, [motion, reset, endTurn])

  const turning = motion != null && !reset
  const settled = reset ? place : queue.settled
  const landing = turning ? motion.flight.to : settled
  const forward = landing > settled
  const fit = turning ? motion.fit : (mushafFit(settled, facing, fits) ?? 1)
  const air = useMemo(() => {
    if (!motion) return null
    const from = mushafFacingPages(motion.flight.from)
    const to = mushafFacingPages(motion.flight.to)
    const on = motion.flight.to > motion.flight.from
    const props = { ...motion.props, onFit: noFit }
    return {
      face: <MushafLeaf page={facing ? on ? from.left : from.right : Math.min(motion.flight.from, motion.flight.to)}
        fit={facing || on ? motion.fromFit : motion.fit}
        side={facing ? on ? 'verso' : 'recto' : undefined} style={facing ? motion.box : undefined} {...props} />,
      back: facing ? <MushafLeaf page={on ? to.right : to.left} fit={motion.fit} side={on ? 'recto' : 'verso'} style={motion.box} {...props} /> : undefined,
    }
  }, [motion, facing])
  // Two incoming leaves are measured once, before any strips mount.
  const probes = !reset && queue.flight && !motion ? (
    <div className="mushaf-fit-probes" inert aria-hidden="true">
      {[...new Set([pair.right, pair.left])].filter((page) => fits[page] == null).map((page) => (
        <MushafLeaf key={page} page={page} fit={1} {...leafProps} />
      ))}
    </div>
  ) : null

  if (!ready || !runtimeMushafCache) {
    return (
      <div className="mushaf-wait">
        <p>The printed page is still arriving.</p>
        <p>It uses the same page map as the book, and fills in on its own.</p>
      </div>
    )
  }

  if (!facing) {
    // One leaf, bound at its right edge. Going on, the old leaf lifts away
    // to the right over the new one; going back, the earlier leaf comes
    // down from the right onto the one being left.
    const under = turning ? Math.max(settled, landing) : settled
    return (
      <>
        <MushafLeaf page={under} fit={turning && !forward ? motion.fromFit : fit} leafRef={rectoRef} {...leafProps} onFit={turning ? noFit : reportFit} />
        {probes}
        {turning ? (
          <TurningLeaf
            key={`${settled}:${landing}`}
            single
            hinge="right"
            dir={forward ? 'on' : 'back'}
            face={air?.face}
            onEnd={endTurn}
          />
        ) : null}
      </>
    )
  }

  const now = mushafFacingPages(landing)
  const was = mushafFacingPages(settled)
  // The leaf between the two spreads carries the old spread's inner page on
  // its face and the new spread's on its back. What it uncovers is already
  // the new page; what it has yet to cover is still the old one.
  const rectoPage = turning && forward ? was.right : now.right
  const versoPage = turning && !forward ? was.left : now.left
  return (
    <>
      <MushafLeaf page={rectoPage} side="recto" fit={turning && forward ? motion.fromFit : fit} leafRef={rectoRef} {...leafProps} onFit={turning ? noFit : reportFit} />
      {probes}
      <button
        type="button"
        className="mushaf-turn mushaf-turn--back"
        aria-label="Previous pages"
        disabled={now.right <= 1}
        onClick={() => turn(-1)}
      >
        <span aria-hidden="true">›</span>
      </button>
      {versoSlot
        ? createPortal(
            <>
              <MushafLeaf page={versoPage} side="verso" fit={turning && !forward ? motion.fromFit : fit} style={versoBox} {...leafProps} onFit={turning ? noFit : reportFit} />
              <button
                type="button"
                className="mushaf-turn mushaf-turn--forward"
                aria-label="Next pages"
                disabled={now.left >= MUSHAF_PAGE_COUNT}
                onClick={() => turn(1)}
              >
                <span aria-hidden="true">‹</span>
              </button>
            </>,
            versoSlot,
          )
        : null}
      {turning && turnSlot
        ? createPortal(
            <TurningLeaf
              key={`${settled}:${landing}`}
              hinge={forward ? 'right' : 'left'}
              dir={forward ? 'on' : 'back'}
              face={air?.face}
              back={air?.back}
              onEnd={endTurn}
            />,
            turnSlot,
          )
        : null}
    </>
  )
}

function noFit() {}

/** One printed page: running head, fifteen lines (or its translation), folio. */
function MushafLeaf({
  page,
  fitRevision,
  side,
  style,
  leafRef,
  activeSurahId,
  activeAyah,
  english,
  pageNumberScript,
  onPlayWord,
  onTurn,
  fit,
  onFit,
}: {
  page: number
  fitRevision: number
  /** The hand both facing leaves are set in: the tighter page's fit. */
  fit: number
  onFit: (page: number, fit: number) => void
  /** Which page of a desktop spread this leaf is; absent on a single leaf. */
  side?: 'recto' | 'verso'
  style?: CSSProperties
  leafRef?: Ref<HTMLDivElement>
  activeSurahId: number
  activeAyah: number | null
  english: boolean
  pageNumberScript: PageNumberScript
  onPlayWord: (surahId: number, ayah: number, position: number) => void
  onTurn: (delta: number) => void
}) {
  const drag = useRef<{ x: number; y: number } | null>(null)
  const linesRef = useRef<HTMLDivElement>(null)

  // Hafs stands in for the page's own face, so a printed line sets wider or
  // narrower than the page. Measure every line at full size, word spaces at
  // their minimum, and report how the widest compares with the page.
  useLayoutEffect(() => {
    const lines = linesRef.current
    // A leaf in the air is a picture at the size already found. Measuring
    // every copy of it forces a layout per strip as the turn starts.
    if (onFit === noFit) return
    if (english) { onFit(page, 1); return }
    if (!lines) return
    let disposed = false
    const measure = () => {
      if (disposed) return
      const leaf = lines.closest('.mushaf')
      if (!(leaf instanceof HTMLElement)) return
      // Widths are read at the scale already applied and divided back out:
      // every length in a line is in em, so they scale with the type exactly.
      // Rewriting the scale to measure at full size re-laid the block and
      // let the measurement chase its own result.
      const scale = Number(lines.style.getPropertyValue('--mushaf-fit')) || 1
      const first = lines.firstElementChild
      const gap = first ? parseFloat(getComputedStyle(first).columnGap) || 0 : 0
      const widest = opening
        ? 0
        : Math.max(0, ...Array.from(lines.children, (line) => naturalLineWidth(
            Array.from(line.children, (item) => item.getBoundingClientRect().width),
            gap,
          ))) / scale
      const box = getComputedStyle(leaf)
      const available = leaf.clientWidth - parseFloat(box.paddingLeft) - parseFloat(box.paddingRight)
      onFit(page, mushafLeafFit(available, widest))
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(lines)
    // The block is sized by its own lines, so it does not change when the
    // page around it does. Watch the page as well.
    const leaf = lines.closest('.mushaf')
    if (leaf) observer.observe(leaf)
    void document.fonts?.ready.then(measure)
    return () => { disposed = true; observer.disconnect() }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, english, side, fitRevision, onFit === noFit])

  if (!runtimeMushafCache) return null
  const rows = runtimeMushafCache.pageWords(page) ?? []
  const { leaf, headSurah, englishAyahs, lastPositions } = mushafLeafModel(page, rows, QuranRepository.surahContent)
  const folio = pageFolioLayout(page, english ? 'english' : pageNumberScript)
  // The two opening leaves carry a few short lines, set in the middle of the
  // well; every other leaf hangs its fifteen lines from the head.
  const opening = page <= MUSHAF_OPENING_PAGES
  const lines = opening ? leaf.lines.filter((line) => line.tokens.length > 0) : leaf.lines

  return (
    <div
      className="mushaf"
      dir={english ? 'ltr' : 'rtl'}
      data-side={side}
      style={style}
      ref={leafRef}
      onPointerDown={(event) => {
        drag.current = { x: event.clientX, y: event.clientY }
        // Chapters lies over the facing leaf; touching the open page puts
        // it away, as touching the reader's peek does on the deck.
        if (side === 'recto' && appStore.getSnapshot().stackLayer === COVER_LAYER) {
          appStore.revealLayer(READER_LAYER)
        }
      }}
      onPointerUp={(event) => {
        const start = drag.current
        drag.current = null
        if (!start) return
        const dx = event.clientX - start.x
        const dy = event.clientY - start.y
        if (Math.abs(dx) < 48 || Math.abs(dx) < Math.abs(dy)) return
        // The next leaf is to the left, so a finger moving right turns forward.
        onTurn(dx > 0 ? 1 : -1)
      }}
      onPointerCancel={() => {
        drag.current = null
      }}
    >
      {/* One measure: head, text and folio share the text block's width. */}
      <div className="mushaf-block">
      <div className="mushaf-head">
        <span>{headSurah?.nameTransliteration ?? ''}</span>
        <span>{headSurah?.nameArabic ?? ''}</span>
      </div>
      {english ? (
        <div className="mushaf-english">
          {englishAyahs.map((item) => {
            const active = item.surahId === activeSurahId && item.ayah === activeAyah
            return (
              <p
                key={`${item.surahId}:${item.ayah}`}
                className="mushaf-english-ayah"
                data-active={active || undefined}
              >
                <button
                  type="button"
                  className="mushaf-english-text"
                  onClick={() => onPlayWord(item.surahId, item.ayah, 1)}
                >
                  {item.translation}
                </button>
                <button
                  type="button"
                  className="mushaf-mark"
                  aria-label={`Gather ayah ${item.ayah}`}
                  onClick={(event) => {
                    event.stopPropagation()
                    appStore.onMarkTap(item.surahId, item.ayah)
                  }}
                >
                  {formatAyahNumberMark(item.ayah, false)}
                </button>
              </p>
            )
          })}
        </div>
      ) : (
        <div
          className="mushaf-lines"
          data-opening={opening || undefined}
          ref={linesRef}
          style={{ ['--mushaf-fit' as string]: String(fit) }}
        >
          {lines.map((line) => (
            <p key={line.number} className="mushaf-line" lang="ar">
              {line.tokens.map((token) => {
                const active =
                  token.surahId === activeSurahId && token.ayah === activeAyah
                const endsAyah = mushafTokenEndsAyah(
                  token.position,
                  lastPositions.get(`${token.surahId}:${token.ayah}`) ?? 0,
                )
                // Word and mark are separate items of the line, so the
                // justified space falls evenly on both sides of a mark.
                return (
                  <Fragment key={`${token.surahId}:${token.ayah}:${token.position}`}>
                    <button
                      type="button"
                      className="mushaf-word"
                      data-active={active || undefined}
                      onClick={() => onPlayWord(token.surahId, token.ayah, token.position)}
                    >
                      {token.arabic}
                    </button>
                    {endsAyah ? (
                      <button
                        type="button"
                        className="mushaf-mark"
                        aria-label={`Gather ayah ${token.ayah}`}
                        onClick={(event) => {
                          event.stopPropagation()
                          appStore.onMarkTap(token.surahId, token.ayah)
                        }}
                      >
                        {formatAyahNumberMark(token.ayah, true)}
                      </button>
                    ) : null}
                  </Fragment>
                )
              })}
            </p>
          ))}
        </div>
      )}
      <div className="mushaf-folio">
        {folio.trailing != null ? (
          <>
            <span lang="ar">{folio.trailing}</span>
            <span className="mushaf-folio-diamond" aria-hidden="true" />
            <span>{folio.leading}</span>
          </>
        ) : (
          <span lang={pageNumberScript === 'arabic' ? 'ar' : undefined}>{folio.leading}</span>
        )}
      </div>
      </div>
    </div>
  )
}
