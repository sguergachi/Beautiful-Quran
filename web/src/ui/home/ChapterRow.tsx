import {
  useEffect,
  useRef,
  type KeyboardEvent,
  type MouseEvent,
  type PointerEvent,
} from 'react'
import type { Surah } from '../../data/models'
import { exceedsWordHoldSlop } from '../../render/useWordInteraction'
import { PIN_HOLD_MS, pinAction, pinFillOrigin } from './pinnedChapters'

/**
 * One chapter row, in order or pinned on the band (Android `SurahRow`).
 * Press-and-hold asks before it pins or unpins, in place: the row fills with
 * its answer (the band's wash to pin, paper over the band to unpin) and the
 * Arabic name hands over to the verb and a quiet Cancel. The keyboard asks
 * with the context-menu key, Shift+F10 or Shift+Enter, as a word's hold does.
 */
export function ChapterRow({
  surah,
  rowKey,
  pinned,
  onBand,
  asking,
  fillOrigin,
  onPrepare,
  onOpen,
  onAsk,
  onCancel,
  onConfirm,
}: {
  surah: Surah
  rowKey: string
  /** Pinned: the hold offers Unpin and fills with paper. */
  pinned: boolean
  /** Set on the band atop the list (search lists a pinned chapter in place). */
  onBand: boolean
  asking: boolean
  /** Where this row's fill grows from, in px; set while it asks or draws back. */
  fillOrigin?: number
  onPrepare: () => void
  onOpen: () => void
  onAsk: (originX: number) => void
  onCancel: () => void
  onConfirm: () => void
}) {
  const itemRef = useRef<HTMLLIElement>(null)
  const cancelRef = useRef<HTMLButtonElement>(null)
  const holdTimer = useRef<number | null>(null)
  const start = useRef<{ x: number; y: number } | null>(null)
  const held = useRef(false)

  const clearHold = () => {
    if (holdTimer.current == null) return
    window.clearTimeout(holdTimer.current)
    holdTimer.current = null
  }
  useEffect(() => clearHold, [])

  // The safe answer takes focus, as a bookmark's Keep does.
  useEffect(() => {
    if (asking) cancelRef.current?.focus({ preventScroll: true })
  }, [asking])

  const ask = (clientX: number) => {
    const rect = itemRef.current?.getBoundingClientRect()
    if (!rect) return
    onAsk(pinFillOrigin(clientX - rect.left, rect.width))
  }

  const onPointerDown = (event: PointerEvent) => {
    onPrepare()
    held.current = false
    start.current = { x: event.clientX, y: event.clientY }
    clearHold()
    if (event.pointerType === 'mouse' && event.button !== 0) return
    const x = event.clientX
    holdTimer.current = window.setTimeout(() => {
      holdTimer.current = null
      held.current = true
      ask(x)
    }, PIN_HOLD_MS)
  }

  const onPointerMove = (event: PointerEvent) => {
    if (start.current == null || holdTimer.current == null) return
    if (exceedsWordHoldSlop(start.current, { x: event.clientX, y: event.clientY })) clearHold()
  }

  const onPointerEnd = () => {
    clearHold()
    start.current = null
  }

  // A touch hold or right click arrives here too; the keyboard's has no pointer.
  const onContextMenu = (event: MouseEvent) => {
    event.preventDefault()
    clearHold()
    const fromPointer = start.current != null || event.button === 2
    held.current = start.current != null
    if (!asking) ask(fromPointer ? event.clientX : Number.NaN)
  }

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key !== 'Enter' || !event.shiftKey || event.repeat) return
    event.preventDefault()
    if (!asking) ask(Number.NaN)
  }

  const onClick = () => {
    if (held.current) {
      held.current = false
      return
    }
    // While asking, a click anywhere else on the row backs out.
    if (asking) onCancel()
    else onOpen()
  }

  return (
    <li
      ref={itemRef}
      className="surah-item"
      data-pinned={pinned || undefined}
      data-band={onBand || undefined}
      data-asking={asking || undefined}
    >
      <span
        className="pin-fill"
        aria-hidden="true"
        style={fillOrigin != null ? { transformOrigin: `${fillOrigin}px 50%` } : undefined}
      />
      <button
        type="button"
        className="surah-row"
        data-row-key={rowKey}
        onPointerEnter={onPrepare}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerEnd}
        onPointerCancel={onPointerEnd}
        onPointerLeave={onPointerEnd}
        onFocus={onPrepare}
        onContextMenu={onContextMenu}
        onKeyDown={onKeyDown}
        onClick={onClick}
      >
        <span className="surah-num">{surah.id}</span>
        <span className="surah-names">
          <span className="en">{surah.nameTransliteration}</span>
          <span className="meta">
            {surah.nameTranslation} · {surah.ayahCount} ayahs
          </span>
        </span>
        <span className="surah-ar">{surah.nameArabic}</span>
      </button>
      <span className="pin-answers" aria-live="polite">
        <button type="button" className="pin-answer" onClick={onConfirm}>
          {pinAction(pinned)}
        </button>
        <button ref={cancelRef} type="button" className="pin-answer pin-cancel" onClick={onCancel}>
          Cancel
        </button>
      </span>
    </li>
  )
}
