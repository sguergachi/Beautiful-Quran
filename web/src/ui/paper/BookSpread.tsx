import { useLayoutEffect, useRef, type CSSProperties } from 'react'
import { GeneratedRosette } from '../theme/GeneratedOrnament'
import { generateCoverOrnament } from '../theme/ornamentGenerator'
import {
  CHAPTERS_PLACE,
  bookPiles,
  bookRightShare,
  restBook,
  setLooseSheetSlot,
  setTurningLeafSlot,
  setVersoLeafSlot,
  useBookAir,
  useBookPlace,
} from './bookSpread'
import type { StackLayer } from './stack'

/** One medallion for the title page, wherever it is drawn, until the page reloads. */
let titleMedallion:
  | ReturnType<typeof generateCoverOrnament>['medallion']
  | null = null

/**
 * The title page: what the right-hand page shows before a chapter is chosen
 * in the scrolling layout. Mushaf keeps a quiet return page there.
 */
function BookMedallion({ className }: { className: string }) {
  titleMedallion ??= generateCoverOrnament(
    (Math.random() * 0x7fffffff) | 0,
  ).medallion
  return (
    <GeneratedRosette
      spec={titleMedallion}
      built
      animated={false}
      className={className}
      brightGold="var(--gold-bright)"
      deepGold="var(--gold-deep)"
      embossDark="var(--emboss-dark)"
      embossLight="var(--emboss-light)"
    />
  )
}

export function BookTitlePage() {
  return (
    <div className="book-title-page">
      <BookMedallion className="book-title-medallion" />
      <p className="book-title-ar" lang="ar" dir="rtl">
        القرآن الجميل
      </p>
      <p className="book-title-en">Beautiful Quran</p>
      <p className="book-title-hint">Choose a chapter to begin reading</p>
    </div>
  )
}

/**
 * The open book the desktop sheets lie on: binding boards, two pages with
 * their fore-edges, and a title page on the recto until a chapter opens.
 * Drawn under the sheets; hidden entirely outside the spread.
 */
export function BookSpread({
  titlePage,
  versoCovered,
  mushaf,
  leaves,
  stack,
  chapterPage,
  chaptersPage,
  returnPageInert,
  returnPlace,
}: {
  titlePage: boolean
  versoCovered: boolean
  /** The book is read as its printed pages (Mushaf layout). */
  mushaf: boolean
  /** Facing Mushaf leaves are open on the spread. */
  leaves: boolean
  stack: StackLayer
  /** Where a scrolling chapter begins; the leaves report their own place. */
  chapterPage: number | null
  /** Chapters faces a quiet resume page instead of unrelated scripture. */
  chaptersPage: boolean
  returnPageInert: boolean
  returnPlace: {
    chapter: string
    arabic: string
    ayah: number
    ayahCount: number
    onReturn: () => void
  } | null
}) {
  // How the block's leaves are shared between the two piles. Read here, by
  // the one element that draws them: the app shell does not re-render on a
  // page turn, and the eased value restyles the book alone.
  const leavesPlace = useBookPlace()
  // Before a chapter is chosen in Mushaf layout the book is at its start.
  const right = bookRightShare(
    leaves ? leavesPlace : mushaf ? CHAPTERS_PLACE : chapterPage,
  )
  // Facing leaves are turned, so their piles change by the leaves that are
  // turned and when they are: no leaf is on a pile while it is in the air.
  const air = useBookAir()
  const piles = bookPiles(right, leaves ? air : null)
  // The sheet the book rests on, for the reader that is built next: it is
  // told after this render, so a reader built in the same one still finds
  // the book where it was.
  useLayoutEffect(() => {
    restBook({ layer: stack })
  }, [stack])
  // With no reader, nothing else says the leaves lie open at the start.
  useLayoutEffect(() => {
    if (mushaf && !leaves) restBook({ place: CHAPTERS_PLACE })
  }, [mushaf, leaves])
  // While a leaf is in the air, the shell says so: Chapters, which a pile
  // lands as, is not shown under it until it has landed (styles.css).
  const book = useRef<HTMLDivElement>(null)
  const flying = air != null
  useLayoutEffect(() => {
    book.current?.closest('.app-shell')?.toggleAttribute('data-air', flying)
  }, [flying])
  return (
    <>
      <div
        ref={book}
        className="book"
        aria-hidden="true"
        style={
          {
            ['--book-right' as string]: piles.right,
            ...(leaves ? { ['--book-left' as string]: piles.left } : null),
          } as CSSProperties
        }
      >
        <div className="book-page book-page--verso" />
        <div className="book-page book-page--recto">
          {titlePage && !mushaf ? <BookTitlePage /> : null}
        </div>
      </div>
      {chaptersPage ? (
        <section
          className="book-return-page"
          aria-label="Reading place"
          inert={returnPageInert}
        >
          {returnPlace ? (
            <div className="book-resume">
              <BookMedallion className="book-resume-medallion" />
              <p className="book-resume-caption">Your reading place</p>
              <p className="book-resume-ar" lang="ar" dir="rtl">
                {returnPlace.arabic}
              </p>
              <h2 className="book-resume-name">{returnPlace.chapter}</h2>
              <p className="book-resume-location">
                Ayah {returnPlace.ayah} <span>of {returnPlace.ayahCount}</span>
              </p>
              <button
                type="button"
                className="book-return"
                aria-label={`Return to ${returnPlace.chapter}, ayah ${returnPlace.ayah}`}
                onClick={returnPlace.onReturn}
              >
                Return to reading
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M4 12h15m-6-6 6 6-6 6" />
                </svg>
              </button>
            </div>
          ) : (
            <BookTitlePage />
          )}
        </section>
      ) : null}
      {/* Mushaf layout: the reader portals the facing leaf in here. */}
      <div
        className="book-verso-leaf"
        ref={setVersoLeafSlot}
        inert={versoCovered}
        aria-hidden={versoCovered || undefined}
      />
      {/* A loose sheet laid over either page: the root viewer. */}
      <div className="book-loose" ref={setLooseSheetSlot} />
      {/* …and the leaf that is mid-turn, which crosses the spine. */}
      <div className="book-turn" ref={setTurningLeafSlot} aria-hidden="true" />
      {/* The gutter's turn — above the sheets, so both pages curve into it. */}
      <div className="book-spine" aria-hidden="true" />
    </>
  )
}
