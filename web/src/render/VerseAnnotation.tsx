/**
 * The reader's marginal note for one verse — Android `VerseAnnotationField`
 * (docs/ANNOTATIONS.md). Set in the scribe's hand below the translation;
 * while editing, a chromeless field takes focus and writes in place. Leaving
 * the field commits; blank text deletes. No box, no underline, no Save.
 */
import { useEffect, useLayoutEffect, useRef, useState } from 'react'

/** Android `ANNOTATION_KEYBOARD_CLEARANCE`. */
const KEYBOARD_CLEARANCE = 16

export function VerseAnnotation({
  text,
  isEditing,
  hidden,
  onChange,
  onEditDone,
  onStartEdit,
  onDelete,
}: {
  text: string
  isEditing: boolean
  /** Recitation clears notes off the sheet; editing always wins. */
  hidden: boolean
  onChange?: (text: string) => void
  onEditDone: () => void
  onStartEdit?: () => void
  onDelete: () => void
}) {
  const fieldRef = useRef<HTMLTextAreaElement>(null)
  const [mounted, setMounted] = useState(false)
  useEffect(() => {
    const raf = requestAnimationFrame(() => setMounted(true))
    return () => cancelAnimationFrame(raf)
  }, [])

  // Grow with the writing; the verse above never moves.
  useLayoutEffect(() => {
    const field = fieldRef.current
    if (!field) return
    field.style.height = '0px'
    field.style.height = `${field.scrollHeight}px`
  }, [text, isEditing])

  useEffect(() => {
    if (!isEditing) return
    const field = fieldRef.current
    if (!field) return
    field.focus({ preventScroll: true })
    const end = field.value.length
    field.setSelectionRange(end, end)
    // Park the line being written above the keyboard (visualViewport).
    const park = () => {
      const vv = window.visualViewport
      const rect = field.getBoundingClientRect()
      const bottomLimit = (vv ? vv.offsetTop + vv.height : window.innerHeight) - KEYBOARD_CLEARANCE
      if (rect.bottom > bottomLimit) {
        const scroller = field.closest<HTMLElement>('.scroll')
        scroller?.scrollBy({ top: rect.bottom - bottomLimit, behavior: 'smooth' })
      }
    }
    const timer = window.setTimeout(park, 320)
    window.visualViewport?.addEventListener('resize', park)
    return () => {
      window.clearTimeout(timer)
      window.visualViewport?.removeEventListener('resize', park)
    }
  }, [isEditing])

  return (
    <div
      className="verse-annotation"
      data-editing={isEditing || undefined}
      data-hidden={hidden || !mounted || undefined}
    >
      <div className="verse-annotation-inner">
        <span className="verse-annotation-lane">
          <span className="verse-annotation-rule" aria-hidden="true" />
          {isEditing ? (
            <button
              type="button"
              className="verse-annotation-delete"
              aria-label="Delete this annotation"
              // Keep focus in the field until the delete lands.
              onPointerDown={(event) => event.preventDefault()}
              onClick={(event) => {
                event.stopPropagation()
                onDelete()
              }}
            >
              <svg viewBox="0 0 20 20" aria-hidden="true">
                <path d="M5 5 15 15M15 5 5 15" />
              </svg>
            </button>
          ) : null}
        </span>
        {isEditing ? (
          <textarea
            ref={fieldRef}
            className="verse-annotation-text verse-annotation-field"
            value={text}
            rows={1}
            placeholder="Write a note…"
            aria-label="Verse note"
            autoCapitalize="sentences"
            enterKeyHint="done"
            onChange={(event) => onChange?.(event.target.value)}
            onBlur={onEditDone}
            onClick={(event) => event.stopPropagation()}
            onKeyDown={(event) => {
              event.stopPropagation()
              if (event.key === 'Escape' || (event.key === 'Enter' && !event.shiftKey)) {
                event.preventDefault()
                event.currentTarget.blur()
              }
            }}
          />
        ) : (
          <p
            className="verse-annotation-text"
            role={onStartEdit ? 'button' : undefined}
            tabIndex={onStartEdit ? 0 : undefined}
            onClick={(event) => {
              event.stopPropagation()
              onStartEdit?.()
            }}
            onKeyDown={(event) => {
              if (onStartEdit && (event.key === 'Enter' || event.key === ' ')) {
                event.preventDefault()
                onStartEdit()
              }
            }}
          >
            {text}
          </p>
        )}
      </div>
    </div>
  )
}
