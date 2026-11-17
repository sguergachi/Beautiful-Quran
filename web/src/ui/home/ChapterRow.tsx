import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent, type PointerEvent } from 'react'
import type { Surah } from '../../data/models'
import { exceedsWordHoldSlop } from '../../render/useWordInteraction'
import { HOLD_FILL_MS, HOLD_START_MS, HOLD_TAP_MAX_MS, holdOutcome } from './pinnedChapters'
import { VerseBookmarkRibbon } from '../../render/VerseBookmarkRibbon'
import { holdHaptic } from './holdHaptics'

/**
 * One chapter row, in order or pinned on the band (Android `SurahRow`).
 * A click opens it. Hold-to-pin: held, the row takes its answer evenly (the
 * band's wash to pin, paper over the band to unpin), deepening until it is
 * full, and full is the confirmation. Lifting early or moving drains it and
 * changes nothing. Keyboard and right click cannot hold, so the context-menu
 * key, Shift+F10, Shift+Enter or a right click toggle the pin outright.
 * [holdable] is off while searching: results list pinned chapters in place,
 * off the band, where a hold could show nothing of what it would do.
 */
export function ChapterRow({
  surah,
  rowKey,
  pinned,
  onBand,
  drying,
  holdable,
  placeAyah = null,
  placeUnfurlSignal = 0,
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
  /** Just pinned or unpinned: travels above the rest, its wet ink drying. */
  drying: boolean
  holdable: boolean
  /** The last-heard ayah when this row owns the reading place: it carries
   * the green place ribbon in its lane (Android `SurahRow.currentAyah`). */
  placeAyah?: number | null
  /** Replays the place ribbon's unfurl (returning from the reader). */
  placeUnfurlSignal?: number
  onPrepare: () => void
  onOpen: () => void
  onToggle: () => void
}) {
  const [holding, setHolding] = useState(false)
  const fullTimer = useRef<number | null>(null)
  const tickTimer = useRef<number | null>(null)
  const start = useRef<{ x: number; y: number; at: number } | null>(null)
  // A press that was a hold (full, let go early, or dragged off) is not a
  // click. Stamped, so a click that never comes cannot swallow a later one.
  const swallowClickAt = useRef(Number.NEGATIVE_INFINITY)
  const swallowNextClick = () => {
    swallowClickAt.current = performance.now()
  }

  const endHold = () => {
    if (fullTimer.current != null) window.clearTimeout(fullTimer.current)
    if (tickTimer.current != null) window.clearTimeout(tickTimer.current)
    fullTimer.current = null
    tickTimer.current = null
    start.current = null
    setHolding(false)
  }
  useEffect(() => () => {
    if (fullTimer.current != null) window.clearTimeout(fullTimer.current)
    if (tickTimer.current != null) window.clearTimeout(tickTimer.current)
  }, [])

  const onPointerDown = (event: PointerEvent) => {
    onPrepare()
    // One hold at a time: a second finger never starts a second timer.
    endHold()
    if (!holdable || !event.isPrimary) return
    // A secondary button, or a Mac's Ctrl+click, is the context menu.
    if (event.pointerType === 'mouse' && (event.button !== 0 || event.ctrlKey)) return
    start.current = { x: event.clientX, y: event.clientY, at: event.timeStamp }
    setHolding(true)
    // The hold has taken: a soft tick as the fill begins (Android CLOCK_TICK).
    tickTimer.current = window.setTimeout(() => {
      tickTimer.current = null
      holdHaptic('engaged')
    }, HOLD_START_MS)
    fullTimer.current = window.setTimeout(() => {
      fullTimer.current = null
      swallowNextClick()
      start.current = null
      setHolding(false)
      // Full is the confirmation: a firm tick (Android CONFIRM).
      holdHaptic('confirmed')
      onToggle()
    }, HOLD_START_MS + HOLD_FILL_MS)
  }

  /** A hold that drains is not a click: past the tap's time, swallow it. */
  const drain = (event: PointerEvent) => {
    if (start.current != null && holdOutcome(event.timeStamp - start.current.at) === 'cancel') {
      swallowNextClick()
    }
    endHold()
  }

  const onPointerMove = (event: PointerEvent) => {
    if (start.current == null) return
    if (exceedsWordHoldSlop(start.current, { x: event.clientX, y: event.clientY })) {
      // Dragged off the hold: nothing happens, not even the open.
      swallowNextClick()
      endHold()
    }
  }

  const onContextMenu = (event: MouseEvent) => {
    event.preventDefault()
    // Touch and pen raise this for their own long-press, sometimes after the
    // hold has already pinned and the row has moved; the hold owns those.
    const pointerType = (event.nativeEvent as globalThis.PointerEvent).pointerType
    if (pointerType === 'touch' || pointerType === 'pen' || start.current != null) return
    if (holdable) onToggle()
  }

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key !== 'Enter' || !event.shiftKey) return
    // Prevent repeats too, or a held Shift+Enter re-activates and opens.
    event.preventDefault()
    if (event.repeat || !holdable) return
    onToggle()
  }

  const onClick = () => {
    if (performance.now() - swallowClickAt.current < HOLD_TAP_MAX_MS * 3) {
      swallowClickAt.current = Number.NEGATIVE_INFINITY
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
      data-drying={drying || undefined}
    >
      <span className="pin-fill" aria-hidden="true" />
      {placeAyah != null ? (
        <span className="surah-place-ribbon" aria-hidden="true">
          <VerseBookmarkRibbon
            bookmarked={false}
            placeMarked
            focused={false}
            side="left"
            interactive={false}
            animateOnTap={false}
            topInset={0}
            bottomGap={0}
            edgeInset={7.5}
            unfurlSignal={placeUnfurlSignal}
            onToggle={() => false}
            decorative
          />
        </span>
      ) : null}
      <button
        type="button"
        className="surah-row"
        data-row-key={rowKey}
        aria-keyshortcuts={holdable ? 'Shift+Enter' : undefined}
        aria-description={
          holdable ? (pinned ? 'Hold, or Shift+Enter, to unpin' : 'Hold, or Shift+Enter, to pin') : undefined
        }
        onPointerEnter={onPrepare}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={drain}
        onPointerCancel={drain}
        onPointerLeave={drain}
        onFocus={onPrepare}
        onContextMenu={onContextMenu}
        onKeyDown={onKeyDown}
        onClick={onClick}
      >
        {placeAyah != null ? (
          <span className="visually-hidden">Current place, ayah {placeAyah}. </span>
        ) : null}
        {pinned ? <span className="visually-hidden">Pinned. </span> : null}
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
