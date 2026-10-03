import { memo, useLayoutEffect, useRef, type AnimationEvent, type ReactNode } from 'react'

/**
 * Strips the leaf in the air is cut into. Each joint bends a little, so the
 * sheet furls. Every strip is flat, so a joint is a crease: the text kinks
 * there, and at nine strips the creases read as vertical rules down the
 * page. Enough strips that each bends only a few degrees, and the sheet
 * reads as one curve. The total curl is fixed in styles.css (--furl-total).
 */
export const TURNING_LEAF_STRIPS = 18

/** The share of the block below which a turning pile is drawn as one leaf. */
export const WAD_VISIBLE = 0.02

/** Faces overlap their joints by --joint a side (styles.css), so a slice is the face less two. */
function sliceOffset(slice: number): string {
  return `calc(${-slice} * (100% - 2 * var(--joint)))`
}

/** Ink can overhang a word's box (a mark above, a tail below the line). */
const SLICE_OVERHANG_PX = 16

interface Box { left: number; top: number; width: number; height: number }

function boxWithin(el: Element, originLeft: number, originTop: number): Box {
  const rect = el.getBoundingClientRect()
  return { left: rect.left - originLeft, top: rect.top - originTop, width: rect.width, height: rect.height }
}

function placeAt(el: HTMLElement, box: Box) {
  el.style.position = 'absolute'
  el.style.left = `${box.left}px`
  el.style.top = `${box.top}px`
  el.style.width = `${box.width}px`
  el.style.height = `${box.height}px`
  el.style.margin = '0'
}

/**
 * Gives each strip only the part of the page its slice shows.
 *
 * Every strip used to hold a copy of the whole page, front and back: 36
 * pages of some 150 words for one leaf, about 20,000 elements built, styled
 * and laid out before the leaf could lift, which held the turn up for a
 * third of a second. A strip is an eighteenth of the page wide and shows a
 * word or two of each line.
 *
 * The page React rendered is measured once. Each strip then gets the same
 * ancestry (so every style rule still applies) holding only the words whose
 * boxes reach into its slice, each set at the place it was measured in: the
 * lines are placed boxes, so they clip their ink exactly as they do on the
 * leaf, and the words are placed inside them.
 *
 * Returns false where a page cannot be cut this way (the English leaf is
 * running text), and the caller copies it whole.
 */
function slicePages(source: HTMLElement, targets: HTMLElement[], count: number): boolean {
  const block = source.querySelector(':scope > .mushaf-block')
  const lines = block?.querySelector(':scope > .mushaf-lines')
  const page = source.parentElement
  if (!(block instanceof HTMLElement) || !(lines instanceof HTMLElement) || !page) return false
  const pageRect = page.getBoundingClientRect()
  const rootRect = source.getBoundingClientRect()
  if (pageRect.width < 1 || rootRect.width < 1) return false
  const strip = pageRect.width / count
  // A placed box is set against the padding edge of the leaf's own box.
  const rootLeft = rootRect.left + source.clientLeft
  const rootTop = rootRect.top + source.clientTop

  const furniture = Array.from(block.children)
    .filter((el): el is HTMLElement => el instanceof HTMLElement && el !== lines)
    .map((el) => ({ el, box: boxWithin(el, rootLeft, rootTop) }))
  const rows = Array.from(lines.children).map((line) => {
    // A loose line is set narrow and stretched from its right edge
    // (--line-widen). It is measured unstretched, since its copy stretches
    // itself the same way; what a strip shows is where the stretch puts it.
    const rect = line.getBoundingClientRect()
    const lineLeft = rect.left + line.clientLeft
    const lineTop = rect.top + line.clientTop
    const widen = line.hasAttribute('data-widen')
      ? Number((line as HTMLElement).style.getPropertyValue('--line-widen')) || 1
      : 1
    const seen = (x: number) => rect.right - (rect.right - x) * widen - pageRect.left
    return {
      line,
      box: boxWithin(line, rootLeft, rootTop),
      words: Array.from(line.children).map((word) => {
        const wordRect = word.getBoundingClientRect()
        return {
          word,
          box: boxWithin(word, lineLeft, lineTop),
          from: seen(wordRect.left),
          to: seen(wordRect.right),
        }
      }),
    }
  })
  // A leaf that is not placed on its page (a phone's) keeps its own box.
  const flows = getComputedStyle(source).position === 'static'

  for (const target of targets) {
    const slice = Number(target.dataset.slice)
    const from = slice * strip - SLICE_OVERHANG_PX
    const to = (slice + 1) * strip + SLICE_OVERHANG_PX
    const root = source.cloneNode(false) as HTMLElement
    if (flows) {
      root.style.position = 'relative'
      root.style.flex = 'none'
      root.style.width = `${rootRect.width}px`
      root.style.height = `${rootRect.height}px`
    }
    const blockCopy = block.cloneNode(false) as HTMLElement
    const linesCopy = lines.cloneNode(false) as HTMLElement
    for (const { el, box } of furniture) {
      const copy = el.cloneNode(true) as HTMLElement
      placeAt(copy, box)
      blockCopy.append(copy)
    }
    for (const row of rows) {
      const shown = row.words.filter((word) => word.to > from && word.from < to)
      if (shown.length === 0) continue
      const lineCopy = row.line.cloneNode(false) as HTMLElement
      placeAt(lineCopy, row.box)
      lineCopy.style.display = 'block'
      for (const { word, box } of shown) {
        const copy = word.cloneNode(true) as HTMLElement
        placeAt(copy, box)
        lineCopy.append(copy)
      }
      linesCopy.append(lineCopy)
    }
    blockCopy.append(linesCopy)
    root.append(blockCopy)
    target.replaceChildren(root)
  }
  return true
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
  wad = 0,
  ms,
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
  useLayoutEffect(() => {
    const el = root.current
    if (!el) return
    // React builds each frozen face once. The other strips only borrow its
    // DOM: the part of it their own slice shows, or all of it where a page
    // cannot be cut into slices.
    // Measured flat: a face is turned and a pile's underside is set back in
    // depth, and both would skew the boxes read here.
    el.setAttribute('data-measuring', '')
    for (const selector of ['.mushaf-flip-face:not(.mushaf-flip-face--back)', '.mushaf-flip-face--back']) {
      const pages = Array.from(el.querySelectorAll<HTMLElement>(`${selector} > .mushaf-flip-page`))
      const source = pages[0]?.firstElementChild
      if (!(source instanceof HTMLElement)) continue
      const targets = pages.slice(1)
      if (!slicePages(source, targets, count)) {
        for (const page of targets) page.replaceChildren(source.cloneNode(true))
      }
    }
    el.removeAttribute('data-measuring')
  }, [])
  const count = TURNING_LEAF_STRIPS
  // Below a few leaves a pile's edge is under a pixel: it is a leaf.
  const pile = !single && wad > WAD_VISIBLE
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
          <div className="mushaf-flip-page" data-slice={faceSlice} style={{ left: sliceOffset(faceSlice) }}>
            {index === 0 ? face : null}
          </div>
        </div>
        {back ? (
          <div className="mushaf-flip-face mushaf-flip-face--back">
            <div className="mushaf-flip-page" data-slice={backSlice} style={{ left: sliceOffset(backSlice) }}>
              {index === 0 ? back : null}
            </div>
          </div>
        ) : null}
        {pile ? (
          <>
            <div className="mushaf-flip-cap mushaf-flip-cap--head" />
            <div className="mushaf-flip-cap mushaf-flip-cap--foot" />
            {index + 1 === count ? <div className="mushaf-flip-edge" /> : null}
          </>
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
      style={{
        ['--strips' as string]: count,
        ['--wad-share' as string]: pile ? wad : 0,
        ...(ms != null ? { ['--turn-ms' as string]: `${ms}ms` } : null),
      }}
    >
      {strip(0)}
    </div>
  )
})
