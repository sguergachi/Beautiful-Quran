import {
  useEffect,
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
  buildMushafPage,
  MUSHAF_OPENING_PAGES,
  MUSHAF_PAGE_COUNT,
  mushafFacingPages,
  mushafTokenEndsAyah,
  pageAyahs,
  type MushafWordPlacement,
} from '../../domain/mushafPage'
import { formatAyahNumberMark, pageFolioLayout } from '../../util/digits'
import type { PageNumberScript } from '../../data/settings'
import { appStore } from '../../store/appStore'
import { useBookSpread, useTurningLeafSlot, useVersoLeafSlot } from '../paper/bookSpread'
import { COVER_LAYER, READER_LAYER } from '../paper/stack'
import { TurningLeaf } from './TurningLeaf'

/** Keep in step with `mushaf-leaf-turn` in styles.css. */
const PAGE_TURN_MS = 760

/** Playback highlight when it exists; otherwise the ayah the sheet was opened on. */
function followedAyah(activeAyah: number | null, openAyah: number): number {
  return activeAyah != null && activeAyah > 0 ? activeAyah : Math.max(1, openAyah)
}

function ayahLastPosition(surahId: number, ayahNumber: number): number {
  const verse = QuranRepository.surahContent(surahId).ayahs.find((item) => item.number === ayahNumber)
  let last = 0
  for (const word of verse?.words ?? []) {
    if (word.position > last) last = word.position
  }
  return last
}

/**
 * One Madinah leaf. The 604 page boundaries come from the Quran Foundation
 * page map once it is on this device. The words are Hafs: the per-page QCF
 * faces are not shipped to the browser.
 */
export function MushafReader({
  activeSurahId,
  activeAyah,
  openAyah,
  openRevision,
  english,
  pageNumberScript,
  onPlayWord,
}: {
  pageNumberScript: PageNumberScript
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
  // Facing leaves share one hand: the tighter page sets the size for both.
  const [fits, setFits] = useState<{ recto: number; verso: number }>({ recto: 1, verso: 1 })
  const reportFit = (side: 'recto' | 'verso' | undefined, fit: number) => {
    const key = side ?? 'recto'
    setFits((current) => (Math.abs(current[key] - fit) < 0.002 ? current : { ...current, [key]: fit }))
  }

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
    if (!facing || !leaf || !sheet) return
    const measure = () => {
      const box = leaf.getBoundingClientRect()
      const page = sheet.getBoundingClientRect()
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
    return () => observer.disconnect()
  }, [facing, ready])

  // Pages run right to left: the left arrow goes on, the right arrow back.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
      const target = event.target
      if (
        target instanceof HTMLElement &&
        (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName))
      ) {
        return
      }
      event.preventDefault()
      turn(event.key === 'ArrowLeft' ? 1 : -1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  // Where the book lies open, and where it lay before the leaf in the air
  // began to move. They differ only while a turn is running.
  const place = facing ? mushafFacingPages(page).right : page
  const [settled, setSettled] = useState(place)
  // Neither the page map arriving nor a resize between one leaf and two is
  // a page turn: the book is simply found open there.
  const footing = `${ready}:${facing}`
  const [settledFooting, setSettledFooting] = useState(footing)
  if (settledFooting !== footing) {
    setSettledFooting(footing)
    setSettled(place)
  }
  const turning = settled !== place && settledFooting === footing
  const forward = place > settled
  useEffect(() => {
    if (!turning) return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setSettled(place)
      return
    }
    // A hidden tab never fires animationend; do not leave a leaf in the air.
    const timer = window.setTimeout(() => setSettled(place), PAGE_TURN_MS + 250)
    return () => window.clearTimeout(timer)
  }, [turning, place])

  if (!ready || !runtimeMushafCache) {
    return (
      <div className="mushaf-wait">
        <p>The printed page is still arriving.</p>
        <p>It uses the same page map as the book, and fills in on its own.</p>
      </div>
    )
  }

  const leafProps = {
    activeSurahId,
    activeAyah,
    english,
    pageNumberScript,
    onPlayWord,
    onTurn: turn,
    onFit: reportFit,
  }
  // The leaf in the air is a picture of a page, not a second control.
  const airProps = { ...leafProps, onFit: noFit }
  const flipKey = `${settled}:${place}`
  const endTurn = () => setSettled(place)

  if (!facing) {
    // One leaf, bound at its right edge. Going on, the old leaf lifts away
    // to the right over the new one; going back, the earlier leaf comes
    // down from the right onto the one being left.
    const under = turning ? Math.max(settled, place) : place
    const inAir = Math.min(settled, place)
    return (
      <>
        <MushafLeaf page={under} fit={fits.recto} {...leafProps} />
        {turning ? (
          <TurningLeaf
            key={flipKey}
            single
            hinge="right"
            dir={forward ? 'on' : 'back'}
            face={() => <MushafLeaf page={inAir} fit={fits.recto} {...airProps} />}
            onEnd={endTurn}
          />
        ) : null}
      </>
    )
  }

  const fit = Math.min(fits.recto, fits.verso)
  const now = mushafFacingPages(place)
  const was = mushafFacingPages(settled)
  // The leaf between the two spreads carries the old spread's inner page on
  // its face and the new spread's on its back. What it uncovers is already
  // the new page; what it has yet to cover is still the old one.
  const rectoPage = turning && forward ? was.right : now.right
  const versoPage = turning && !forward ? was.left : now.left
  return (
    <>
      <MushafLeaf page={rectoPage} side="recto" fit={fit} leafRef={rectoRef} {...leafProps} />
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
              <MushafLeaf page={versoPage} side="verso" fit={fit} style={versoBox} {...leafProps} />
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
              key={flipKey}
              hinge={forward ? 'right' : 'left'}
              dir={forward ? 'on' : 'back'}
              face={() => (
                <MushafLeaf
                  page={forward ? was.left : was.right}
                  side={forward ? 'verso' : 'recto'}
                  fit={fit}
                  style={versoBox}
                  {...airProps}
                />
              )}
              back={() => (
                <MushafLeaf
                  page={forward ? now.right : now.left}
                  side={forward ? 'recto' : 'verso'}
                  fit={fit}
                  style={versoBox}
                  {...airProps}
                />
              )}
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
  /** Scale on the line's type so the page's longest line stays inside the measure. */
  fit: number
  onFit: (side: 'recto' | 'verso' | undefined, fit: number) => void
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

  // Hafs stands in for the page's own face, so a printed line can set wider
  // than the measure. Measure at full size and report the scale that fits.
  useLayoutEffect(() => {
    const lines = linesRef.current
    if (!lines) return
    const measure = () => {
      const applied = lines.style.getPropertyValue('--mushaf-fit')
      lines.style.setProperty('--mushaf-fit', '1')
      let ratio = 1
      for (const line of lines.children) {
        if (line.scrollWidth > line.clientWidth) {
          ratio = Math.min(ratio, line.clientWidth / line.scrollWidth)
        }
      }
      lines.style.setProperty('--mushaf-fit', applied)
      // A hair under, so rounding never leaves the last word a pixel over.
      onFit(side, ratio < 1 ? ratio * 0.99 : 1)
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(lines)
    void document.fonts?.ready.then(measure)
    return () => observer.disconnect()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, english, side])

  if (!runtimeMushafCache) return null
  const rows = runtimeMushafCache.pageWords(page) ?? []
  const placements: MushafWordPlacement[] = rows.map((row) => {
    const content = QuranRepository.surahContent(row.surah_id)
    const ayah = content.ayahs.find((item) => item.number === row.ayah_number)
    const word = ayah?.words.find((item) => item.position === row.position)
    return {
      surahId: row.surah_id,
      ayah: row.ayah_number,
      position: row.position,
      line: row.qcf_line,
      arabic: word?.arabic ?? '',
    }
  })
  const leaf = buildMushafPage(page, placements)
  const head = placements[0]
  const headSurah = head ? QuranRepository.surahContent(head.surahId).surah : null
  const englishAyahs = pageAyahs(placements)
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
      <div className="mushaf-head">
        <span>{headSurah?.nameTransliteration ?? ''}</span>
        <span>{headSurah?.nameArabic ?? ''}</span>
      </div>
      {english ? (
        <div className="mushaf-english">
          {englishAyahs.map((item) => {
            const content = QuranRepository.surahContent(item.surahId)
            const ayah = content.ayahs.find((candidate) => candidate.number === item.ayah)
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
                  {ayah?.translation}
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
                  ayahLastPosition(token.surahId, token.ayah),
                )
                return (
                  <span key={`${token.surahId}:${token.ayah}:${token.position}`}>
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
                  </span>
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
  )
}
