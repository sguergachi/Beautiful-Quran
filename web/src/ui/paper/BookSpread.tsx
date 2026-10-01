import { useMemo } from 'react'
import { GeneratedRosette } from '../theme/GeneratedOrnament'
import { generateCoverOrnament } from '../theme/ornamentGenerator'

/**
 * The open book the desktop sheets lie on: binding boards, two pages with
 * their fore-edges, and a title page on the recto until a chapter opens.
 * Drawn under the sheets; hidden entirely outside the spread.
 */
export function BookSpread({ titlePage }: { titlePage: boolean }) {
  const medallion = useMemo(
    () => generateCoverOrnament((Math.random() * 0x7fffffff) | 0).medallion,
    [],
  )
  return (
    <>
      <div className="book" aria-hidden="true">
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
      {/* The gutter's turn — above the sheets, so both pages curve into it. */}
      <div className="book-spine" aria-hidden="true" />
    </>
  )
}
