/**
 * The gather bar — Android `ShareRibbon`. The count lives on the verses; the
 * bar is the theme's green foot of the sheet. Nothing gathered: a hairline
 * runs to a small-caps hint. An error replaces the middle until the next
 * export starts.
 */
import type { Ref } from 'react'
import type { AyahRef } from '../../share/gather'
import { IconBuffering, IconClose } from '../icons/PlaybackIcons'
import { CopyVerseLink } from './CopyVerseLink'
import './readerChrome.css'

export type GatherBarMiddle = 'hint' | 'options' | 'error'

/** Android `gatherBarMiddle`. */
export function gatherBarMiddle(count: number, error: string | null, busy: boolean): GatherBarMiddle {
  if (error && error.trim() && !busy) return 'error'
  if (count >= 1) return 'options'
  return 'hint'
}

const GATHER_HINT = 'Tap verses to share'

export function ShareRibbon({
  barRef,
  selection,
  error,
  preparing,
  onCancel,
  onShareText,
  onShareImage,
}: {
  barRef?: Ref<HTMLDivElement>
  selection: readonly AyahRef[]
  error: string | null
  preparing: 'text' | 'image' | null
  onCancel: () => void
  onShareText: () => void
  onShareImage: () => void
}) {
  const count = selection.length
  const busy = preparing != null
  const canExport = count >= 1 && !busy
  const middle = gatherBarMiddle(count, error, busy)
  const label = count === 0 ? GATHER_HINT : count === 1 ? '1 verse selected' : `${count} verses selected`
  const send = (
    <span className="share-ribbon-send">
      <CopyVerseLink selection={selection} />
      <button
        type="button"
        className="share-ribbon-icon"
        aria-label="Share as text"
        aria-busy={preparing === 'text' || undefined}
        disabled={!canExport}
        onClick={onShareText}
      >
        {preparing === 'text' ? (
          <IconBuffering className="share-ribbon-spinner" />
        ) : (
          <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
            <path fill="currentColor" d="M6 17h3l2-4V7H5v6h3l-2 4zm8 0h3l2-4V7h-6v6h3l-2 4z" />
          </svg>
        )}
      </button>
      <button
        type="button"
        className="share-ribbon-icon"
        aria-label="Share as image"
        aria-busy={preparing === 'image' || undefined}
        disabled={!canExport}
        onClick={onShareImage}
      >
        {preparing === 'image' ? (
          <IconBuffering className="share-ribbon-spinner" />
        ) : (
          <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
            <path fill="currentColor" d="M21 19V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2zM8.5 13.5 11 16.5 14.5 12 19 18H5l3.5-4.5z" />
          </svg>
        )}
      </button>
    </span>
  )
  return (
    <div ref={barRef} className="share-ribbon" role="group" aria-label={label} data-middle={middle}>
      <button type="button" className="share-ribbon-icon share-ribbon-cancel" aria-label="Cancel share" onClick={onCancel}>
        <IconClose />
      </button>
      <div className="share-ribbon-middle" key={middle}>
        {middle === 'hint' ? (
          <span className="share-ribbon-hint" aria-hidden="true">
            <span className="share-ribbon-hairline" />
            <span className="share-ribbon-hint-label">{GATHER_HINT}</span>
          </span>
        ) : middle === 'error' ? (
          <>
            <span className="share-ribbon-error" role="status">{error}</span>
            {count >= 1 ? send : <span className="share-ribbon-icon-spacer" />}
          </>
        ) : (
          <>
            <span className="share-ribbon-spacer" />
            {send}
          </>
        )}
      </div>
    </div>
  )
}
