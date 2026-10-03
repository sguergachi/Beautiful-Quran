import { memo, useLayoutEffect, useRef, type ReactNode } from 'react'
import { LEAF_TURN_MS } from '../paper/bookSpread'
import { startPageTurn } from '../paper/pageTurn'

/** The share of the block below which a turning pile is drawn as one leaf. */
export const WAD_VISIBLE = 0.02

/**
 * The leaf in the air during a page turn. Paper does not swing like a board:
 * the free edge is lifted first and the sheet bows behind it, then is laid
 * flat. So the leaf is one bent sheet, drawn by the GPU (paper/pageTurn),
 * with a picture of the page it lifts off on its face and of the page it
 * lands as on its back.
 *
 * What React renders here is only the two pages those pictures are taken
 * from: laid out where they lie on the book, and never painted.
 */
export const TurningLeaf = memo(function TurningLeaf({
  hinge,
  dir,
  single = false,
  wad = 0,
  ms = LEAF_TURN_MS,
  face,
  back,
  onEnd,
}: {
  /**
   * The share of the book's block this turn carries, 0 to 1: one leaf is 0,
   * and going to Chapters, Settings or a far page turns the whole pile in
   * between. A pile has thickness, so it is drawn with its edges.
   */
  wad?: number
  /** How long the turn takes; a pile is slower than a leaf. */
  ms?: number
  /** Which edge of the leaf is bound. */
  hinge: 'left' | 'right'
  dir: 'on' | 'back'
  /** One leaf with nowhere to land (phones): it lifts to edge-on. */
  single?: boolean
  face: ReactNode
  back?: ReactNode
  onEnd: () => void
}) {
  const root = useRef<HTMLDivElement>(null)
  const ended = useRef(onEnd)
  ended.current = onEnd
  // Below a few leaves a pile's edge is under a pixel: it is a leaf.
  const pile = !single && wad > WAD_VISIBLE
  // A leaf's own effects run before this one, so the pages are already set
  // (their lines widened and centred) when their pictures are taken.
  useLayoutEffect(() => {
    const el = root.current
    if (!el) return
    return startPageTurn(el, { hinge, dir, single, wad: pile ? wad : 0, ms }, () => ended.current())
    // A turn is one flight: a new one is a new leaf (its key).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div
      ref={root}
      inert
      aria-hidden="true"
      className={single ? 'mushaf-flip mushaf-flip--single' : 'mushaf-flip'}
      data-hinge={hinge}
      data-dir={dir}
    >
      <div className="mushaf-flip-page" data-face="front">{face}</div>
      {back ? <div className="mushaf-flip-page" data-face="back">{back}</div> : null}
    </div>
  )
})
