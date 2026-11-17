import { suppliedWords } from '../../render/suppliedWords'
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { annotationFor } from '../../data/annotations'
import './bookmarks.css'
import { QuranRepository } from '../../data/repository'
import { VerseBookmarkRibbon } from '../../render/VerseBookmarkRibbon'
import { appStore, useAppSelector } from '../../store/appStore'
import { DisclosureChevron } from '../kit/DisclosureChevron'
import { PaperInput } from '../kit/PaperInput'
import { BOOKMARKS_LAYER, COVER_LAYER, type StackLayer } from '../paper/stack'
import {
  BOOKMARK_SECTION_PREVIEW_LIMIT,
  bookmarkDisclosureLabel,
  filterBookmarkSections,
  hiddenBookmarkCount,
  isBookmarkSectionCollapsed,
  visibleBookmarkVerses,
  type BookmarkedVerse,
} from './bookmarkSections'

type BookmarkKey = `${number}:${number}`

export function BookmarksScreen({ stackLayer }: { stackLayer: StackLayer }) {
  const bookmarks = useAppSelector((s) => s.bookmarks)
  const annotations = useAppSelector((s) => s.annotations)
  const annotationsEnabled = useAppSelector((s) => s.settings.annotationsEnabled)
  // One note open at a time; it saves as it closes (blur, Done, a scroll,
  // opening a verse, leaving the sheet). Android BookmarksScreen.
  const [editing, setEditing] = useState<{ surahId: number; ayah: number } | null>(null)
  const [draft, setDraft] = useState('')
  const editingRef = useRef<{ surahId: number; ayah: number; draft: string } | null>(null)
  editingRef.current = editing ? { ...editing, draft } : null
  const scrollRef = useRef<HTMLDivElement>(null)
  const [query, setQuery] = useState('')
  const queryInputRef = useRef<HTMLInputElement>(null)
  const [pendingRemoval, setPendingRemoval] = useState<BookmarkKey | null>(null)
  const [expandedSurahs, setExpandedSurahs] = useState<Set<number>>(new Set())
  const [collapsedSurahs, setCollapsedSurahs] = useState<Set<number>>(new Set())
  const active = stackLayer === BOOKMARKS_LAYER
  const searching = query.trim().length > 0
  const verses = useMemo<BookmarkedVerse[]>(() => {
    return [...bookmarks]
      .sort((a, b) => a.surahId - b.surahId || a.ayah - b.ayah)
      .flatMap((bookmark) => {
        const content = QuranRepository.surahContent(bookmark.surahId)
        const ayah = content.ayahs.find((item) => item.number === bookmark.ayah)
        return ayah ? [{ surah: content.surah, ayah }] : []
      })
  }, [bookmarks])
  const sections = useMemo(
    () => filterBookmarkSections(verses, query),
    [verses, query],
  )

  function commitAnnotation() {
    const open = editingRef.current
    if (!open) return
    editingRef.current = null
    appStore.setAnnotation(open.surahId, open.ayah, open.draft)
    setEditing(null)
    setDraft('')
  }

  function editAnnotation(surahId: number, ayah: number) {
    commitAnnotation()
    setPendingRemoval(null)
    setDraft(annotationFor(annotations, surahId, ayah) ?? '')
    setEditing({ surahId, ayah })
  }

  // Leaving the sheet saves the open note.
  useEffect(() => {
    if (!active) commitAnnotation()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active])
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => () => commitAnnotation(), [])
  // A deliberate scroll of the list puts the keyboard away, saving the note.
  useEffect(() => {
    const scroller = scrollRef.current
    if (!scroller || !editing) return
    const dismiss = () => {
      if (document.activeElement instanceof HTMLElement && document.activeElement.closest('.bookmark-note-editor')) {
        document.activeElement.blur()
      }
    }
    scroller.addEventListener('wheel', dismiss, { passive: true })
    scroller.addEventListener('touchmove', dismiss, { passive: true })
    return () => {
      scroller.removeEventListener('wheel', dismiss)
      scroller.removeEventListener('touchmove', dismiss)
    }
  }, [editing])

  function changeQuery(next: string) {
    setQuery(next)
    setPendingRemoval(null)
  }

  return (
    <div
      className="sheet bookmarks-sheet"
      data-name="bookmarks"
      data-layer={BOOKMARKS_LAYER}
      data-depth={0}
      data-active={active}
      aria-hidden={!active}
    >
      <div className="sheet-frame bookmarks-frame">
        <div className="edge-fade bookmarks-edge-fade">
          <div className="scroll bookmarks-scroll" ref={scrollRef}>
            {/* Title and search scroll with the list (Android LazyColumn items). */}
            <header className="bookmarks-header">
              <div>
                <h1>Bookmarks</h1>
              </div>
              <button
                type="button"
                className="bookmarks-back"
                aria-label="Back to Chapters"
                onClick={() => {
                  commitAnnotation()
                  // Search may still hold focus after the sheet peels away; blur
                  // so the mobile keyboard does not follow to Chapters.
                  if (document.activeElement instanceof HTMLElement) {
                    document.activeElement.blur()
                  }
                  appStore.revealLayer(COVER_LAYER)
                }}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path
                    fill="currentColor"
                    d="M5 13h11.17l-4.88 4.88a1 1 0 0 0 1.41 1.42l6.59-6.59a1 1 0 0 0 0-1.41l-6.58-6.6a1 1 0 0 0-1.41 1.41L16.17 11H5a1 1 0 0 0 0 2z"
                  />
                </svg>
              </button>
            </header>
            <div className="bookmarks-search">
              <div className="home-search">
                <SearchGlyph />
                <PaperInput
                  inputRef={queryInputRef}
                  type="search"
                  name="bookmark-search"
                  placeholder="Search bookmarks, text, or 2:255"
                  value={query}
                  onValueChange={changeQuery}
                  aria-label="Search bookmarked verses"
                />
                {query ? (
                  <button
                    type="button"
                    className="home-search-clear"
                    aria-label="Clear bookmark search"
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => {
                      changeQuery('')
                      queryInputRef.current?.focus()
                    }}
                  >
                    <ClearGlyph />
                  </button>
                ) : null}
              </div>
            </div>
            {sections.length === 0 ? <BookmarkEmptyState query={query} /> : null}

            {sections.map((section, sectionIndex) => {
              const collapsed = isBookmarkSectionCollapsed(
                section.surah.id,
                collapsedSurahs,
                searching,
              )
              const expanded = expandedSurahs.has(section.surah.id)
              const visibleVerses = visibleBookmarkVerses(
                section.verses,
                expanded,
                searching,
              )
              const hiddenCount = hiddenBookmarkCount(
                section.verses,
                expanded,
                searching,
              )

              return (
                <section className="bookmark-section" key={section.surah.id}>
                  <button
                    type="button"
                    className="bookmark-section-header"
                    data-first={sectionIndex === 0}
                    aria-expanded={!collapsed}
                    disabled={searching}
                    onClick={() => {
                      commitAnnotation()
                      setCollapsedSurahs((current) => {
                        const next = new Set(current)
                        if (collapsed) next.delete(section.surah.id)
                        else next.add(section.surah.id)
                        return next
                      })
                      setPendingRemoval(null)
                    }}
                  >
                    <span className="bookmark-section-number">
                      {section.surah.id}
                    </span>
                    <span className="bookmark-section-copy">
                      <span className="bookmark-section-name">
                        {section.surah.nameTransliteration}
                      </span>
                      <span
                        className="bookmark-section-arabic"
                        lang="ar"
                        dir="rtl"
                      >
                        {section.surah.nameArabic}
                      </span>
                      <DisclosureChevron
                        expanded={!collapsed}
                        pointsLeftWhenCollapsed
                        className={`bookmark-section-chevron${searching ? ' bookmark-section-chevron-hidden' : ''}`}
                      />
                    </span>
                  </button>

                  <ul className="bookmark-verses" hidden={collapsed}>
                    {visibleVerses.map(({ surah, ayah }) => {
                      const key = `${surah.id}:${ayah.number}` as BookmarkKey
                      const editingThis = editing?.surahId === surah.id && editing.ayah === ayah.number
                      return (
                        <BookmarkVerse
                          key={key}
                          verse={{ surah, ayah }}
                          confirming={pendingRemoval === key}
                          annotationsEnabled={annotationsEnabled}
                          note={editingThis ? draft : annotationFor(annotations, surah.id, ayah.number)}
                          editing={editingThis}
                          onEditNote={() => editAnnotation(surah.id, ayah.number)}
                          onNoteChange={setDraft}
                          onNoteDone={() => {
                            if (editingRef.current?.surahId === surah.id && editingRef.current.ayah === ayah.number) {
                              commitAnnotation()
                            }
                          }}
                          onOpen={() => {
                            commitAnnotation()
                            void appStore.openReading(surah.id, ayah.number)
                          }}
                          onRequestRemove={() => {
                            commitAnnotation()
                            setPendingRemoval(key)
                          }}
                          onKeep={() => setPendingRemoval(null)}
                          onRemove={() => {
                            appStore.toggleBookmarkAt(surah.id, ayah.number)
                            setPendingRemoval(null)
                          }}
                        />
                      )
                    })}
                  </ul>

                  {!collapsed &&
                  !searching &&
                  section.verses.length > BOOKMARK_SECTION_PREVIEW_LIMIT ? (
                    <button
                      type="button"
                      className="bookmark-disclosure"
                      aria-expanded={expanded}
                      onClick={() => {
                        commitAnnotation()
                        setExpandedSurahs((current) => {
                          const next = new Set(current)
                          if (expanded) next.delete(section.surah.id)
                          else next.add(section.surah.id)
                          return next
                        })
                      }}
                    >
                      {bookmarkDisclosureLabel(hiddenCount, expanded)}
                    </button>
                  ) : null}
                </section>
              )
            })}
          </div>
        </div>
      </div>
    </div>
  )
}

function BookmarkVerse({
  verse: { surah, ayah },
  confirming,
  annotationsEnabled,
  note,
  editing,
  onEditNote,
  onNoteChange,
  onNoteDone,
  onOpen,
  onRequestRemove,
  onKeep,
  onRemove,
}: {
  verse: BookmarkedVerse
  confirming: boolean
  annotationsEnabled: boolean
  /** The verse's note (the live draft while editing); null when it has none. */
  note: string | null
  editing: boolean
  onEditNote: () => void
  onNoteChange: (text: string) => void
  onNoteDone: () => void
  onOpen: () => void
  onRequestRemove: () => void
  onKeep: () => void
  onRemove: () => void
}) {
  const keepRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (confirming) keepRef.current?.focus()
  }, [confirming])

  const showNote = annotationsEnabled && (note != null || editing)
  // Holding the ribbon writes a note (Android onLongClick); its click is then spent.
  const holdTimer = useRef<number | null>(null)
  const swallowClick = useRef(false)
  const endHold = () => {
    if (holdTimer.current != null) window.clearTimeout(holdTimer.current)
    holdTimer.current = null
  }
  useEffect(() => endHold, [])

  return (
    <li className="bookmark-verse" data-editing={editing || undefined}>
      <button type="button" className="bookmark-verse-copy" onClick={onOpen} disabled={editing}>
        <span className="bookmark-verse-ar" lang="ar" dir="rtl">
          {ayah.text}
        </span>
        <span className="bookmark-verse-translation">{suppliedWords(ayah.translation)}</span>
      </button>
      {showNote ? (
        <BookmarkNote text={note} editing={editing} onStartEdit={onEditNote} onChange={onNoteChange} onDone={onNoteDone} />
      ) : null}
      <span
        className="bookmark-ribbon-hold"
        onPointerDown={(event) => {
          endHold()
          if (!annotationsEnabled || editing || (event.pointerType === 'mouse' && event.button !== 0)) return
          holdTimer.current = window.setTimeout(() => {
            holdTimer.current = null
            swallowClick.current = true
            onEditNote()
          }, 500)
        }}
        onPointerUp={endHold}
        onPointerCancel={endHold}
        onPointerLeave={endHold}
        onContextMenu={(event) => {
          if (!annotationsEnabled || editing) return
          event.preventDefault()
          onEditNote()
        }}
        onClickCapture={(event) => {
          if (!swallowClick.current) return
          swallowClick.current = false
          event.preventDefault()
          event.stopPropagation()
        }}
      >
        <VerseBookmarkRibbon
          bookmarked
          focused
          side="left"
          animateOnTap={false}
          interactive={!editing}
          edgeInset={2}
          topInset={0}
          bottomGap={12}
          ariaLabel={`Remove bookmark ${surah.id}:${ayah.number}`}
          onToggle={() => {
            onRequestRemove()
            return true
          }}
        />
      </span>
      <div
        className="bookmark-verse-meta"
        data-confirming={confirming}
        aria-live={confirming ? 'polite' : undefined}
        aria-atomic={confirming ? 'true' : undefined}
      >
        {confirming ? (
          <div className="bookmark-remove-confirmation">
            <span>Remove this bookmark?</span>
            <button ref={keepRef} type="button" onClick={onKeep}>
              Keep
            </button>
            <button type="button" className="confirm-remove" onClick={onRemove}>
              Remove
            </button>
          </div>
        ) : (
          <>
            <span className="bookmark-verse-ayah">
              Ayah{' '}
              <strong>{ayah.number}</strong>
            </span>
            {annotationsEnabled && !showNote ? (
              <button type="button" className="bookmark-add-note" onClick={onEditNote}>
                Add note
              </button>
            ) : null}
          </>
        )}
      </div>
    </li>
  )
}

/** The reader's note on a saved verse, in the annotation hand: tap it to
 * write in place. Saves as it closes; an emptied note is removed. */
function BookmarkNote({
  text,
  editing,
  onStartEdit,
  onChange,
  onDone,
}: {
  text: string | null
  editing: boolean
  onStartEdit: () => void
  onChange: (text: string) => void
  onDone: () => void
}) {
  const fieldRef = useRef<HTMLTextAreaElement>(null)
  useLayoutEffect(() => {
    const field = fieldRef.current
    if (!editing || !field) return
    field.focus({ preventScroll: true })
    const end = field.value.length
    field.setSelectionRange(end, end)
  }, [editing])
  // The field grows with the note; no resize handle, no box.
  useLayoutEffect(() => {
    const field = fieldRef.current
    if (!field) return
    field.style.height = 'auto'
    field.style.height = `${field.scrollHeight}px`
  }, [text, editing])

  if (!editing) {
    return (
      <button
        type="button"
        className="bookmark-note"
        aria-label={`Edit note: ${text ?? ''}`}
        onClick={onStartEdit}
      >
        {text}
      </button>
    )
  }
  return (
    <div className="bookmark-note-editor">
      <textarea
        ref={fieldRef}
        className="bookmark-note bookmark-note-field"
        rows={1}
        value={text ?? ''}
        placeholder="Write a note…"
        aria-label="Note"
        autoCapitalize="sentences"
        enterKeyHint="done"
        onChange={(event) => onChange(event.target.value)}
        onBlur={onDone}
        onKeyDown={(event) => {
          if ((event.key === 'Enter' && !event.shiftKey) || event.key === 'Escape') {
            event.preventDefault()
            // Done ends the note, never the sheet.
            event.stopPropagation()
            event.currentTarget.blur()
          }
        }}
      />
    </div>
  )
}

function BookmarkEmptyState({ query }: { query: string }) {
  const needle = query.trim()
  return (
    <div className="bookmarks-empty">
      <p>
        {needle
          ? `No marked verse matches “${needle}”.`
          : 'Your marked verses will gather here.'}
      </p>
      {!needle ? <small>Mark a verse in the reader to return to it later.</small> : null}
    </div>
  )
}

function SearchGlyph() {
  return (
    <svg className="home-search-icon" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="m15.4 15.4 4.2 4.2" />
    </svg>
  )
}

function ClearGlyph() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="m7 7 10 10M17 7 7 17" />
    </svg>
  )
}
