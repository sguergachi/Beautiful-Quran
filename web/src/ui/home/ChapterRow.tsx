import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent, type PointerEvent } from 'react'
import type { Surah } from '../../data/models'
import { exceedsWordHoldSlop } from '../../render/useWordInteraction'
import { HOLD_FILL_MS, HOLD_START_MS, holdOutcome } from './pinnedChapters'

/**
 * One chapter row, in order or pinned on the band (Android `SurahRow`).
 * A click opens it. Hold-to-pin: held, the row takes its answer evenly (the
 * band's wash to pin, paper over the band to unpin), deepening until it is
 * full, and full is the confirmation. Lifting early or moving drains it and
 * changes nothing. Keyboard and right click cannot hold, so the context-menu
 * key, Shift+F10, Shift+Enter or a right click toggle the pin outright.
 */
export function ChapterRow({
  surah,
  rowKey,
  pinned,
  onBand,
  onPrepare,
  onOpen,
  onToggle,
}: {
  surah: Surah
  rowKey: string
  /** Pinned: holding fills paper back over the band, and unpins. */
  pinned: boolean
  /** Set on the band atop the list (search lists a pinned chapter in place). */
  onBand: boolean
  onPrepare: () => void
  onOpen: () => void
  onToggle: () => void
}) {
  const [holding, setHolding] = useState(false)
  const fullTimer = useRef<number | null>(null)
  const start = useRef<{ x: number; y: number; at: number } | null>(null)
  // A press that was a hold (full, or let go early) is not a click.
  const swallowClick = useRef(false)

  const endHold = () => {
    if (fullTimer.current != null) window.clearTimeout(fullTimer.current)
    fullTimer.current = null
    start.current = null
    setHolding(false)
  }
  useEffect(() => () => {
    if (fullTimer.current != null) window.clearTimeout(fullTimer.current)
  }, [])

  const onPointerDown = (event: PointerEvent) => {
    onPrepare()
    swallowClick.current = false
    if (event.pointerType === 'mouse' && event.button !== 0) return
    start.current = { x: event.clientX, y: event.clientY, at: event.timeStamp }
    setHolding(true)
    fullTimer.current = window.setTimeout(() => {
      fullTimer.current = null
      swallowClick.current = true
      start.current = null
      setHolding(false)
      onToggle()
    }, HOLD_START_MS + HOLD_FILL_MS)
  }

  const onPointerMove = (event: PointerEvent) => {
    if (start.current == null) return
    if (exceedsWordHoldSlop(start.current, { x: event.clientX, y: event.clientY })) endHold()
  }

  const onPointerUp = (event: PointerEvent) => {
    if (start.current != null && holdOutcome(event.timeStamp - start.current.at) === 'cancel') {
      swallowClick.current = true
    }
    endHold()
  }

  const onContextMenu = (event: MouseEvent) => {
    event.preventDefault()
    // A touch hold raises this too; the hold timer owns that gesture.
    if (start.current != null) return
    onToggle()
  }

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key !== 'Enter' || !event.shiftKey || event.repeat) return
    event.preventDefault()
    onToggle()
  }

  const onClick = () => {
    if (swallowClick.current) {
      swallowClick.current = false
      return
    }
    onOpen()
  }

  return (
    <li
      className="surah-item"
      data-pinned={pinned || undefined}
      data-band={onBand || undefined}
      data-holding={holding || undefined}
    >
      <span className="pin-fill" aria-hidden="true" />
      <button
        type="button"
        className="surah-row"
        data-row-key={rowKey}
        aria-keyshortcuts="Shift+Enter"
        aria-description={pinned ? 'Hold, or Shift+Enter, to unpin' : 'Hold, or Shift+Enter, to pin'}
        onPointerEnter={onPrepare}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={endHold}
        onPointerLeave={endHold}
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
    </li>
  )
}
