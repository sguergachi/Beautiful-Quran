import { useEffect, useMemo, useRef, useState } from 'react'
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
  const [query, setQuery] = useState('')
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
        <header className="bookmarks-header">
          <div>
            <h1>Bookmarks</h1>
          </div>
          <button
            type="button"
            onClick={() => {
              // Search may still hold focus after the sheet peels away; blur
              // so the mobile keyboard does not follow to Chapters.
              if (document.activeElement instanceof HTMLElement) {
                document.activeElement.blur()
              }
              appStore.revealLayer(COVER_LAYER)
            }}
          >
            Chapters&nbsp; →
          </button>
        </header>

        <div className="bookmarks-search">
          <div className="home-search">
            <SearchGlyph />
            <PaperInput
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
                onClick={() => changeQuery('')}
              >
                <ClearGlyph />
              </button>
            ) : null}
          </div>
        </div>

        <div className="edge-fade bookmarks-edge-fade">
          <div className="scroll bookmarks-scroll">
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
                      return (
                        <BookmarkVerse
                          key={key}
                          verse={{ surah, ayah }}
                          confirming={pendingRemoval === key}
                          onOpen={() => void appStore.openSurah(surah.id, ayah.number)}
                          onRequestRemove={() => setPendingRemoval(key)}
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
  onOpen,
  onRequestRemove,
  onKeep,
  onRemove,
}: {
  verse: BookmarkedVerse
  confirming: boolean
  onOpen: () => void
  onRequestRemove: () => void
  onKeep: () => void
  onRemove: () => void
}) {
  const keepRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (confirming) keepRef.current?.focus()
  }, [confirming])

  return (
    <li className="bookmark-verse">
      <button type="button" className="bookmark-verse-copy" onClick={onOpen}>
        <span className="bookmark-verse-ar" lang="ar" dir="rtl">
          {ayah.text}
        </span>
        <span className="bookmark-verse-translation">{ayah.translation}</span>
      </button>
      <VerseBookmarkRibbon
        bookmarked
        focused
        side="left"
        animateOnTap={false}
        edgeInset={2}
        topInset={8}
        bottomGap={12}
        ariaLabel={`Remove bookmark ${surah.id}:${ayah.number}`}
        onToggle={() => {
          onRequestRemove()
          return true
        }}
      />
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
          <span className="bookmark-verse-ayah">
            Ayah{' '}
            <strong>{ayah.number}</strong>
          </span>
        )}
      </div>
    </li>
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
