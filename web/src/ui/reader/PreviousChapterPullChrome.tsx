import { memo } from 'react'
import type { Surah } from '../../data/models'
import './chapterPull.css'

/**
 * Android `PreviousChapterPullChrome`: the band revealed above the page while
 * pulling down past the chapter's top — "PREVIOUS", the chapter's name, and
 * an Open pill that fills with the pull. Its height (`--prev-reveal`) and
 * fill (`--prev-pull`) are painted by [useChapterPull] without re-rendering.
 */
export const PreviousChapterPullChrome = memo(function PreviousChapterPullChrome({
  surah,
  onOpen,
}: {
  surah: Surah
  onOpen: () => void
}) {
  return (
    <div className="prev-chapter-pull" aria-hidden="true">
      <div className="prev-chapter-pull-inner">
        <span className="next-chapter-label">Previous</span>
        <span className="prev-chapter-pull-name">{surah.nameTransliteration}</span>
        <button
          type="button"
          className="next-chapter-pill chapter-pull-pill"
          tabIndex={-1}
          onClick={onOpen}
          aria-label={`Open ${surah.nameTransliteration}`}
        >
          <span className="next-chapter-pill-fill" aria-hidden="true" />
          <span className="next-chapter-pill-label">Open</span>
          <svg className="next-chapter-pill-arrow" viewBox="0 0 18 18" width="18" height="18" aria-hidden="true">
            <path
              d="M4 11.2 L9 6.8 L14 11.2"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>
      </div>
    </div>
  )
})
