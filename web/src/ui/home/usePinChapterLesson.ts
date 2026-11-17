import { useCallback, useEffect, useRef, useState } from 'react'
import { dismissEducation, isEducationDismissed } from '../../data/education'
import { COVER_LAYER, READER_LAYER } from '../../store/appStore'
import type { StackLayer } from '../paper/stack'
import { PinChapterLesson } from './pinChapterLesson'

export type PinChapterLessonState = {
  /** The chapter that earned the lesson, once Chapters is back. */
  chapter: number | null
  open: boolean
  setOpen: (open: boolean) => void
  rendered: boolean
  setRendered: (rendered: boolean) => void
  /** The lesson's vellum is up or still closing: the sheet holds still. */
  blocking: boolean
  /** Whether the lesson may still be offered (developer guides, not spent). */
  allowed: () => boolean
  /** Puts an open lesson away for good (Got it, a scroll, leaving, a pin). */
  close: () => void
  complete: () => void
}

/**
 * Android `HomeViewModel.observePinTipVisit` + `shouldShowPinTip`: behind the
 * developer guide gate, once. Reading time is the reader on top with the page
 * visible; Chapters on top ends the visit.
 */
export function usePinChapterLesson({
  stackLayer,
  readingChapterId,
  pinnedCount,
  developerMode,
  guidesEnabled,
}: {
  stackLayer: StackLayer
  readingChapterId: number
  pinnedCount: number
  developerMode: boolean
  guidesEnabled: boolean
}): PinChapterLessonState {
  const lesson = useRef(new PinChapterLesson())
  const [chapter, setChapter] = useState<number | null>(null)
  const [open, setOpen] = useState(false)
  const [rendered, setRendered] = useState(false)
  const [visible, setVisible] = useState(
    () => typeof document === 'undefined' || document.visibilityState === 'visible',
  )
  const allowed = useCallback(
    () => developerMode && guidesEnabled && !isEducationDismissed('pin_chapter'),
    [developerMode, guidesEnabled],
  )

  useEffect(() => {
    const onVisibility = () => setVisible(document.visibilityState === 'visible')
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [])

  const reading = stackLayer === READER_LAYER && visible && readingChapterId > 0
  const onChapters = stackLayer === COVER_LAYER
  useEffect(() => {
    const current = lesson.current
    if (!allowed()) {
      current.dismiss()
      setChapter(null)
      return
    }
    current.observe(readingChapterId, reading, onChapters, performance.now())
    setChapter(current.offeredChapter)
    return () => current.pauseReading(performance.now())
  }, [readingChapterId, reading, onChapters, allowed])

  const openRef = useRef(open)
  openRef.current = open
  const close = useCallback(() => {
    if (!openRef.current) return
    openRef.current = false
    dismissEducation('pin_chapter')
    lesson.current.dismiss()
    setChapter(null)
    setOpen(false)
  }, [])

  return {
    chapter: pinnedCount === 0 ? chapter : null,
    open,
    setOpen,
    rendered,
    setRendered,
    blocking: open || rendered,
    allowed,
    close,
    complete: close,
  }
}
