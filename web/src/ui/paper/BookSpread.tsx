import { useLayoutEffect, type CSSProperties } from 'react'
import { GeneratedRosette } from '../theme/GeneratedOrnament'
import { generateCoverOrnament } from '../theme/ornamentGenerator'
import {
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
let titleMedallion: ReturnType<typeof generateCoverOrnament>['medallion'] | null = null

/**
 * The title page: what the right-hand page shows before a chapter is chosen.
 * Also the face of the pile turned to reach the first chapter (MushafReader).
 */
export function BookTitlePage() {
  titleMedallion ??= generateCoverOrnament((Math.random() * 0x7fffffff) | 0).medallion
  return (
    <div className="book-title-page">
      <GeneratedRosette
        spec={titleMedallion}
        built
        animated={false}
        className="book-title-medallion"
        brightGold="var(--gold-bright)"
        deepGold="var(--gold-deep)"
        embossDark="var(--emboss-dark)"
        embossLight="var(--emboss-light)"
      />
      <p className="book-title-ar" lang="ar" dir="rtl">
        القرآن الكريم
      </p>
      <p className="book-title-en">The Noble Quran</p>
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
  leaves,
  stack,
  chapterPage,
}: {
  titlePage: boolean
  versoCovered: boolean
  /** Facing Mushaf leaves are open on the spread. */
  leaves: boolean
  stack: StackLayer
  /** Where a scrolling chapter begins; the leaves report their own place. */
  chapterPage: number | null
}) {
  // How the block's leaves are shared between the two piles. Read here, by
  // the one element that draws them: the app shell does not re-render on a
  // page turn, and the eased value restyles the book alone.
  const leavesPlace = useBookPlace()
  const right = bookRightShare(leaves, stack, leaves ? leavesPlace : chapterPage)
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
  return (
    <>
      <div
        className="book"
        aria-hidden="true"
        style={{
          ['--book-right' as string]: piles.right,
          ...(leaves ? { ['--book-left' as string]: piles.left } : null),
        } as CSSProperties}
      >
        <div className="book-page book-page--verso" />
        <div className="book-page book-page--recto">
          {titlePage ? <BookTitlePage /> : null}
        </div>
      </div>
      {/* Mushaf layout: the reader portals the facing leaf in here. */}
      <div className="book-verso-leaf" ref={setVersoLeafSlot} inert={versoCovered} aria-hidden={versoCovered || undefined} />
      {/* A loose sheet laid over either page: the root viewer. */}
      <div className="book-loose" ref={setLooseSheetSlot} />
      {/* …and the leaf that is mid-turn, which crosses the spine. */}
      <div className="book-turn" ref={setTurningLeafSlot} aria-hidden="true" />
      {/* The gutter's turn — above the sheets, so both pages curve into it. */}
      <div className="book-spine" aria-hidden="true" />
    </>
  )
}
