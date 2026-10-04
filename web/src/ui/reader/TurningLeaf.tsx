import { memo, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { LEAF_TURN_MS } from '../paper/bookSpread'
import { hasPagePicture, keepPagePicture, startPageTurn, type PagePlace } from '../paper/pageTurn'

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
 * from: laid out where they lie on the book, and never painted. A page whose
 * picture is already kept ([LeafPicture]) is not rendered at all.
 */
export const TurningLeaf = memo(function TurningLeaf({
  hinge,
  dir,
  single = false,
  wad = 0,
  ms = LEAF_TURN_MS,
  face,
  faceKey,
  back,
  backKey,
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
  /** What the face's picture is kept under; absent for a page that changes as it is read. */
  faceKey?: string
  back?: ReactNode
  backKey?: string
  onEnd: () => void
}) {
  const root = useRef<HTMLDivElement>(null)
  const ended = useRef(onEnd)
  ended.current = onEnd
  // Below a few leaves a pile's edge is under a pixel: it is a leaf.
  const pile = !single && wad > WAD_VISIBLE
  // Decided once, as the leaf lifts: a page with a kept picture is not built.
  const [staged] = useState(() => ({
    face: !(faceKey && hasPagePicture(faceKey)),
    back: !(backKey && hasPagePicture(backKey)),
  }))
  // A leaf's own effects run before this one, so the pages are already set
  // (their lines widened and centred) when their pictures are taken.
  useLayoutEffect(() => {
    const el = root.current
    if (!el) return
    return startPageTurn(
      el,
      { hinge, dir, single, wad: pile ? wad : 0, ms, frontKey: faceKey, backKey },
      () => ended.current(),
    )
    // A turn is one flight: a new one is a new leaf (its key).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // The page the leaf lifts off lies on the side it is bound from; the page
  // it lands as lies on the other.
  const from: PagePlace = single ? 'single' : hinge === 'right' ? 'verso' : 'recto'
  const onto: PagePlace = from === 'verso' ? 'recto' : 'verso'
  return (
    <div
      ref={root}
      inert
      aria-hidden="true"
      className={single ? 'mushaf-flip mushaf-flip--single' : 'mushaf-flip'}
      data-hinge={hinge}
      data-dir={dir}
    >
      <div className="mushaf-flip-page" data-face="front" data-at={from}>{staged.face ? face : null}</div>
      {back ? (
        <div className="mushaf-flip-page" data-face="back" data-at={onto}>{staged.back ? back : null}</div>
      ) : null}
    </div>
  )
})

/**
 * A page whose picture is taken ahead of its turn, while the reader is
 * idle, and kept: the leaf then lifts on the click with nothing to draw.
 * Staged as a turning leaf's pages are, and never painted.
 */
export function LeafPicture({
  at,
  shotKey,
  onKept,
  children,
}: {
  at: PagePlace
  shotKey: string
  /** The picture is kept (or could not be taken): this may be unmounted. */
  onKept: () => void
  children: ReactNode
}) {
  const root = useRef<HTMLDivElement>(null)
  const done = useRef(onKept)
  done.current = onKept
  useLayoutEffect(() => {
    if (root.current) keepPagePicture(root.current, shotKey, at)
    done.current()
  }, [shotKey, at])
  return (
    <div
      ref={root}
      inert
      aria-hidden="true"
      className={at === 'single' ? 'mushaf-flip mushaf-flip--single' : 'mushaf-flip'}
    >
      <div className="mushaf-flip-page" data-at={at}>{children}</div>
    </div>
  )
}
