import { useLayoutEffect, useRef } from 'react'
import { readerVisible, spreadLayers } from './bookSpread'
import { BOOKMARKS_LAYER, COVER_LAYER, settingsLayerFor, sheetAtLayer, type StackLayer } from './stack'

/** Exposed facing pages stay usable; covered and parked paper leaves the focus tree. */
export function visibleSheets(spread: boolean, leaves: boolean, stack: StackLayer, hasReader: boolean): string[] {
  const names: string[] = []
  if (spreadLayers(spread, stack, hasReader, leaves).home === COVER_LAYER) names.push('home')
  if (readerVisible(spread, stack, hasReader)) names.push('reader')
  if (stack === BOOKMARKS_LAYER) names.push('bookmarks')
  if (stack === settingsLayerFor(hasReader)) names.push('settings')
  return names
}

/** Hand focus to arriving paper and restore its last control when returning. */
export function useSheetFocus(enabled: boolean, spread: boolean, leaves: boolean, stack: StackLayer, hasReader: boolean, openRevision: number) {
  const previous = useRef('')
  const saved = useRef(new Map<string, HTMLElement>())
  useLayoutEffect(() => {
    const current = `${enabled}:${sheetAtLayer(stack, hasReader)}:${openRevision}`
    const focused = document.activeElement
    if (current !== previous.current && focused instanceof HTMLElement) {
      const name = focused.closest<HTMLElement>('.sheet')?.dataset.name
      if (name) saved.current.set(name, focused)
    }
    const visible = enabled ? visibleSheets(spread, leaves, stack, hasReader) : []
    for (const sheet of document.querySelectorAll<HTMLElement>('.sheet[data-name]')) {
      sheet.inert = !visible.includes(sheet.dataset.name!)
      sheet.tabIndex = -1
    }
    if (enabled && (current !== previous.current || (focused instanceof HTMLElement && focused.closest('[inert]')))) {
      const name = sheetAtLayer(stack, hasReader)
      const sheet = document.querySelector<HTMLElement>(`.sheet[data-name="${name}"]`)
      const restore = saved.current.get(name)
      const newReading = name === 'reader' && !previous.current.endsWith(`:${openRevision}`)
      if (!newReading && restore?.isConnected && !restore.closest('[inert]')) restore.focus({ preventScroll: true })
      else sheet?.focus({ preventScroll: true })
    }
    previous.current = current
  }, [enabled, spread, leaves, stack, hasReader, openRevision])
}
