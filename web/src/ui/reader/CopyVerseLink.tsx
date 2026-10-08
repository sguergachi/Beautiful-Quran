import { useEffect, useState } from 'react'
import type { AyahRef } from '../../share/gather'
import { verseLink } from '../verseRoute'

/** Clipboard failure leaves a selectable address on the paper, including on HTTP. */
export function CopyVerseLink({ selection }: { selection: readonly AyahRef[] }) {
  const place = selection.length === 1 ? selection[0] : null
  const [copied, setCopied] = useState(false)
  const [fallback, setFallback] = useState('')
  useEffect(() => { setCopied(false); setFallback('') }, [place?.surahId, place?.ayah])
  const copy = async () => {
    if (!place) return
    setCopied(false)
    setFallback('')
    const link = verseLink(location.href, place)
    try {
      await navigator.clipboard.writeText(link)
      setCopied(true)
    } catch {
      setFallback(link)
    }
  }
  return (
    <>
      <button type="button" className="share-ribbon-icon" aria-label="Copy verse link" title={place ? 'Copy verse link' : 'Select one verse to copy its link'} disabled={!place} onClick={() => void copy()}>
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
          <path d="m10 13 4-4m-6 6-1 1a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0m2 3 1-1a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0" />
        </svg>
      </button>
      <span className="verse-link-status" role="status">{copied ? 'Link copied' : ''}</span>
      {fallback ? <input className="verse-link-field" aria-label="Verse link — select and copy" value={fallback} readOnly autoFocus onFocus={(event) => event.target.select()} /> : null}
    </>
  )
}
