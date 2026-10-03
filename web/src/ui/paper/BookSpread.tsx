import { useMemo, type CSSProperties } from 'react'
import { GeneratedRosette } from '../theme/GeneratedOrnament'
import { generateCoverOrnament } from '../theme/ornamentGenerator'
import {
  bookRightShare,
  setLooseSheetSlot,
  setTurningLeafSlot,
  setVersoLeafSlot,
  useBookPlace,
} from './bookSpread'
import type { StackLayer } from './stack'

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
  const medallion = useMemo(
    () => generateCoverOrnament((Math.random() * 0x7fffffff) | 0).medallion,
    [],
  )
  return (
    <>
      <div className="book" aria-hidden="true" style={{ ['--book-right' as string]: right } as CSSProperties}>
        <div className="book-page book-page--verso" />
        <div className="book-page book-page--recto">
          {titlePage ? (
            <div className="book-title-page">
              <GeneratedRosette
                spec={medallion}
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
          ) : null}
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
