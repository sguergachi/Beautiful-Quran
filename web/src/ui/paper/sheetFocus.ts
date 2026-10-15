import { useLayoutEffect, useRef } from 'react'
import { readerVisible, spreadLayers } from './bookSpread'
import {
  BOOKMARKS_LAYER,
  COVER_LAYER,
  settingsLayerFor,
  sheetAtLayer,
  type StackLayer,
} from './stack'

/** Exposed facing pages stay usable; covered and parked paper leaves the focus tree. */
export function visibleSheets(
  spread: boolean,
  leaves: boolean,
  stack: StackLayer,
  hasReader: boolean,
  wordSheetOpen = false,
): string[] {
  if (stack === settingsLayerFor(hasReader)) return ['settings']
  const names: string[] = []
  if (
    !(spread && wordSheetOpen) &&
    spreadLayers(spread, stack, hasReader, leaves).home === COVER_LAYER
  )
    names.push('home')
  if (readerVisible(spread, stack, hasReader, leaves)) names.push('reader')
  if (stack === BOOKMARKS_LAYER && !(spread && wordSheetOpen))
    names.push('bookmarks')
  return names
}

/** Hand focus to arriving paper and restore its last control when returning. */
export function useSheetFocus(
  enabled: boolean,
  spread: boolean,
  leaves: boolean,
  stack: StackLayer,
  hasReader: boolean,
  openRevision: number,
  wordSheetOpen = false,
) {
  const previous = useRef('')
  const previousWordSheet = useRef(wordSheetOpen)
  const saved = useRef(new Map<string, HTMLElement>())
  useLayoutEffect(() => {
    const current = `${enabled}:${sheetAtLayer(stack, hasReader)}:${openRevision}`
    const focused = document.activeElement
    if (current !== previous.current && focused instanceof HTMLElement) {
      const name = focused.closest<HTMLElement>('.sheet')?.dataset.name
      if (name) saved.current.set(name, focused)
    }
    const visible = enabled
      ? visibleSheets(spread, leaves, stack, hasReader, wordSheetOpen)
      : []
    for (const sheet of document.querySelectorAll<HTMLElement>(
      '.sheet[data-name]',
    )) {
      sheet.inert = !visible.includes(sheet.dataset.name!)
      sheet.tabIndex = -1
    }
    // The word sheet is portalled outside the reader. Settings and the cover
    // cover that slot too; its controls must leave the focus tree with it.
    const loose = document.querySelector<HTMLElement>('.book-loose')
    if (loose) loose.inert = !enabled || visible.includes('settings')
    const focusCurrent = () => {
      const name = sheetAtLayer(stack, hasReader)
      const sheet = document.querySelector<HTMLElement>(
        `.sheet[data-name="${name}"]`,
      )
      const restore = saved.current.get(name)
      const newReading =
        name === 'reader' && !previous.current.endsWith(`:${openRevision}`)
      if (newReading) saved.current.delete('reader')
      const word =
        spread && wordSheetOpen && !visible.includes('settings')
          ? loose?.querySelector<HTMLElement>('.root-sheet')
          : null
      if (word) word.focus({ preventScroll: true })
      else if (
        !newReading &&
        restore?.isConnected &&
        !restore.closest('[inert]')
      )
        restore.focus({ preventScroll: true })
      else sheet?.focus({ preventScroll: true })
    }
    if (
      enabled &&
      (current !== previous.current ||
        (focused instanceof HTMLElement && focused.closest('[inert]')))
    )
      focusCurrent()
    if (enabled && previousWordSheet.current && !wordSheetOpen) {
      // The word sheet first restores its origin, if that page still owns
      // the route. Otherwise give the arriving sheet a focusable landing.
      queueMicrotask(() => {
        if (document.activeElement === document.body) focusCurrent()
      })
    }
    previousWordSheet.current = wordSheetOpen
    previous.current = current
  }, [enabled, spread, leaves, stack, hasReader, openRevision, wordSheetOpen])
}
