import { memo, useLayoutEffect, useRef, type AnimationEvent, type ReactNode } from 'react'

/** Strips the leaf in the air is cut into. Each joint bends a little, so the sheet furls. */
export const TURNING_LEAF_STRIPS = 9

/** Faces overlap their joints by 1px a side (styles.css), so a slice is the face less 2px. */
function sliceOffset(slice: number): string {
  return `calc(${-slice} * (100% - 2px))`
}

/**
 * The leaf in the air during a page turn. Paper does not swing like a board:
 * the free edge is lifted first and the sheet bows behind it. So the leaf is
 * a chain of narrow strips, each hinged on the one before, the first on the
 * bound edge. Every strip shows its own slice of the page on its face and of
 * the page behind on its back.
 */
export const TurningLeaf = memo(function TurningLeaf({
  hinge,
  dir,
  single = false,
  face,
  back,
  onEnd,
}: {
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
  useLayoutEffect(() => {
    const el = root.current
    if (!el) return
    // React builds each frozen face once. The other strips only borrow its DOM.
    for (const selector of ['.mushaf-flip-face:not(.mushaf-flip-face--back)', '.mushaf-flip-face--back']) {
      const pages = el.querySelectorAll(`${selector} > .mushaf-flip-page`)
      const source = pages[0]?.firstElementChild
      if (!source) continue
      for (const page of Array.from(pages).slice(1)) {
        page.replaceChildren(source.cloneNode(true))
      }
    }
  }, [])
  const count = TURNING_LEAF_STRIPS
  // Only the bound strip's own turn ends the turn; joints and shading run
  // the same clock and bubble their own animationend through here.
  const ended = (event: AnimationEvent) => {
    if (event.target === event.currentTarget) onEnd()
  }

  const strip = (index: number): ReactNode => {
    // Strip 0 sits on the bound edge. The face shows the page as it lies
    // before the turn; the back shows the other page as it will lie after.
    const faceSlice = hinge === 'right' ? count - 1 - index : index
    const backSlice = hinge === 'right' ? index : count - 1 - index
    return (
      <div
        className="mushaf-flip-strip"
        data-bound={index === 0 || undefined}
        onAnimationEnd={index === 0 ? ended : undefined}
      >
        <div className="mushaf-flip-face">
          <div className="mushaf-flip-page" style={{ left: sliceOffset(faceSlice) }}>
            {index === 0 ? face : null}
          </div>
        </div>
        {back ? (
          <div className="mushaf-flip-face mushaf-flip-face--back">
            <div className="mushaf-flip-page" style={{ left: sliceOffset(backSlice) }}>
              {index === 0 ? back : null}
            </div>
          </div>
        ) : null}
        {index + 1 < count ? strip(index + 1) : null}
      </div>
    )
  }

  return (
    <div
      ref={root}
      inert
      aria-hidden="true"
      className={single ? 'mushaf-flip mushaf-flip--single' : 'mushaf-flip'}
      data-hinge={hinge}
      data-dir={dir}
      style={{ ['--strips' as string]: count }}
    >
      {strip(0)}
    </div>
  )
})
