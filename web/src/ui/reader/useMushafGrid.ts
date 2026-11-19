import { useLayoutEffect, useState, type RefObject } from 'react'

/** Reserve the real toolbar and transport on both leaves. Callback refs
 * follow the phone transport through its portal and the gathering ribbon.
 * Only sizes are observed; the wash never measures or moves the grid.
 */
export function useMushafGrid(sheet: RefObject<HTMLElement | null>, enabled: boolean) {
  const [top, topRef] = useState<HTMLElement | null>(null)
  const [foot, footRef] = useState<HTMLElement | null>(null)
  useLayoutEffect(() => {
    const book = sheet.current?.closest<HTMLElement>('.app-shell')
    // A single leaf carries no top bar (Android's book window): only the
    // transport is reserved.
    if (!enabled || !book || !foot) return
    const measure = () => {
      book.style.setProperty('--mushaf-toolbar-h', `${top?.offsetHeight ?? 0}px`)
      book.style.setProperty('--mushaf-transport-h', `${foot.offsetHeight}px`)
    }
    measure()
    const observer = new ResizeObserver(measure)
    if (top) observer.observe(top, { box: 'border-box' })
    observer.observe(foot, { box: 'border-box' })
    return () => {
      observer.disconnect()
      book.style.removeProperty('--mushaf-toolbar-h')
      book.style.removeProperty('--mushaf-transport-h')
    }
  }, [sheet, enabled, top, foot])
  return { topRef, footRef }
}
